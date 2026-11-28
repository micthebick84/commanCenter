# netisMaker public stack launcher for Windows (PowerShell 5.1+).
# Windows port of scripts/start-public.sh. Starts the origin services that a
# Cloudflare Tunnel points at:
#   1) netis-auth      (:9000)  public issuer + forward headers + CORS
#   2) netisMaker API  (:8090)  JWKS from localhost, iss validated against the public issuer
#   3) frontend        (:3001)  production build (Nitro), public NUXT_PUBLIC_* values
#   4) worker + interview service (only with -WithWorkers; they consume claude quota)
#      + worker-rs, a native Rust worker (only with -WithWorkers AND WORKER_RS_ENABLED=true; off by default)
#
# The tunnel (cloudflared) is NOT managed here. Expected ingress in
# %USERPROFILE%\.cloudflared\config.yml:
#     ingress:
#       - hostname: auth.<domain>
#         service: http://localhost:9000
#       - hostname: app.<domain>
#         service: http://localhost:3001
#       - service: http_status:404
#
# Usage:
#   .\scripts\start-public.ps1 -AuthOrigin https://auth.example.com -AppOrigin https://app.example.com
#   .\scripts\start-public.ps1 -WithWorkers
#   .\scripts\start-public.ps1 -Rebuild        rebuild the auth/api/worker jars even if this commit's jar exists
#   .\scripts\stop-public.ps1
# Java services (auth, api, worker) run as java -jar (since 2026-11-28, spec
# docs/superpowers/specs/2026-11-28-live-run-jar-design.md): right before starting one, its source folder
# (this checkout / netis-auth) is built with gradlew bootJar unless %USERPROFILE%\netis-maker\run\jars
# already has <prefix>-<sha7>.jar for a clean tree. The copy there is what runs, so a running jar is never
# overwritten; a tree with uncommitted changes is rebuilt every time. A failed build fails the service
# (it never falls back to an older jar). Build logs: .run\<name>-build.log. Roll back = check out the
# previous commit and restart (its jar is reused when still kept: 5 newest per prefix + running ones).
# worker-rs (native exe, spec docs/superpowers/specs/2026-11-28-rust-worker-skeleton-design.md section 10): built with
# cargo build --release --locked in worker-rs\ and run from %USERPROFILE%\netis-maker\run\bin\netis-worker-rs-<sha7>[-dirty-<time>].exe
# by the same copy-then-run rules as the jars (5 newest + running ones kept). Build log: .run\worker-rs-build.log.
# Logs / pids: .run\{auth,api,front,worker,worker-rs,interview}.log|.pid
# Previous runs' logs: .run\logs\<name>-<yyyyMMdd-HHmmss-fff>.log (newest 20 of each, see Save-PreviousLog)|.json
#   .pid  = the cmd wrapper's pid (unchanged); .json = the real JVM / node process (scripts/public-proc.ps1)
#
# Settings come from (highest priority first): parameters, environment variables,
# then %USERPROFILE%\netis-maker\public.env (KEY=VALUE lines; see install-autostart.ps1).
#   AUTH_ORIGIN / APP_ORIGIN    public origins
#   PG_DIR                      zip-installed PostgreSQL root; started if :5432 is not listening
#   INTERVIEW_CONCURRENCY       interview service: question/interview turns run at once (default 1 = serial)
#   WORKER_RS_ENABLED           true = also start worker-rs with -WithWorkers (WORKER_RS_ID default win-rs-worker-1,
#                               WORKER_RS_KINDS default ANALYSIS); anything else = worker-rs is not touched at all
# Secrets are read from there too, never stored in this file:
#   SPRING_DATASOURCE_PASSWORD  DB password (both Spring apps; falls back to application.yml)
#   WORKER_API_KEY              shared secret for API <-> worker/interview (dev default if unset)
#   GITHUB_PAT                  optional, private repos / push
#   NETISMAKER_DB_SECRET_KEY    question-session DB connections: password encryption key (API; feature off if unset)
#   NETISMAKER_DB_SECRET_SALT   hex, even length, 16+ chars; both values required - back them up
#
# A service whose port is already listening is skipped. To re-apply settings run stop-public.ps1 first.
# ASCII only on purpose: Windows PowerShell 5.1 misreads BOM-less UTF-8 scripts.

