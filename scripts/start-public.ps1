# netisMaker public stack launcher for Windows (PowerShell 5.1+).
# Windows port of scripts/start-public.sh. Starts the origin services that a
# Cloudflare Tunnel points at:
#   1) netis-auth      (:9000)  public issuer + forward headers + CORS
#   2) netisMaker API  (:8090)  JWKS from localhost, iss validated against the public issuer
#   3) frontend        (:3001)  production build (Nitro), public NUXT_PUBLIC_* values
#   4) worker + interview service (only with -WithWorkers; they consume claude quota)
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
#   .\scripts\stop-public.ps1
# Logs / pids: .run\{auth,api,front,worker,interview}.log|.pid
#
# Settings come from (highest priority first): parameters, environment variables,
# then %USERPROFILE%\netis-maker\public.env (KEY=VALUE lines; see install-autostart.ps1).
#   AUTH_ORIGIN / APP_ORIGIN    public origins
#   PG_DIR                      zip-installed PostgreSQL root; started if :5432 is not listening
# Secrets are read from there too, never stored in this file:
#   SPRING_DATASOURCE_PASSWORD  DB password (both Spring apps; falls back to application.yml)
#   WORKER_API_KEY              shared secret for API <-> worker/interview (dev default if unset)
#   GITHUB_PAT                  optional, private repos / push
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
    [switch]$RebuildFront
)

$ErrorActionPreference = 'Stop'

# ---- settings file (KEY=VALUE lines, '#' comments) --------------------------
# Lives outside the repo so secrets never get committed. Variables already set
# in the environment win over the file. Used by the logon autostart task.
# Keys: AUTH_ORIGIN, APP_ORIGIN, PG_DIR, SPRING_DATASOURCE_PASSWORD, WORKER_API_KEY, GITHUB_PAT, ...
if (Test-Path $EnvFile) {
    foreach ($line in Get-Content $EnvFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
            $k = $Matches[1]; $v = $Matches[2].Trim('"').Trim("'")
            if (-not [Environment]::GetEnvironmentVariable($k, 'Process')) {
                [Environment]::SetEnvironmentVariable($k, $v, 'Process')
            }
        }
    }
}
if (-not $AuthOrigin) { $AuthOrigin = if ($env:AUTH_ORIGIN) { $env:AUTH_ORIGIN } else { 'https://auth.micthebick.dev' } }
if (-not $AppOrigin)  { $AppOrigin  = if ($env:APP_ORIGIN)  { $env:APP_ORIGIN }  else { 'https://app.micthebick.dev' } }
if (-not $JavaHome)   { $JavaHome   = $env:JAVA_HOME }

$Root     = Split-Path -Parent $PSScriptRoot
$FrontDir = Join-Path $Root 'frontend'
$IvDir    = Join-Path $Root 'netismaker-interview-service'
$RunDir   = Join-Path $Root '.run'
if (-not $AuthDir) { $AuthDir = Join-Path (Split-Path -Parent $Root) 'netis-auth' }
New-Item -ItemType Directory -Force $RunDir | Out-Null

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

function Test-PidAlive([string]$Name) {
    $pidFile = Join-Path $RunDir "$Name.pid"
    if (-not (Test-Path $pidFile)) { return $false }
    $id = (Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (-not $id) { return $false }
    [bool](Get-Process -Id $id -ErrorAction SilentlyContinue)
}

# Start a command line detached, with extra env vars, stdout+stderr -> .run\<name>.log.
# The child inherits this process' environment, so vars are set then restored.
function Start-Service-Proc([string]$Name, [string]$WorkDir, [string]$CommandLine, [hashtable]$EnvVars) {
    $log = Join-Path $RunDir "$Name.log"
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
    Set-Content -Path (Join-Path $RunDir "$Name.pid") -Value $p.Id -Encoding ascii
    return $p.Id
}

# Run a command line to completion through cmd with all output in a log file.
# (Calling npm directly would turn its stderr lines into terminating errors under
# $ErrorActionPreference = 'Stop' on Windows PowerShell 5.1.)
function Invoke-Logged([string]$WorkDir, [string]$CommandLine, [string]$LogName) {
    $log = Join-Path $RunDir $LogName
    $p = Start-Process -FilePath $env:ComSpec -ArgumentList "/c `"$CommandLine > `"$log`" 2>&1`"" `
        -WorkingDirectory $WorkDir -WindowStyle Hidden -PassThru
    # Not -Wait: that also waits for descendants, and pg_ctl leaves postgres.exe running.
    $null = $p.Handle   # cache the handle so ExitCode is available after exit
    $p.WaitForExit()
    return $p.ExitCode
}

function Wait-Log([string]$Name, [string]$OkPattern, [string]$FailPattern, [int]$TimeoutSec = 180) {
    $log = Join-Path $RunDir "$Name.log"
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $log) {
            if (Select-String -Path $log -Pattern $OkPattern -Quiet)   { return $true }
            if (Select-String -Path $log -Pattern $FailPattern -Quiet) { return $false }
        }
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
$SpringFail = 'APPLICATION FAILED|BUILD FAILED|FAILURE:'
$failed = @()

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

# ---- 1) netis-auth (:9000) --------------------------------------------------
if (Test-PortListening 9000) {
    Write-Host "[skip] auth: port 9000 already listening"
} else {
    if (-not (Test-Path (Join-Path $AuthDir 'gradlew.bat'))) { throw "netis-auth not found at $AuthDir (pass -AuthDir)" }
    Write-Host "[..]   auth starting (:9000)"
    Start-Service-Proc 'auth' $AuthDir "`"$(Join-Path $AuthDir 'gradlew.bat')`" bootRun -q" @{
        JAVA_HOME                       = $JavaHome
        AUTH_ISSUER_URI                 = $AuthOrigin
        SERVER_FORWARD_HEADERS_STRATEGY = 'framework'
        CORS_ALLOWED_ORIGINS            = "$AppOrigin,http://localhost:9000,http://localhost:3001,http://localhost:3000,http://localhost:8080"
    } | Out-Null
    # The API fetches JWKS from auth at startup, so wait for auth first.
    if (Wait-Log 'auth' $SpringOk $SpringFail 240) { Write-Host "[ok]   auth ready" }
    else { Write-Host "[FAIL] auth - see .run\auth.log"; $failed += 'auth' }
}

# ---- 2) netisMaker API (:8090) ----------------------------------------------
$apiStarted = $false
if (Test-PortListening 8090) {
    Write-Host "[skip] api: port 8090 already listening"
} else {
    Write-Host "[..]   api starting (:8090)"
    Start-Service-Proc 'api' $Root "`"$(Join-Path $Root 'gradlew.bat')`" bootRun -q" @{
        JAVA_HOME                                             = $JavaHome
        SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI = 'http://localhost:9000/oauth2/jwks'
        SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI  = $AuthOrigin
        CORS_ALLOWED_ORIGINS                                  = "$AppOrigin,http://localhost:3001,http://localhost:3000"
    } | Out-Null
    $apiStarted = $true
}

