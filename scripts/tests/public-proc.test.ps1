# Tests for scripts/public-proc.ps1 (no Pester; plain assertions, exit 1 on failure).
# Run:  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\public-proc.test.ps1
# Hermetic on purpose: uses a temp RunDir, only kills processes it spawned itself,
# never touches the real .run directory or the live stack.
# ASCII only on purpose: Windows PowerShell 5.1 misreads BOM-less UTF-8 scripts.

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot '..\public-proc.ps1')

$script:failures = 0
function Assert-True([bool]$Cond, [string]$Name, [string]$Detail = '') {
    if ($Cond) { Write-Host "PASS $Name" }
    else { $script:failures++; Write-Host "FAIL ${Name}: $Detail" }
}
function Assert-Equal($Expected, $Actual, [string]$Name) {
    if ("$Expected" -eq "$Actual") { Write-Host "PASS $Name" }
    else { $script:failures++; Write-Host "FAIL ${Name}: expected [$Expected] actual [$Actual]" }
}

$RunDir = Join-Path $env:TEMP ("public-proc-test-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $RunDir | Out-Null
# pid -> start ticks recorded at spawn time. Stop-Spawned kills only while the start ticks still match,
# so a pid that Windows reused for an unrelated process (maybe the live stack) is never touched.
$spawned = @{}

# Start a fake service exactly like start-public's Start-Service-Proc:
# cmd wrapper -> ping child. Returns @{ Wrapper = <pid>; App = <pid> }.
function New-Fake([bool]$WritePidFile, [string]$ExtraLog = '') {
    $log = Join-Path $RunDir 'fake.log'
    # $ExtraLog: a second fake cannot share fake.log (file in use), so it redirects elsewhere and only
    # carries the fake.log path in its command line (all Test-WrapperPid looks at).
    $cmdLine = if ($ExtraLog) { "/c `"ping -n 120 127.0.0.1 > `"$ExtraLog`" 2>&1 & rem $log`"" } else { "/c `"ping -n 120 127.0.0.1 > `"$log`" 2>&1`"" }
    $p = Start-Process -FilePath $env:ComSpec `
        -ArgumentList $cmdLine `
        -WindowStyle Hidden -PassThru
    $spawned[[int]$p.Id] = Get-ProcStartTicks ([int]$p.Id)
    $app = $null
    $deadline = (Get-Date).AddSeconds(10)
    while (-not $app -and (Get-Date) -lt $deadline) {
        $app = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($p.Id)" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -eq 'PING.EXE' } | Select-Object -First 1
        if (-not $app) { Start-Sleep -Milliseconds 200 }
    }
    if (-not $app) { throw "fake service: PING.EXE child did not appear" }
    $spawned[[int]$app.ProcessId] = Get-ProcStartTicks ([int]$app.ProcessId)
    if ($WritePidFile) { Set-Content -Path (Join-Path $RunDir 'fake.pid') -Value $p.Id -Encoding ascii }
    return @{ Wrapper = [int]$p.Id; App = [int]$app.ProcessId }
}

function Test-Alive([int]$ProcessId) { [bool](Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) }

function Wait-Gone([int]$ProcessId, [int]$TimeoutSec = 8) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (-not (Test-Alive $ProcessId)) { return $true }
        Start-Sleep -Milliseconds 200
    }
    return $false
}

function Clear-Run {
    Remove-Item (Join-Path $RunDir 'fake.pid') -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $RunDir 'fake.json') -Force -ErrorAction SilentlyContinue
}

# Rewrite fake.json with appStart shifted by one tick (simulates pid reuse).
function Set-AppStartOffByOne {
    $rec = Read-ProcRecord $RunDir 'fake'
    $rec.appStart = ([long]$rec.appStart) + 1
    Set-Content -Path (Join-Path $RunDir 'fake.json') -Value ($rec | ConvertTo-Json) -Encoding ascii
}

function Stop-Spawned([int]$ProcessId) {
    $recorded = $spawned[$ProcessId]
    if ($null -eq $recorded) { return }
    $now = Get-ProcStartTicks $ProcessId
    if (($null -ne $now) -and ($now -eq $recorded)) { & taskkill.exe /PID $ProcessId /T /F *> $null }
}

try {
    # ---- Import-PublicEnv -------------------------------------------------
    $envFile = Join-Path $RunDir 'public.env'
    Set-Content -Path $envFile -Encoding ascii -Value @(
        '# comment',
        'PPT_TEST_A = "from-file"',
        "PPT_TEST_B='from-file-b'",
        'PPT_TEST_C=plain'
    )
    $env:PPT_TEST_B = 'from-env'
    Import-PublicEnv $envFile
    Assert-Equal 'from-file' $env:PPT_TEST_A 'env file: loads KEY=VALUE and trims quotes'
    Assert-Equal 'from-env' $env:PPT_TEST_B 'env file: existing environment variable wins'
    Assert-Equal 'plain' $env:PPT_TEST_C 'env file: plain value'
    Import-PublicEnv (Join-Path $RunDir 'missing.env')
    Assert-True $true 'env file: missing file is not an error'
    $env:PPT_TEST_A = $null; $env:PPT_TEST_B = $null; $env:PPT_TEST_C = $null

    # ---- record roundtrip -------------------------------------------------
    $f = New-Fake $true
    Write-ProcRecord $RunDir 'fake' $f.Wrapper $f.App
    $rec = Read-ProcRecord $RunDir 'fake'
    $ok = ($rec -ne $null) -and $rec.name -eq 'fake' -and $rec.wrapperPid -eq $f.Wrapper -and $rec.appPid -eq $f.App `
        -and ([long]$rec.wrapperStart) -eq (Get-ProcStartTicks $f.Wrapper) `
        -and ([long]$rec.appStart) -eq (Get-ProcStartTicks $f.App) -and $rec.appImage -eq 'PING.EXE'
    Assert-True $ok 'record roundtrip' ($rec | ConvertTo-Json -Compress)
    Write-ProcRecord $RunDir 'nulls' $f.Wrapper $null
    $rn = Read-ProcRecord $RunDir 'nulls'
    Assert-True (($rn -ne $null) -and $rn.appPid -eq $null -and $rn.appStart -eq $null -and $rn.appImage -eq $null -and $rn.wrapperPid -eq $f.Wrapper) 'record roundtrip: null app pid gives null app fields'
    Assert-True ((Read-ProcRecord $RunDir 'nonexistent') -eq $null) 'record: missing file reads as null'
    Assert-True ((Get-ProcStartTicks 2147483000) -eq $null) 'start ticks: unknown pid is null'

    # ---- alive / not alive ------------------------------------------------
    Assert-True (Test-ServiceAlive $RunDir 'fake') 'alive with matching start'
    Set-AppStartOffByOne
    Assert-True (-not (Test-ServiceAlive $RunDir 'fake')) 'not alive with mismatched appStart'
    Remove-Item (Join-Path $RunDir 'fake.json') -Force
    Assert-True (Test-ServiceAlive $RunDir 'fake') 'legacy pid file: alive when wrapper matches'
    Assert-True (Test-WrapperPid $RunDir 'fake' $f.Wrapper) 'wrapper pid: cmd with the log path matches'
    Assert-True (-not (Test-WrapperPid $RunDir 'fake' $f.App)) 'wrapper pid: non-cmd process does not match'
    Assert-True (-not (Test-WrapperPid $RunDir 'other' $f.Wrapper)) 'wrapper pid: different service name does not match'
    Stop-Spawned $f.Wrapper
    Clear-Run
    Remove-Item (Join-Path $RunDir 'nulls.json') -Force -ErrorAction SilentlyContinue

    # ---- stale json (wrapperPid differs from the pid file) is ignored -------
    # (1) json of fake A (app alive) + pid file of fake B (live cmd wrapper): follows the wrapper check.
    $a = New-Fake $false
    Write-ProcRecord $RunDir 'fake' $a.Wrapper $a.App
    $b = New-Fake $true (Join-Path $RunDir 'fake-b.out')
    Assert-True (Test-ServiceAlive $RunDir 'fake') 'stale json: pid file wrapper B alive -> alive'
    Stop-Spawned $b.Wrapper
    [void](Wait-Gone $b.Wrapper)
    Assert-True ((Test-Alive $a.App) -and (-not (Test-ServiceAlive $RunDir 'fake'))) 'stale json: live app of A does not count when wrapper B is dead'
    Stop-Spawned $a.Wrapper
    Clear-Run
    # json with a live app but no pid file: not alive (json does not belong to any pid file)
    $a = New-Fake $false
    Write-ProcRecord $RunDir 'fake' $a.Wrapper $a.App
    Assert-True (-not (Test-ServiceAlive $RunDir 'fake')) 'stale json: json alone without pid file is not alive'
    Stop-Spawned $a.Wrapper
    Clear-Run
    # (2) json with a mismatched app + pid file of a live matching wrapper B (other pid): legacy path -> alive.
    $a = New-Fake $false
    Write-ProcRecord $RunDir 'fake' $a.Wrapper $a.App
    Set-AppStartOffByOne
    $b = New-Fake $true (Join-Path $RunDir 'fake-b.out')
    Assert-True (Test-ServiceAlive $RunDir 'fake') 'stale json: mismatched app but pid file wrapper B matches -> alive'
    Stop-Spawned $a.Wrapper
    Stop-Spawned $b.Wrapper
    Clear-Run

    # ---- stop kills app tree and wrapper, removes files --------------------
    $f = New-Fake $true
    Write-ProcRecord $RunDir 'fake' $f.Wrapper $f.App
    $out = @(Stop-ServiceRecord $RunDir 'fake')
    $appGone = Wait-Gone $f.App
    $wrapGone = Wait-Gone $f.Wrapper
    $filesGone = (-not (Test-Path (Join-Path $RunDir 'fake.pid'))) -and (-not (Test-Path (Join-Path $RunDir 'fake.json')))
    Assert-True ($appGone -and $wrapGone -and $filesGone -and ($out.Count -eq 1) -and ($out[0] -match '^\[stop\] fake \(wrapper \d+, app \d+\)$')) `
        'stop kills app tree and wrapper, removes files' "app=$appGone wrapper=$wrapGone files=$filesGone out=$($out -join '|')"
    Assert-True (Test-Alive $PID) 'stop: test process itself still alive (1)'
    Stop-Spawned $f.Wrapper; Clear-Run

    # ---- stop does not kill when appStart mismatches -----------------------
    # json only (no pid file): step 1 must refuse the mismatching app, step 2 has no wrapper to check.
    $f = New-Fake $false
    Write-ProcRecord $RunDir 'fake' $f.Wrapper $f.App
    Set-AppStartOffByOne
    $out = @(Stop-ServiceRecord $RunDir 'fake')
    Start-Sleep -Milliseconds 500
    $appAlive = Test-Alive $f.App
    $wrapAlive = Test-Alive $f.Wrapper
    $jsonGone = -not (Test-Path (Join-Path $RunDir 'fake.json'))
    Assert-True ($appAlive -and $wrapAlive -and $jsonGone -and ($out.Count -eq 1) -and ($out[0] -eq '[gone] fake')) `
        'stop does not kill when appStart mismatches' "appAlive=$appAlive wrapAlive=$wrapAlive jsonGone=$jsonGone out=$($out -join '|')"
    Assert-True (Test-Alive $PID) 'stop: test process itself still alive (2)'
    Stop-Spawned $f.Wrapper; Stop-Spawned $f.App; Clear-Run

    # ---- legacy pid file: stops matching cmd wrapper -----------------------
    $f = New-Fake $true
    $out = @(Stop-ServiceRecord $RunDir 'fake')
    $appGone = Wait-Gone $f.App
    $wrapGone = Wait-Gone $f.Wrapper
    $pidFileGone = -not (Test-Path (Join-Path $RunDir 'fake.pid'))
    Assert-True ($appGone -and $wrapGone -and $pidFileGone -and ($out.Count -eq 1) -and ($out[0] -match '^\[stop\] fake \(wrapper \d+\)$')) `
        'legacy pid file: stops matching cmd wrapper' "app=$appGone wrapper=$wrapGone pidFile=$pidFileGone out=$($out -join '|')"
    Assert-True (Test-Alive $PID) 'stop: test process itself still alive (3)'
    Stop-Spawned $f.Wrapper; Clear-Run

    # ---- legacy pid file: ignores unrelated pid ----------------------------
    Set-Content -Path (Join-Path $RunDir 'fake.pid') -Value $PID -Encoding ascii
    $out = @(Stop-ServiceRecord $RunDir 'fake')
    Assert-True (($out.Count -eq 1) -and ($out[0] -eq '[gone] fake') -and (Test-Alive $PID) -and (-not (Test-Path (Join-Path $RunDir 'fake.pid')))) `
        'legacy pid file: ignores unrelated pid' "out=$($out -join '|')"
    Clear-Run
    $out = @(Stop-ServiceRecord $RunDir 'fake')
    Assert-True (($out.Count -eq 1) -and ($out[0] -eq '[gone] fake')) 'stop: no files at all is [gone]'

    # ---- Stop-ProcTree never throws under ErrorActionPreference=Stop -------
    # taskkill on a pid that does not exist writes to stderr; that used to throw.
    $threw = $false; $res = $null
    try { $res = Invoke-TaskKill 2147483000 } catch { $threw = $true }
    Assert-True (-not $threw) 'taskkill on a nonexistent pid does not throw under Stop'
    $threw = $false
    try { $res = Stop-ProcTree 2147483000 } catch { $threw = $true }
    Assert-True ((-not $threw) -and ($res -eq $true)) 'Stop-ProcTree: already-gone target is true, no throw'
    Assert-True ((Stop-ProcTree $PID) -eq $false) 'Stop-ProcTree: refuses its own pid'
    Assert-True (Test-Alive $PID) 'stop: test process itself still alive (4)'

    # ---- stop when the wrapper already exited before step 2 ----------------
    # Kill only the wrapper (no /T), so the app survives as an orphan with a matching record.
    $f = New-Fake $true
    Write-ProcRecord $RunDir 'fake' $f.Wrapper $f.App
    Stop-Process -Id $f.Wrapper -Force
    [void](Wait-Gone $f.Wrapper)
    $threw = $false; $out = @()
    try { $out = @(Stop-ServiceRecord $RunDir 'fake') } catch { $threw = $true }
    $appGone = Wait-Gone $f.App
    $filesGone = (-not (Test-Path (Join-Path $RunDir 'fake.pid'))) -and (-not (Test-Path (Join-Path $RunDir 'fake.json')))
    Assert-True ((-not $threw) -and $appGone -and $filesGone -and ($out.Count -eq 1) -and ($out[0] -match '^\[stop\] fake \(app \d+\)$')) `
        'stop with wrapper already exited: kills app, no throw, files removed' "threw=$threw app=$appGone files=$filesGone out=$($out -join '|')"
    Assert-True (Test-Alive $PID) 'stop: test process itself still alive (5)'
    Stop-Spawned $f.Wrapper; Stop-Spawned $f.App; Clear-Run

    # ---- failing kill: [fail], keep pid file and json ----------------------
    # Replace the taskkill wrapper with a no-op so the target survives, then restore the real one.
    $f = New-Fake $true
    Write-ProcRecord $RunDir 'fake' $f.Wrapper $f.App
    function Invoke-TaskKill([int]$ProcessId) { }
    $out = @(Stop-ServiceRecord $RunDir 'fake')
    $filesKept = (Test-Path (Join-Path $RunDir 'fake.pid')) -and (Test-Path (Join-Path $RunDir 'fake.json'))
    $stillAlive = (Test-Alive $f.App) -and (Test-Alive $f.Wrapper)
    Assert-True (($out.Count -eq 1) -and ($out[0] -match '^\[fail\] fake \(wrapper \d+, app \d+ still running') -and $filesKept -and $stillAlive) `
        'failed kill: [fail] line, record kept, process untouched' "out=$($out -join '|') kept=$filesKept alive=$stillAlive"
    Remove-Item Function:\Invoke-TaskKill
    . (Join-Path $PSScriptRoot '..\public-proc.ps1')
    $out = @(Stop-ServiceRecord $RunDir 'fake')
    $filesGone = (-not (Test-Path (Join-Path $RunDir 'fake.pid'))) -and (-not (Test-Path (Join-Path $RunDir 'fake.json')))
    Assert-True (($out.Count -eq 1) -and ($out[0] -match '^\[stop\] fake ') -and $filesGone -and (Wait-Gone $f.App) -and (Wait-Gone $f.Wrapper)) `
        'failed kill: rerun with the real taskkill stops it and removes the record' "out=$($out -join '|') files=$filesGone"
    Assert-True (Test-Alive $PID) 'stop: test process itself still alive (6)'
    Stop-Spawned $f.Wrapper; Stop-Spawned $f.App; Clear-Run

    # ---- bad json value: alive falls back to the pid file, stop still does the wrapper step ----
    $f = New-Fake $true
    $bad = @{ name = 'fake'; wrapperPid = $f.Wrapper; wrapperStart = 1; appPid = 'abc'; appStart = 1; appImage = 'PING.EXE' }
    Set-Content -Path (Join-Path $RunDir 'fake.json') -Value ($bad | ConvertTo-Json) -Encoding ascii
    Assert-True (Test-ServiceAlive $RunDir 'fake') 'bad json value: alive falls back to the pid file wrapper'
    $out = @(Stop-ServiceRecord $RunDir 'fake')
    $wrapGone = Wait-Gone $f.Wrapper
    $filesGone = (-not (Test-Path (Join-Path $RunDir 'fake.pid'))) -and (-not (Test-Path (Join-Path $RunDir 'fake.json')))
    Assert-True ($wrapGone -and $filesGone -and ($out.Count -eq 1) -and ($out[0] -match '^\[stop\] fake \(wrapper \d+\)$')) `
        'bad json value: stop treats app as unmatched but still stops the wrapper' "wrapper=$wrapGone files=$filesGone out=$($out -join '|')"
    Assert-True (Test-Alive $PID) 'stop: test process itself still alive (7)'
    Stop-Spawned $f.Wrapper; Stop-Spawned $f.App; Clear-Run

    # ---- Select-WorkerJvm --------------------------------------------------
    $jarDir = 'C:\Users\x\netis-maker\run\jars'
    $jar = "$jarDir\netis-maker-1a2b3c4.jar"
    $oldJar = "$jarDir\netis-maker-0f0f0f0.jar"
    $t0 = [datetime]'2026-10-23 10:00:00'
    $jvm = 'C:\jdk\bin\java.exe -Dfile.encoding=UTF-8 -jar "{0}" {1}'
    $procs = @(
        [pscustomobject]@{ ProcessId = 10; ParentProcessId = 500; Name = 'java.exe'; CommandLine = ($jvm -f $jar, '--spring.profiles.active=worker'); CreationDate = $t0.AddMinutes(5) },
        [pscustomobject]@{ ProcessId = 11; ParentProcessId = 500; Name = 'java.exe'; CommandLine = ($jvm -f $jar, ''); CreationDate = $t0.AddMinutes(9) },
        [pscustomobject]@{ ProcessId = 12; ParentProcessId = 777; Name = 'java.exe'; CommandLine = ($jvm -f $jar, '--spring.profiles.active=worker'); CreationDate = $t0.AddMinutes(8) },
        [pscustomobject]@{ ProcessId = 13; ParentProcessId = 500; Name = 'java.exe'; CommandLine = ($jvm -f $oldJar, '--spring.profiles.active=worker'); CreationDate = $t0.AddMinutes(9) },
        [pscustomobject]@{ ProcessId = 15; ParentProcessId = 500; Name = 'cmd.exe'; CommandLine = ($jvm -f $jar, '--spring.profiles.active=worker'); CreationDate = $t0.AddMinutes(9) }
    )
    Assert-Equal 10 (Select-WorkerJvm $procs 500 $jar) 'Select-WorkerJvm: wrapper child running this jar with the worker profile'
    Assert-True ((Select-WorkerJvm $procs 501 $jar) -eq $null) 'Select-WorkerJvm: other wrapper gives null'
    Assert-True ((Select-WorkerJvm $procs 500 '') -eq $null) 'Select-WorkerJvm: no jar path gives null'
    $two = $procs + @([pscustomobject]@{ ProcessId = 14; ParentProcessId = 500; Name = 'java.exe'; CommandLine = ($jvm -f $jar, '--spring.profiles.active=worker'); CreationDate = $t0.AddMinutes(7) })
    Assert-Equal 14 (Select-WorkerJvm $two 500 $jar) 'Select-WorkerJvm: most recent wins'

    # ---- Get-JarName ---------------------------------------------------------
    $now = [datetime]'2026-11-28 15:30:05'
    Assert-Equal 'netis-maker-1a2b3c4.jar' (Get-JarName 'netis-maker' '1a2b3c4d5e6f' '' $now) 'Get-JarName: clean tree uses sha7'
    Assert-Equal 'netis-auth-1a2b3c4-dirty-20261128153005.jar' (Get-JarName 'netis-auth' '1a2b3c4d5e6f' 'dirty' $now) 'Get-JarName: dirty tree adds a timestamp'
    Assert-Equal 'netis-maker-1a2b3c4-rebuild-20261128153005.jar' (Get-JarName 'netis-maker' '1a2b3c4d5e6f' 'rebuild' $now) 'Get-JarName: forced rebuild gets its own name'
    Assert-Equal 'netis-maker-abc.jar' (Get-JarName 'netis-maker' 'abc' '' $now) 'Get-JarName: short sha kept as is'

    # ---- Select-BootJar --------------------------------------------------------
    $b0 = [datetime]'2026-11-28 15:00:00'
    $libs = @(
        [pscustomobject]@{ Name = 'netis-maker-0.1.0-SNAPSHOT.jar'; FullName = 'C:\r\build\libs\netis-maker-0.1.0-SNAPSHOT.jar'; LastWriteTime = $b0.AddSeconds(30) },
        [pscustomobject]@{ Name = 'netis-maker-0.1.0-SNAPSHOT-plain.jar'; FullName = 'C:\r\build\libs\netis-maker-0.1.0-SNAPSHOT-plain.jar'; LastWriteTime = $b0.AddSeconds(31) },
        [pscustomobject]@{ Name = 'netis-maker-0.0.9.jar'; FullName = 'C:\r\build\libs\netis-maker-0.0.9.jar'; LastWriteTime = $b0.AddDays(-3) },
        [pscustomobject]@{ Name = 'notes.txt'; FullName = 'C:\r\build\libs\notes.txt'; LastWriteTime = $b0.AddSeconds(40) }
    )
    Assert-Equal 'C:\r\build\libs\netis-maker-0.1.0-SNAPSHOT.jar' (Select-BootJar $libs $b0) 'Select-BootJar: the boot jar of this build (plain and old jars ignored)'
    Assert-True ((Select-BootJar $libs $b0.AddMinutes(5)) -eq $null) 'Select-BootJar: nothing written by this build gives null'
    $twoLibs = $libs + @([pscustomobject]@{ Name = 'other-1.0.jar'; FullName = 'C:\r\build\libs\other-1.0.jar'; LastWriteTime = $b0.AddSeconds(35) })
    Assert-True ((Select-BootJar $twoLibs $b0) -eq $null) 'Select-BootJar: two candidates give null'

    # ---- Select-JarsToPrune / Select-JarsInUse ---------------------------------
    $files = @()
    for ($i = 0; $i -lt 8; $i++) {
        $files += [pscustomobject]@{ Name = "netis-maker-00000$i.jar"; FullName = "$jarDir\netis-maker-00000$i.jar"; LastWriteTime = $b0.AddHours($i) }
    }
    $files += [pscustomobject]@{ Name = 'netis-auth-aaaaaaa.jar'; FullName = "$jarDir\netis-auth-aaaaaaa.jar"; LastWriteTime = $b0.AddDays(-30) }
    $prune = @(Select-JarsToPrune $files 'netis-maker' 5 @("$jarDir\NETIS-MAKER-000001.jar"))
    Assert-Equal 2 $prune.Count 'Select-JarsToPrune: keeps 5 newest and the one in use'
    Assert-True (($prune -contains "$jarDir\netis-maker-000000.jar") -and ($prune -contains "$jarDir\netis-maker-000002.jar")) 'Select-JarsToPrune: oldest not-in-use ones go' ($prune -join ',')
    Assert-Equal 0 (@(Select-JarsToPrune $files 'netis-auth' 5 @())).Count 'Select-JarsToPrune: other prefix untouched / under the limit'
    $running = @(
        [pscustomobject]@{ Name = 'java.exe'; CommandLine = "C:\jdk\bin\java.exe -Dx=1 -jar `"$jarDir\netis-maker-000001.jar`" --spring.profiles.active=worker" },
        [pscustomobject]@{ Name = 'java.exe'; CommandLine = 'C:\jdk\bin\java.exe -cp C:\elsewhere\lib\a.jar;C:\elsewhere\lib\b.jar Main' },
        [pscustomobject]@{ Name = 'node.exe'; CommandLine = "node $jarDir\netis-maker-000002.jar" }
    )
    $inUse = @(Select-JarsInUse $running $jarDir)
    Assert-Equal 1 $inUse.Count 'Select-JarsInUse: only java.exe jars inside the run folder'
    Assert-Equal "$jarDir\netis-maker-000001.jar" $inUse[0] 'Select-JarsInUse: quoted path read without quotes'

    # ---- Get-ExeName -----------------------------------------------------------
    Assert-Equal 'netis-worker-rs-1a2b3c4.exe' (Get-ExeName 'netis-worker-rs' '1a2b3c4d5e6f' '' $now) 'Get-ExeName: clean tree uses sha7'
    Assert-Equal 'netis-worker-rs-1a2b3c4-dirty-20261128153005.exe' (Get-ExeName 'netis-worker-rs' '1a2b3c4d5e6f' 'dirty' $now) 'Get-ExeName: tagged name adds a timestamp'
    Assert-Equal 'netis-worker-rs-abc.exe' (Get-ExeName 'netis-worker-rs' 'abc' '' $now) 'Get-ExeName: short sha kept as is'

    # ---- Select-NativeApp ------------------------------------------------------
    $binDir = 'C:\Users\x\netis-maker\run\bin'
    $exe = "$binDir\netis-worker-rs-1a2b3c4.exe"
    $oldExe = "$binDir\netis-worker-rs-0f0f0f0.exe"
    $nprocs = @(
        [pscustomobject]@{ ProcessId = 20; ParentProcessId = 500; Name = 'netis-worker-rs-1a2b3c4.exe'; ExecutablePath = $exe; CommandLine = "`"$exe`""; CreationDate = $t0.AddMinutes(5) },
        [pscustomobject]@{ ProcessId = 21; ParentProcessId = 500; Name = 'conhost.exe'; ExecutablePath = 'C:\Windows\System32\conhost.exe'; CommandLine = '\??\C:\Windows\system32\conhost.exe 0x4'; CreationDate = $t0.AddMinutes(9) },
        [pscustomobject]@{ ProcessId = 22; ParentProcessId = 777; Name = 'netis-worker-rs-1a2b3c4.exe'; ExecutablePath = $exe; CommandLine = "`"$exe`""; CreationDate = $t0.AddMinutes(8) },
        [pscustomobject]@{ ProcessId = 23; ParentProcessId = 500; Name = 'netis-worker-rs-0f0f0f0.exe'; ExecutablePath = $oldExe; CommandLine = "`"$oldExe`""; CreationDate = $t0.AddMinutes(9) }
    )
    Assert-Equal 20 (Select-NativeApp $nprocs 500 $exe) 'Select-NativeApp: wrapper child running this exe (conhost, other copy, other parent ignored)'
    Assert-Equal 20 (Select-NativeApp $nprocs 500 $exe.ToUpper()) 'Select-NativeApp: path case differences are fine'
    Assert-True ((Select-NativeApp $nprocs 501 $exe) -eq $null) 'Select-NativeApp: other wrapper gives null'
    Assert-True ((Select-NativeApp $nprocs 500 '') -eq $null) 'Select-NativeApp: no exe path gives null'
    Assert-True ((Select-NativeApp @($nprocs[1]) 500 $exe) -eq $null) 'Select-NativeApp: only a conhost child gives null'
    $ntwo = $nprocs + @([pscustomobject]@{ ProcessId = 24; ParentProcessId = 500; Name = 'netis-worker-rs-1a2b3c4.exe'; ExecutablePath = $exe; CommandLine = "`"$exe`""; CreationDate = $t0.AddMinutes(7) })
    Assert-Equal 24 (Select-NativeApp $ntwo 500 $exe) 'Select-NativeApp: most recent wins'
    $noPath = @([pscustomobject]@{ ProcessId = 25; ParentProcessId = 500; Name = 'netis-worker-rs-1a2b3c4.exe'; ExecutablePath = $null; CommandLine = "`"$exe`""; CreationDate = $t0 })
    Assert-Equal 25 (Select-NativeApp $noPath 500 $exe) 'Select-NativeApp: falls back to the command line when the image path is unreadable'
    Assert-True ((Select-NativeApp @() 500 $exe) -eq $null) 'Select-NativeApp: no processes gives null'

    # ---- Select-FilesToPrune ---------------------------------------------------
    $efiles = @()
    for ($i = 0; $i -lt 8; $i++) {
        $efiles += [pscustomobject]@{ Name = "netis-worker-rs-00000$i.exe"; FullName = "$binDir\netis-worker-rs-00000$i.exe"; LastWriteTime = $b0.AddHours($i) }
    }
    $efiles += [pscustomobject]@{ Name = 'netis-worker-rs-0000000.exe.part'; FullName = "$binDir\netis-worker-rs-0000000.exe.part"; LastWriteTime = $b0.AddDays(-30) }
    $efiles += [pscustomobject]@{ Name = 'other-tool-aaaaaaa.exe'; FullName = "$binDir\other-tool-aaaaaaa.exe"; LastWriteTime = $b0.AddDays(-30) }
    $eprune = @(Select-FilesToPrune $efiles 'netis-worker-rs' '.exe' 5 @("$binDir\NETIS-WORKER-RS-000001.exe"))
    Assert-Equal 2 $eprune.Count 'Select-FilesToPrune: keeps 5 newest exes and the one in use'
    Assert-True (($eprune -contains "$binDir\netis-worker-rs-000000.exe") -and ($eprune -contains "$binDir\netis-worker-rs-000002.exe")) 'Select-FilesToPrune: oldest not-in-use exes go' ($eprune -join ',')
    Assert-True (-not ($eprune -like '*.part') -and -not ($eprune -like '*other-tool*')) 'Select-FilesToPrune: .part files and other prefixes are left alone'
    Assert-Equal 0 (@(Select-FilesToPrune $efiles 'netis-worker-rs' '.jar' 5 @())).Count 'Select-FilesToPrune: extension must match'
    Assert-Equal 2 (@(Select-FilesToPrune $files 'netis-maker' '.jar' 5 @("$jarDir\NETIS-MAKER-000001.jar"))).Count 'Select-FilesToPrune: the jar case still works'

    # ---- Select-ExesInUse ------------------------------------------------------
    $erun = @(
        [pscustomobject]@{ Name = 'netis-worker-rs-000001.exe'; ExecutablePath = "$binDir\netis-worker-rs-000001.exe" },
        [pscustomobject]@{ Name = 'netis-worker-rs-000002.exe'; ExecutablePath = "$binDir\sub\netis-worker-rs-000002.exe" },
        [pscustomobject]@{ Name = 'node.exe'; ExecutablePath = 'C:\elsewhere\node.exe' },
        [pscustomobject]@{ Name = 'System'; ExecutablePath = $null }
    )
    $einUse = @(Select-ExesInUse $erun $binDir)
    Assert-Equal 1 $einUse.Count 'Select-ExesInUse: only images directly inside the bin folder'
    Assert-Equal "$binDir\netis-worker-rs-000001.exe" $einUse[0] 'Select-ExesInUse: returns the image path'
    Assert-Equal 1 (@(Select-ExesInUse $erun ($binDir + '\'))).Count 'Select-ExesInUse: trailing backslash on the folder is fine'

    # ---- Select-OrphanClaude -----------------------------------------------
    $claude = 'C:\Users\x\.local\bin\claude.exe'
    $all = @(
        [pscustomobject]@{ ProcessId = 100; ParentProcessId = 4;   Name = 'explorer.exe'; ExecutablePath = 'C:\Windows\explorer.exe'; CreationDate = $t0 },
        [pscustomobject]@{ ProcessId = 101; ParentProcessId = 100; Name = 'late-parent.exe'; ExecutablePath = 'C:\x\late.exe'; CreationDate = $t0.AddMinutes(30) },
        [pscustomobject]@{ ProcessId = 200; ParentProcessId = 999; Name = 'claude.exe'; ExecutablePath = $claude.ToUpper(); CreationDate = $t0.AddMinutes(10) },
        [pscustomobject]@{ ProcessId = 201; ParentProcessId = 101; Name = 'claude.exe'; ExecutablePath = $claude; CreationDate = $t0.AddMinutes(10) },
        [pscustomobject]@{ ProcessId = 202; ParentProcessId = 100; Name = 'claude.exe'; ExecutablePath = $claude; CreationDate = $t0.AddMinutes(10) },
        [pscustomobject]@{ ProcessId = 203; ParentProcessId = 999; Name = 'claude.exe'; ExecutablePath = 'C:\Users\x\AppData\Local\Claude\claude.exe'; CreationDate = $t0.AddMinutes(10) }
    )
    $ids = @(Select-OrphanClaude $all $claude | ForEach-Object { $_.ProcessId })
    Assert-True ($ids -contains 200) 'Select-OrphanClaude: no parent -> included' ($ids -join ',')
    Assert-True ($ids -contains 201) 'Select-OrphanClaude: parent started later -> included' ($ids -join ',')
    Assert-True (-not ($ids -contains 202)) 'Select-OrphanClaude: live parent -> excluded' ($ids -join ',')
    Assert-True (-not ($ids -contains 203)) 'Select-OrphanClaude: other executable path -> excluded' ($ids -join ',')
    Assert-Equal 2 $ids.Count 'Select-OrphanClaude: exactly the two orphans'

    # ---- Save-PreviousLog --------------------------------------------------
    $logs = Join-Path $RunDir 'logs'
    Assert-True ((Save-PreviousLog $RunDir 'svc.log') -eq $null) 'Save-PreviousLog: missing log -> null'
    Set-Content -Path (Join-Path $RunDir 'svc.log') -Value '' -NoNewline -Encoding ascii
    Assert-True (((Save-PreviousLog $RunDir 'svc.log') -eq $null) -and (Test-Path (Join-Path $RunDir 'svc.log'))) 'Save-PreviousLog: empty log stays, nothing archived'
    Set-Content -Path (Join-Path $RunDir 'svc.log') -Value 'Started FakeApplication' -Encoding ascii
    (Get-Item (Join-Path $RunDir 'svc.log')).LastWriteTime = [datetime]'2026-10-24 09:15:30.123'
    $moved = Save-PreviousLog $RunDir 'svc.log'
    Assert-True (($moved -eq (Join-Path $logs 'svc-20261024-091530-123.log')) -and (-not (Test-Path (Join-Path $RunDir 'svc.log'))) `
        -and ((Get-Content $moved -Raw) -match 'Started FakeApplication')) 'Save-PreviousLog: moves the log, named by its last write time' "$moved"
    # keep only the newest N of the same name; other names (svc-extra) are never pruned
    Set-Content -Path (Join-Path $logs 'svc-extra-20200101-000000-000.log') -Value 'x' -Encoding ascii
    foreach ($i in 1..4) {
        Set-Content -Path (Join-Path $RunDir 'svc.log') -Value "run $i" -Encoding ascii
        (Get-Item (Join-Path $RunDir 'svc.log')).LastWriteTime = ([datetime]'2026-10-25 00:00:00').AddMinutes($i)
        $null = Save-PreviousLog $RunDir 'svc.log' 3
    }
    $kept = @(Get-ChildItem $logs -Filter 'svc-2*.log' | Sort-Object Name | ForEach-Object { $_.Name })
    Assert-True (($kept.Count -eq 3) -and ($kept[0] -eq 'svc-20261025-000200-000.log') -and ($kept[2] -eq 'svc-20261025-000400-000.log')) `
        'Save-PreviousLog: keeps the newest N archives of that log' ($kept -join ',')
    Assert-True (Test-Path (Join-Path $logs 'svc-extra-20200101-000000-000.log')) 'Save-PreviousLog: another log name is not pruned'
    # a log held open for writing cannot be moved -> null, file untouched
    Set-Content -Path (Join-Path $RunDir 'busy.log') -Value 'in use' -Encoding ascii
    $fs = [IO.File]::Open((Join-Path $RunDir 'busy.log'), 'Open', 'ReadWrite', 'None')
    try {
        Assert-True (((Save-PreviousLog $RunDir 'busy.log') -eq $null) -and (Test-Path (Join-Path $RunDir 'busy.log'))) 'Save-PreviousLog: log in use -> null, left in place'
    } finally { $fs.Dispose() }
}
catch {
    $script:failures++
    Write-Host "FAIL unexpected error: $($_.Exception.Message) at line $($_.InvocationInfo.ScriptLineNumber)"
}
finally {
    foreach ($id in @($spawned.Keys)) { Stop-Spawned ([int]$id) }
    Get-CimInstance Win32_Process -Filter "Name='PING.EXE'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*ping -n 120 127.0.0.1*' -and $spawned.ContainsKey([int]$_.ParentProcessId) } |
        ForEach-Object { Stop-Spawned ([int]$_.ProcessId) }
    Start-Sleep -Milliseconds 300
    Remove-Item $RunDir -Recurse -Force -ErrorAction SilentlyContinue
}

if ($script:failures -gt 0) { Write-Host "$($script:failures) FAILED"; exit 1 }
Write-Host 'ALL PASS'
exit 0