[CmdletBinding()]
param(
    [string]$AuthOrigin,
    [string]$AppOrigin,
    [string]$AuthDir,
    [string]$JavaHome,
    [string]$EnvFile = (Join-Path $env:USERPROFILE 'netis-maker\public.env'),
    [switch]$WithWorkers,
    [switch]$RebuildFront,
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'

# Shared helpers: Import-PublicEnv, Test-ServiceAlive, Find-AppPid, Write-ProcRecord, ...
. (Join-Path $PSScriptRoot 'public-proc.ps1')

# ---- settings file (KEY=VALUE lines, '#' comments) --------------------------
# Lives outside the repo so secrets never get committed. Variables already set
# in the environment win over the file. Used by the logon autostart task.
# Keys: AUTH_ORIGIN, APP_ORIGIN, PG_DIR, SPRING_DATASOURCE_PASSWORD, WORKER_API_KEY, GITHUB_PAT, ...
Import-PublicEnv $EnvFile
if (-not $AuthOrigin) { $AuthOrigin = if ($env:AUTH_ORIGIN) { $env:AUTH_ORIGIN } else { 'https://auth.micthebick.dev' } }
if (-not $AppOrigin)  { $AppOrigin  = if ($env:APP_ORIGIN)  { $env:APP_ORIGIN }  else { 'https://app.micthebick.dev' } }
if (-not $JavaHome)   { $JavaHome   = $env:JAVA_HOME }

$Root     = Split-Path -Parent $PSScriptRoot
$FrontDir = Join-Path $Root 'frontend'
$IvDir    = Join-Path $Root 'netismaker-interview-service'
$RunDir   = Join-Path $Root '.run'
$JarDir   = Join-Path $env:USERPROFILE 'netis-maker\run\jars'
if (-not $AuthDir) { $AuthDir = Join-Path (Split-Path -Parent $Root) 'netis-auth' }
New-Item -ItemType Directory -Force $RunDir | Out-Null
New-Item -ItemType Directory -Force $JarDir | Out-Null

$AuthOrigin = $AuthOrigin.TrimEnd('/')
$AppOrigin  = $AppOrigin.TrimEnd('/')

# ---- JDK 21 -----------------------------------------------------------------
if (-not $JavaHome) {
    $jdk = Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '21' } | Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { $JavaHome = $jdk.FullName }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
    throw "JDK 21 not found. Pass -JavaHome or set JAVA_HOME."
}