# ---- 3) frontend (:3001) - production build ---------------------------------
$frontStarted = $false
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
        Start-Service-Proc 'front' $FrontDir 'node .output\server\index.mjs' @{
            PORT                     = '3001'
            HOST                     = '0.0.0.0'
            NUXT_PUBLIC_AUTH_ISSUER  = $AuthOrigin
            NUXT_PUBLIC_REDIRECT_URI = "$AppOrigin/oauth/callback"
            NUXT_PUBLIC_CLIENT_ID    = 'netis-maker-spa'
        } | Out-Null
        $frontStarted = $true
    }
}

if ($apiStarted) {
    if (Wait-Log 'api' $SpringOk $SpringFail 240) { Write-Host "[ok]   api ready" }
    else { Write-Host "[FAIL] api - see .run\api.log"; $failed += 'api' }
}
if ($frontStarted) {
    if (Wait-Port 3001 90) { Write-Host "[ok]   front ready" }
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

    if (Test-PidAlive 'worker') {
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
        Start-Service-Proc 'worker' $Root "`"$(Join-Path $Root 'gradlew.bat')`" bootRun -q --args=--spring.profiles.active=worker" $workerEnv | Out-Null
        if (Wait-Log 'worker' $SpringOk $SpringFail 240) { Write-Host "[ok]   worker ready" }
        else { Write-Host "[FAIL] worker - see .run\worker.log"; $failed += 'worker' }
    }

    if (Test-PidAlive 'interview') {
        Write-Host "[skip] interview: already running"
    } else {
        $sp = $env:SUPERPOWERS_PLUGIN_PATH
        if (-not $sp) {
            $spRoot = Join-Path $env:USERPROFILE '.claude\plugins\cache\claude-plugins-official\superpowers'
            $spDir = Get-ChildItem $spRoot -Directory -ErrorAction SilentlyContinue |
                Where-Object { Test-Path (Join-Path $_.FullName 'skills\brainstorming') } |
                Sort-Object Name -Descending | Select-Object -First 1
            if ($spDir) { $sp = $spDir.FullName }
        }
        if (-not $sp) {
            Write-Host "[FAIL] interview - superpowers plugin not found (set SUPERPOWERS_PLUGIN_PATH)"; $failed += 'interview'
        } else {
            if (-not (Test-Path (Join-Path $IvDir 'node_modules'))) {
                Invoke-Logged $IvDir 'npm install' 'interview-install.log' | Out-Null
            }
            Write-Host "[..]   interview starting"
            Start-Service-Proc 'interview' $IvDir 'node --import tsx src/index.ts' @{
                API_BASE_URL            = 'http://localhost:8090'
                WORKER_API_KEY          = $workerKey
                WORKER_ID               = 'interview-worker-1'
                CLAUDE_CLI              = $claudeCli
                SUPERPOWERS_PLUGIN_PATH = ($sp -replace '\\', '/')
            } | Out-Null
            if (Wait-Log 'interview' '\[interview-service\].*polling' 'Missing required env|Error:' 60) { Write-Host "[ok]   interview ready" }
            else { Write-Host "[FAIL] interview - see .run\interview.log"; $failed += 'interview' }
        }
    }
}

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
