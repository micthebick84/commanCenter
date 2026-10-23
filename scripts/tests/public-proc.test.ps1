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
$spawned = New-Object System.Collections.ArrayList

# Start a fake service exactly like start-public's Start-Service-Proc:
# cmd wrapper -> ping child. Returns @{ Wrapper = <pid>; App = <pid> }.
function New-Fake([bool]$WritePidFile) {
    $log = Join-Path $RunDir 'fake.log'
    $p = Start-Process -FilePath $env:ComSpec `
        -ArgumentList "/c `"ping -n 120 127.0.0.1 > `"$log`" 2>&1`"" `
        -WindowStyle Hidden -PassThru
    [void]$spawned.Add($p.Id)
    $app = $null
    $deadline = (Get-Date).AddSeconds(10)
    while (-not $app -and (Get-Date) -lt $deadline) {
        $app = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($p.Id)" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -eq 'PING.EXE' } | Select-Object -First 1
        if (-not $app) { Start-Sleep -Milliseconds 200 }
    }
    if (-not $app) { throw "fake service: PING.EXE child did not appear" }
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
    if (Test-Alive $ProcessId) { & taskkill.exe /PID $ProcessId /T /F *> $null }
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
    Assert-True ($appGone -and $wrapGone -and $pidFileGone -and ($out.Count -eq 1) -and ($out[0] -match '^\[stop\] fake \(wrapper \d+, app ')) `
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

    # ---- Select-WorkerJvm --------------------------------------------------
    $root = 'C:\Users\x\commanCenter'
    $t0 = [datetime]'2026-10-23 10:00:00'
    $jvm = 'C:\jdk\bin\java.exe -cp "{0}\build\classes\java\main;{0}\build\resources\main;x.jar" com.netismaker.NetisMakerApplication {1}'
    $procs = @(
        [pscustomobject]@{ ProcessId = 10; Name = 'java.exe'; CommandLine = ($jvm -f $root, '--spring.profiles.active=worker'); CreationDate = $t0.AddMinutes(5) },
        [pscustomobject]@{ ProcessId = 11; Name = 'java.exe'; CommandLine = ($jvm -f $root, '--spring.profiles.active=api'); CreationDate = $t0.AddMinutes(9) },
        [pscustomobject]@{ ProcessId = 12; Name = 'java.exe'; CommandLine = ($jvm -f "$root\.claude\worktrees\wt1", '--spring.profiles.active=worker'); CreationDate = $t0.AddMinutes(8) },
        [pscustomobject]@{ ProcessId = 13; Name = 'java.exe'; CommandLine = ($jvm -f $root, '--spring.profiles.active=worker'); CreationDate = $t0.AddMinutes(-30) }
    )
    $sel = Select-WorkerJvm $procs $root $t0
    Assert-Equal 10 $sel 'Select-WorkerJvm picks main checkout worker only'
    Assert-True ((Select-WorkerJvm $procs $root $t0.AddMinutes(60)) -eq $null) 'Select-WorkerJvm: nothing new enough gives null'
    $two = $procs + @([pscustomobject]@{ ProcessId = 14; Name = 'java.exe'; CommandLine = ($jvm -f $root, '--spring.profiles.active=worker'); CreationDate = $t0.AddMinutes(7) })
    Assert-Equal 14 (Select-WorkerJvm $two $root $t0) 'Select-WorkerJvm: most recent wins'

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
}
catch {
    $script:failures++
    Write-Host "FAIL unexpected error: $($_.Exception.Message) at line $($_.InvocationInfo.ScriptLineNumber)"
}
finally {
    foreach ($id in $spawned) { Stop-Spawned ([int]$id) }
    Get-CimInstance Win32_Process -Filter "Name='PING.EXE'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*ping -n 120 127.0.0.1*' -and ($spawned -contains [int]$_.ParentProcessId) } |
        ForEach-Object { Stop-Spawned ([int]$_.ProcessId) }
    Start-Sleep -Milliseconds 300
    Remove-Item $RunDir -Recurse -Force -ErrorAction SilentlyContinue
}

if ($script:failures -gt 0) { Write-Host "$($script:failures) FAILED"; exit 1 }
Write-Host 'ALL PASS'
exit 0