# ---- helpers ----------------------------------------------------------------
function Test-PortListening([int]$Port) {
    [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

# Record the REAL process (JVM / node) of a service this run just started and found ready.
# $AppRoot is only used by the worker lookup (its build\classes path); the others are found by port / child.
function Register-Service([string]$Name, [int]$WrapperPid, [string]$AppRoot) {
    $appPid = Find-AppPid $Name $WrapperPid $AppRoot
    if ($null -eq $appPid) {
        Write-Host "[warn] ${Name}: real process not found - stop falls back to the wrapper"
    }
    Write-ProcRecord $RunDir $Name $WrapperPid $appPid
}

# Keep the previous run's log under .run\logs (Save-PreviousLog) and start the new run from an empty
# file. Without this, right after Start-Process cmd may not have truncated the old file yet, and
# Wait-Log reads the previous run's "Started ..." line and calls the new process ready too early
# (then Register-Service looks for a JVM / node that does not exist yet).
function Reset-Log([string]$LogName) {
    $null = Save-PreviousLog $RunDir $LogName
    $log = Join-Path $RunDir $LogName
    if (Test-Path -LiteralPath $log) {
        try { Clear-Content -LiteralPath $log -ErrorAction Stop }
        catch { Write-Host "[warn] $LogName is still in use - its old lines may be read as this run's" }
    }
}

# Start a command line detached, with extra env vars, stdout+stderr -> .run\<name>.log.
# The child inherits this process' environment, so vars are set then restored.
function Start-Service-Proc([string]$Name, [string]$WorkDir, [string]$CommandLine, [hashtable]$EnvVars) {
    $log = Join-Path $RunDir "$Name.log"
    Reset-Log "$Name.log"
    $saved = @{}
    foreach ($k in $EnvVars.Keys) {
        $saved[$k] = [Environment]::GetEnvironmentVariable($k, 'Process')
        [Environment]::SetEnvironmentVariable($k, [string]$EnvVars[$k], 'Process')
    }
    try {
        $p = Start-Process -FilePath $env:ComSpec `
            -ArgumentList "/c `"$CommandLine > `"$log`" 2>&1`"" `
            -WorkingDirectory $WorkDir -WindowStyle Hidden -PassThru
    } finally {
        foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k], 'Process') }
    }
    # a newly started service must not inherit the record of an earlier run (stale appPid would fool Test-ServiceAlive)
    Remove-Item -LiteralPath (Join-Path $RunDir "$Name.json") -Force -ErrorAction SilentlyContinue
    Set-Content -Path (Join-Path $RunDir "$Name.pid") -Value $p.Id -Encoding ascii
    return $p.Id
}

# Run a command line to completion through cmd with all output in a log file.
# (Calling npm directly would turn its stderr lines into terminating errors under
# $ErrorActionPreference = 'Stop' on Windows PowerShell 5.1.)
function Invoke-Logged([string]$WorkDir, [string]$CommandLine, [string]$LogName) {
    $log = Join-Path $RunDir $LogName
    Reset-Log $LogName
    $p = Start-Process -FilePath $env:ComSpec -ArgumentList "/c `"$CommandLine > `"$log`" 2>&1`"" `
        -WorkingDirectory $WorkDir -WindowStyle Hidden -PassThru
    # Not -Wait: that also waits for descendants, and pg_ctl leaves postgres.exe running.
    $null = $p.Handle   # cache the handle so ExitCode is available after exit
    $p.WaitForExit()
    return $p.ExitCode
}

# $ProcessId (optional, the cmd wrapper): once it has exited without the ok line, stop waiting -
# a java -jar service that dies on startup (DB down, bad migration) prints no Gradle FAILURE line.
function Wait-Log([string]$Name, [string]$OkPattern, [string]$FailPattern, [int]$TimeoutSec = 180, [int]$ProcessId = 0) {
    $log = Join-Path $RunDir "$Name.log"
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $exited = ($ProcessId -gt 0) -and -not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
        if (Test-Path $log) {
            if (Select-String -Path $log -Pattern $OkPattern -Quiet)   { return $true }
            if (Select-String -Path $log -Pattern $FailPattern -Quiet) { return $false }
        }
        if ($exited) { return $false }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Wait-Port([int]$Port, [int]$TimeoutSec = 90) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortListening $Port) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

$SpringOk   = 'Started .*Application'
$SpringFail = 'APPLICATION FAILED|Application run failed|BUILD FAILED|FAILURE:'
$failed = @()

# ---- jar build / run (auth, api, worker) -------------------------------------
$JavaExe = Join-Path $JavaHome 'bin\java.exe'
# What gradlew bootRun used to pass (logs are UTF-8, locale ko/KR); bootRun's -XX:TieredStopAtLevel=1
# (fast-start only) is left out on purpose.
$JavaOpts = '-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Duser.language=ko -Duser.country=KR'
$script:jarCache = @{}

# The jar copy to run for $SrcDir's current state, building it when needed; $null on any failure
# (already reported as [FAIL]). api and worker share one build per run of this script.
function Get-ServiceJar([string]$Prefix, [string]$SrcDir, [string]$Name) {
    $cacheKey = "$Prefix|$SrcDir"
    if ($script:jarCache.ContainsKey($cacheKey)) { return $script:jarCache[$cacheKey] }
    # git writes to stderr on failure; under 'Stop' that would throw instead of being reported
    $ErrorActionPreference = 'Continue'
    $sha = [string](& git -C $SrcDir rev-parse HEAD 2>$null)
    if ($LASTEXITCODE -ne 0 -or $sha -notmatch '^[0-9a-f]{7,}$') {
        Write-Host "[FAIL] ${Name}: cannot read the commit of $SrcDir (git rev-parse HEAD)"; return $null
    }
    $porcelain = @(& git -C $SrcDir status --porcelain 2>$null)
    if ($LASTEXITCODE -ne 0) { Write-Host "[FAIL] ${Name}: git status failed in $SrcDir"; return $null }
    $dirty = $porcelain.Count -gt 0
    $target = Join-Path $JarDir (Get-JarName $Prefix $sha '' (Get-Date))
    if ($dirty) {
        $target = Join-Path $JarDir (Get-JarName $Prefix $sha 'dirty' (Get-Date))
        Write-Host "[warn] ${Name}: $SrcDir has uncommitted changes - building them into $(Split-Path -Leaf $target)"
    } elseif (Test-Path -LiteralPath $target) {
        if (-not $Rebuild) { $script:jarCache[$cacheKey] = $target; return $target }
        # forced rebuild: never overwrite a copy that may be running
        $target = Join-Path $JarDir (Get-JarName $Prefix $sha 'rebuild' (Get-Date))
    }
    Write-Host "[..]   $Name building $(Split-Path -Leaf $target) (gradlew bootJar)"
    $tasks = if ($Rebuild) { 'clean bootJar' } else { 'bootJar --rerun' }
    $since = (Get-Date).AddSeconds(-2)
    $savedJavaHome = $env:JAVA_HOME
    $env:JAVA_HOME = $JavaHome
    try {
        $exit = Invoke-Logged $SrcDir "`"$(Join-Path $SrcDir 'gradlew.bat')`" $tasks -q --no-daemon" "$Name-build.log"
    } finally { $env:JAVA_HOME = $savedJavaHome }
    if ($exit -ne 0) { Write-Host "[FAIL] $Name build - see .run\$Name-build.log"; return $null }
    $built = Select-BootJar (Get-ChildItem (Join-Path $SrcDir 'build\libs') -File -ErrorAction SilentlyContinue) $since
    if (-not $built) { Write-Host "[FAIL] $Name build - no single new boot jar in $SrcDir\build\libs"; return $null }
    # Copy to .part, then rename: a copy cut short (reboot, task time limit) must never leave a truncated
    # <sha7>.jar that every later clean start would reuse. An existing target is never overwritten.
    $tmp = "$target.part"
    try {
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction Stop }
        [System.IO.File]::Copy($built, $tmp, $false)
        Move-Item -LiteralPath $tmp -Destination $target -ErrorAction Stop
    } catch {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
        Write-Host "[FAIL] $Name - cannot copy the jar to ${target}: $($_.Exception.Message)"; return $null
    }
    $script:jarCache[$cacheKey] = $target
    return $target
}

# Keep the 5 newest copies per prefix and every copy a running java.exe uses; never throws.
function Remove-OldJars {
    try {
        $files = Get-ChildItem $JarDir -File -Filter '*.jar' -ErrorAction SilentlyContinue
        $inUse = @(Select-JarsInUse (Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue) $JarDir)
        foreach ($prefix in @('netis-maker', 'netis-auth')) {
            foreach ($path in @(Select-JarsToPrune $files $prefix 5 $inUse)) {
                try { Remove-Item -LiteralPath $path -Force -ErrorAction Stop }
                catch { Write-Host "[warn] old jar not removed: $path ($($_.Exception.Message))" }
            }
        }
    } catch {
        Write-Host "[warn] old jar cleanup skipped: $($_.Exception.Message)"
    }
}

# ---- native exe build / run (worker-rs; only used when WORKER_RS_ENABLED=true) -----------------------
# Same idea as Get-ServiceJar: cargo builds in the source folder, the exe is copied to
# %USERPROFILE%\netis-maker\run\bin under a commit-named file and that copy is run.
$BinDir = Join-Path $env:USERPROFILE 'netis-maker\run\bin'
$script:workerRsSha7 = ''

# The exe copy to run for $SrcDir's current state, building it when needed; $null on any failure
# (already reported as [FAIL]). Never falls back to an older copy. Sets $script:workerRsSha7.
function Get-ServiceExe([string]$Prefix, [string]$SrcDir, [string]$Name) {
    # git / cargo write to stderr; under 'Stop' that would throw instead of being reported
    $ErrorActionPreference = 'Continue'
    $sha = [string](& git -C $SrcDir rev-parse HEAD 2>$null)
    if ($LASTEXITCODE -ne 0 -or $sha -notmatch '^[0-9a-f]{7,}$') {
        Write-Host "[FAIL] ${Name}: cannot read the commit of $SrcDir (git rev-parse HEAD)"; return $null
    }
    $script:workerRsSha7 = $sha.Substring(0, 7)
    $porcelain = @(& git -C $SrcDir status --porcelain 2>$null)
    if ($LASTEXITCODE -ne 0) { Write-Host "[FAIL] ${Name}: git status failed in $SrcDir"; return $null }
    $dirty = $porcelain.Count -gt 0
    New-Item -ItemType Directory -Force $BinDir | Out-Null
    $target = Join-Path $BinDir (Get-ExeName $Prefix $sha '' (Get-Date))
    if ($dirty) {
        $target = Join-Path $BinDir (Get-ExeName $Prefix $sha 'dirty' (Get-Date))
        Write-Host "[warn] ${Name}: $SrcDir has uncommitted changes - building them into $(Split-Path -Leaf $target)"
    } elseif (Test-Path -LiteralPath $target) {
        if (-not $Rebuild) { return $target }
        # forced rebuild: never overwrite a copy that may be running
        $target = Join-Path $BinDir (Get-ExeName $Prefix $sha 'rebuild' (Get-Date))
    }
    $cargo = Join-Path $env:USERPROFILE '.cargo\bin\cargo.exe'
    if (-not (Test-Path -LiteralPath $cargo)) { Write-Host "[FAIL] ${Name}: cargo not found ($cargo)"; return $null }
    Write-Host "[..]   $Name building $(Split-Path -Leaf $target) (cargo build --release)"
    if ($Rebuild) {
        $cleanExit = Invoke-Logged $SrcDir "`"$cargo`" clean" "$Name-clean.log"
        if ($cleanExit -ne 0) { Write-Host "[FAIL] $Name cargo clean - see .run\$Name-clean.log"; return $null }
    }
    $exit = Invoke-Logged $SrcDir "`"$cargo`" build --release --locked" "$Name-build.log"
    if ($exit -ne 0) { Write-Host "[FAIL] $Name build - see .run\$Name-build.log"; return $null }
    $built = Join-Path $SrcDir 'target\release\netis-worker-rs.exe'
    if (-not (Test-Path -LiteralPath $built)) { Write-Host "[FAIL] $Name build - $built not found"; return $null }
    # Copy to .part, then rename: a copy cut short must never leave a truncated <sha7>.exe that every
    # later clean start would reuse. An existing target is never overwritten.
    $tmp = "$target.part"
    try {
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction Stop }
        [System.IO.File]::Copy($built, $tmp, $false)
        Move-Item -LiteralPath $tmp -Destination $target -ErrorAction Stop
    } catch {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
        Write-Host "[FAIL] $Name - cannot copy the exe to ${target}: $($_.Exception.Message)"; return $null
    }
    return $target
}

# Keep the 5 newest worker-rs copies and every copy a running process uses; never throws.
function Remove-OldExes {
    try {
        $files = Get-ChildItem $BinDir -File -Filter '*.exe' -ErrorAction SilentlyContinue
        $inUse = @(Select-ExesInUse (Get-CimInstance Win32_Process -ErrorAction SilentlyContinue) $BinDir)
        foreach ($path in @(Select-FilesToPrune $files 'netis-worker-rs' '.exe' 5 $inUse)) {
            try { Remove-Item -LiteralPath $path -Force -ErrorAction Stop }
            catch { Write-Host "[warn] old exe not removed: $path ($($_.Exception.Message))" }
        }
    } catch {
        Write-Host "[warn] old exe cleanup skipped: $($_.Exception.Message)"
    }
}

Write-Host "netisMaker public stack"
Write-Host "  auth = $AuthOrigin"
Write-Host "  app  = $AppOrigin"
Write-Host "  jdk  = $JavaHome"
Write-Host "  auth dir = $AuthDir"
Write-Host ""

# cloudflared: started here only when CLOUDFLARED_TUNNEL names a tunnel (uses
# %USERPROFILE%\.cloudflared\config.yml). Leave it unset if cloudflared runs as a Windows service.
if (Get-Process cloudflared -ErrorAction SilentlyContinue) {
    Write-Host "[ok]   cloudflared is running"
} elseif ($env:CLOUDFLARED_TUNNEL) {
    $cf = if ($env:CLOUDFLARED_EXE) { $env:CLOUDFLARED_EXE } else { (Get-Command cloudflared -ErrorAction SilentlyContinue).Source }
    if (-not $cf) { $cf = Join-Path $env:USERPROFILE 'bin\cloudflared.exe' }
    if (Test-Path $cf) {
        Start-Service-Proc 'cloudflared' $Root "`"$cf`" tunnel --no-autoupdate run $($env:CLOUDFLARED_TUNNEL)" @{} | Out-Null
        if (Wait-Log 'cloudflared' 'Registered tunnel connection' 'ERR .*(failed|error)' 60) { Write-Host "[ok]   cloudflared tunnel '$($env:CLOUDFLARED_TUNNEL)' connected" }
        else { Write-Host "[FAIL] cloudflared - see .run\cloudflared.log"; $failed += 'cloudflared' }
    } else {
        Write-Host "[FAIL] cloudflared.exe not found (set CLOUDFLARED_EXE)"; $failed += 'cloudflared'
    }
} else {
    Write-Host "[warn] cloudflared is NOT running - the public domains will not resolve to this machine"
}
if (-not $env:SPRING_DATASOURCE_PASSWORD) {
    Write-Host "[warn] SPRING_DATASOURCE_PASSWORD not set - using the password in application.yml"
}
Write-Host ""

# ---- 0) PostgreSQL (:5432) - only when PG_DIR is set and nothing is listening ----
# For a zip-installed PostgreSQL that is not a Windows service.
if ($env:PG_DIR -and -not (Test-PortListening 5432)) {
    $pgCtl = Join-Path $env:PG_DIR 'bin\pg_ctl.exe'
    if (Test-Path $pgCtl) {
        Write-Host "[..]   postgres starting (:5432)"
        # PostgreSQL needs the VC++ runtime DLLs; the JDK ships them, so its bin dir
        # is a fallback for machines without the VC++ redistributable.
        $pgCmd = "set `"PATH=$JavaHome\bin;%PATH%`" && `"$pgCtl`" start -w -D `"$(Join-Path $env:PG_DIR 'data')`" -l `"$(Join-Path $env:PG_DIR 'postgresql.log')`""
        Invoke-Logged $env:PG_DIR $pgCmd 'postgres-start.log' | Out-Null
        if (Wait-Port 5432 60) { Write-Host "[ok]   postgres ready" }
        else { Write-Host "[FAIL] postgres - see .run\postgres-start.log"; $failed += 'postgres' }
    } else {
        Write-Host "[warn] PG_DIR set but $pgCtl not found"
    }
}

# auth, api and worker run as java -jar (Get-ServiceJar): the chain is cmd wrapper -> app JVM, inside this
# script's process tree. Gradle only runs to build the jar, with --no-daemon so it never hands the build to
# an idle daemon started somewhere else (a daemon started from a Claude Code tool shell lives in the Claude
# app's Job object and dies with the app - 2026-11-07, the API was down about 55 h when it ran on one);
# that single-use daemon exits with the build. Until 2026-11-28 the services ran under gradlew bootRun,
# which kept a Gradle client + daemon (about 0.6-0.8 GB) next to every app JVM.

# ---- 1) netis-auth (:9000) --------------------------------------------------
if (Test-PortListening 9000) {
    Write-Host "[skip] auth: port 9000 already listening"
} else {
    if (-not (Test-Path (Join-Path $AuthDir 'gradlew.bat'))) { throw "netis-auth not found at $AuthDir (pass -AuthDir)" }
    $authJar = Get-ServiceJar 'netis-auth' $AuthDir 'auth'
    if (-not $authJar) {
        $failed += 'auth'
    } else {
        Write-Host "[..]   auth starting (:9000) - $(Split-Path -Leaf $authJar)"
        $authPid = Start-Service-Proc 'auth' $AuthDir "`"$JavaExe`" $JavaOpts -jar `"$authJar`"" @{
            JAVA_HOME                       = $JavaHome
            AUTH_ISSUER_URI                 = $AuthOrigin
            SERVER_FORWARD_HEADERS_STRATEGY = 'framework'
            CORS_ALLOWED_ORIGINS            = "$AppOrigin,http://localhost:9000,http://localhost:3001,http://localhost:3000,http://localhost:8080"
        }
        # The API fetches JWKS from auth at startup, so wait for auth first.
        if (Wait-Log 'auth' $SpringOk $SpringFail 240 $authPid) { Write-Host "[ok]   auth ready"; Register-Service 'auth' $authPid $AuthDir }
        else { Write-Host "[FAIL] auth - see .run\auth.log"; $failed += 'auth' }
    }
}

# ---- 2) netisMaker API (:8090) ----------------------------------------------
$apiStarted = $false
$apiPid = 0
if (Test-PortListening 8090) {
    Write-Host "[skip] api: port 8090 already listening"
} else {
    $apiJar = Get-ServiceJar 'netis-maker' $Root 'api'
    if (-not $apiJar) {
        $failed += 'api'
    } else {
        Write-Host "[..]   api starting (:8090) - $(Split-Path -Leaf $apiJar)"
        $apiPid = Start-Service-Proc 'api' $Root "`"$JavaExe`" $JavaOpts -jar `"$apiJar`"" @{
            JAVA_HOME                                             = $JavaHome
            SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI = 'http://localhost:9000/oauth2/jwks'
            SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI  = $AuthOrigin
            CORS_ALLOWED_ORIGINS                                  = "$AppOrigin,http://localhost:3001,http://localhost:3000"
        }
        $apiStarted = $true
    }
}

# ---- 3) frontend (:3001) - production build ---------------------------------
$frontStarted = $false
$frontPid = 0
if (Test-PortListening 3001) {
    Write-Host "[skip] front: port 3001 already listening"
} else {
    $entry = Join-Path $FrontDir '.output\server\index.mjs'
    if ($RebuildFront -or -not (Test-Path $entry)) {
        Write-Host "[..]   front building (npm run build) - takes a minute"
        if (-not (Test-Path (Join-Path $FrontDir 'node_modules'))) {
            Invoke-Logged $FrontDir 'npm install' 'front-install.log' | Out-Null
        }
        $env:NUXT_IGNORE_LOCK = '1'
        try { $buildExit = Invoke-Logged $FrontDir 'npm run build' 'front-build.log' } finally { $env:NUXT_IGNORE_LOCK = $null }
        if ($buildExit -ne 0 -or -not (Test-Path $entry)) { Write-Host "[FAIL] front build - see .run\front-build.log"; $failed += 'front' }
    }
    if (Test-Path $entry) {
        Write-Host "[..]   front starting (:3001)"
        $frontPid = Start-Service-Proc 'front' $FrontDir 'node .output\server\index.mjs' @{
            PORT                     = '3001'
            HOST                     = '0.0.0.0'
            NUXT_PUBLIC_AUTH_ISSUER  = $AuthOrigin
            NUXT_PUBLIC_REDIRECT_URI = "$AppOrigin/oauth/callback"
            NUXT_PUBLIC_CLIENT_ID    = 'netis-maker-spa'
        }
        $frontStarted = $true
    }
}

if ($apiStarted) {
    if (Wait-Log 'api' $SpringOk $SpringFail 240 $apiPid) { Write-Host "[ok]   api ready"; Register-Service 'api' $apiPid $Root }
    else { Write-Host "[FAIL] api - see .run\api.log"; $failed += 'api' }
}
if ($frontStarted) {
    if (Wait-Port 3001 90) { Write-Host "[ok]   front ready"; Register-Service 'front' $frontPid $FrontDir }
    else { Write-Host "[FAIL] front - see .run\front.log"; $failed += 'front' }
}

# ---- 4) worker + interview service (optional) -------------------------------
if ($WithWorkers) {
    $workerKey = if ($env:WORKER_API_KEY) { $env:WORKER_API_KEY } else { 'dev-only-change-me' }
    if (-not $env:WORKER_API_KEY) { Write-Host "[warn] WORKER_API_KEY not set - using the dev default. Change it before exposing /worker/*." }

    $claudeCli = $env:CLAUDE_CLI
    if (-not $claudeCli) { $claudeCli = Join-Path $env:USERPROFILE '.local\bin\claude.exe' }
    if (-not (Test-Path $claudeCli)) { Write-Host "[warn] claude CLI not found at $claudeCli (set CLAUDE_CLI)" }
    $claudeCli = $claudeCli -replace '\\', '/'

    # superpowers plugin - interview service (brainstorming/writing-plans) and worker SDD implementation share it
    $sp = $env:SUPERPOWERS_PLUGIN_PATH
    if (-not $sp) {
        $spRoot = Join-Path $env:USERPROFILE '.claude\plugins\cache\claude-plugins-official\superpowers'
        $spDir = Get-ChildItem $spRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName 'skills\brainstorming') } |
            Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending | Select-Object -First 1
        if ($spDir) { $sp = $spDir.FullName }
    }
    if ($sp) { $sp = $sp -replace '\\', '/' }

    if (Test-ServiceAlive $RunDir 'worker') {
        Write-Host "[skip] worker: already running"
    } else {
        Write-Host "[..]   worker starting"
        $workerEnv = @{
            JAVA_HOME      = $JavaHome
            API_BASE_URL   = 'http://localhost:8090'
            WORKER_API_KEY = $workerKey
            WORKER_ID      = $(if ($env:WORKER_ID) { $env:WORKER_ID } else { 'win-worker-1' })
            CLAUDE_CLI     = $claudeCli
        }
        if ($sp) { $workerEnv['SUPERPOWERS_PLUGIN_PATH'] = $sp }
        else { Write-Host "[warn] superpowers plugin not found - SDD implementation requests fall back to REVIEW (set SUPERPOWERS_PLUGIN_PATH)" }
        $workerJar = Get-ServiceJar 'netis-maker' $Root 'worker'
        if (-not $workerJar) {
            $failed += 'worker'
        } else {
            $workerPid = Start-Service-Proc 'worker' $Root "`"$JavaExe`" $JavaOpts -jar `"$workerJar`" --spring.profiles.active=worker" $workerEnv
            # Find-AppPid's worker lookup takes the jar path (the JVM is the wrapper's child running it)
            if (Wait-Log 'worker' $SpringOk $SpringFail 240 $workerPid) { Write-Host "[ok]   worker ready"; Register-Service 'worker' $workerPid $workerJar }
            else { Write-Host "[FAIL] worker - see .run\worker.log"; $failed += 'worker' }
        }
    }

    # worker-rs (native exe, opt-in): chain is cmd wrapper -> netis-worker-rs-<sha7>.exe
    if ($env:WORKER_RS_ENABLED -eq 'true') {
        if (Test-ServiceAlive $RunDir 'worker-rs') {
            Write-Host "[skip] worker-rs: already running"
        } else {
            $rsExe = Get-ServiceExe 'netis-worker-rs' (Join-Path $Root 'worker-rs') 'worker-rs'
            if (-not $rsExe) {
                $failed += 'worker-rs'
            } else {
                Write-Host "[..]   worker-rs starting - $(Split-Path -Leaf $rsExe)"
                $rsPid = Start-Service-Proc 'worker-rs' $Root "`"$rsExe`"" @{
                    API_BASE_URL   = 'http://localhost:8090'
                    WORKER_API_KEY = $workerKey
                    CLAUDE_CLI     = $claudeCli
                    WORKER_ID      = $(if ($env:WORKER_RS_ID) { $env:WORKER_RS_ID } else { 'win-rs-worker-1' })
                    WORKER_KINDS   = $(if ($env:WORKER_RS_KINDS) { $env:WORKER_RS_KINDS } else { 'ANALYSIS' })
                    WORKER_VERSION = "rs-$($script:workerRsSha7)"
                }
                # Find-AppPid's worker-rs lookup takes the exe path (the process is the wrapper's child running it)
                if (Wait-Log 'worker-rs' 'worker-rs started:' 'worker-rs fatal' 60 $rsPid) { Write-Host "[ok]   worker-rs ready"; Register-Service 'worker-rs' $rsPid $rsExe }
                else { Write-Host "[FAIL] worker-rs - see .run\worker-rs.log"; $failed += 'worker-rs' }
            }
        }
    }

    if (Test-ServiceAlive $RunDir 'interview') {
        Write-Host "[skip] interview: already running"
    } else {
        if (-not $sp) {
            Write-Host "[FAIL] interview - superpowers plugin not found (set SUPERPOWERS_PLUGIN_PATH)"; $failed += 'interview'
        } else {
            if (-not (Test-Path (Join-Path $IvDir 'node_modules'))) {
                Invoke-Logged $IvDir 'npm install' 'interview-install.log' | Out-Null
            }
            Write-Host "[..]   interview starting"
            $interviewPid = Start-Service-Proc 'interview' $IvDir 'node --import tsx src/index.ts' @{
                API_BASE_URL            = 'http://localhost:8090'
                WORKER_API_KEY          = $workerKey
                WORKER_ID               = 'interview-worker-1'
                CLAUDE_CLI              = $claudeCli
                SUPERPOWERS_PLUGIN_PATH = $sp
            }
            if (Wait-Log 'interview' '\[interview-service\].*polling' 'Missing required env|Error:' 60) { Write-Host "[ok]   interview ready"; Register-Service 'interview' $interviewPid $IvDir }
            else { Write-Host "[FAIL] interview - see .run\interview.log"; $failed += 'interview' }
        }
    }
}

Remove-OldJars
if ($env:WORKER_RS_ENABLED -eq 'true') { Remove-OldExes }

Write-Host ""
if ($failed.Count -gt 0) {
    Write-Host "Finished with failures: $($failed -join ', ')"
    exit 1
}
Write-Host "Done. Public address: $AppOrigin"
Write-Host "The OAuth client 'netis-maker-spa' must list these exact URIs in netis-auth:"
Write-Host "  redirect_uris             : $AppOrigin/oauth/callback"
Write-Host "  post_logout_redirect_uris : $AppOrigin/login?logout=true"
Write-Host "Stop: .\scripts\stop-public.ps1"
