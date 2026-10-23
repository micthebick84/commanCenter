# Shared helpers for scripts/start-public.ps1 and scripts/stop-public.ps1 (dot-source this file).
# Records the REAL process of each service (.run\<name>.json next to the cmd wrapper's .pid),
# checks liveness by pid + start time, and stops by process tree.
# Every function answers $null / $false instead of throwing. Spec: docs/superpowers/specs/
# 2026-10-23-graceful-shutdown-minimal-design.md section 5.
# Windows PowerShell 5.1 only. ASCII only on purpose: 5.1 misreads BOM-less UTF-8 scripts.

# Process-start timestamps as FILETIME ticks (long), or $null when the process is gone / not readable.
function Get-ProcStartTicks([int]$ProcessId) {
    try {
        $p = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
        if (-not $p) { return $null }
        return [long]$p.StartTime.ToFileTimeUtc()
    } catch {
        return $null
    }
}

# KEY=VALUE settings file ('#' comments). Variables already set in the environment win over the file.
function Import-PublicEnv([string]$EnvFile) {
    if (-not $EnvFile -or -not (Test-Path $EnvFile)) { return }
    foreach ($line in Get-Content $EnvFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
            $k = $Matches[1]; $v = $Matches[2].Trim('"').Trim("'")
            if (-not [Environment]::GetEnvironmentVariable($k, 'Process')) {
                [Environment]::SetEnvironmentVariable($k, $v, 'Process')
            }
        }
    }
}

# <RunDir>\<Name>.json: wrapper (cmd) pid/start plus the app process pid/start/image.
# $AppPid $null (or the process is already gone) writes the three app fields as null.
function Write-ProcRecord([string]$RunDir, [string]$Name, [int]$WrapperPid, $AppPid) {
    try {
        $appId = $null; $appStart = $null; $appImage = $null
        if ($null -ne $AppPid) {
            $appStart = Get-ProcStartTicks ([int]$AppPid)
            if ($null -ne $appStart) {
                $appId = [int]$AppPid
                $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$appId" -ErrorAction SilentlyContinue
                if ($cim) { $appImage = [string]$cim.Name }
            }
        }
        $rec = [ordered]@{
            name         = $Name
            wrapperPid   = $WrapperPid
            wrapperStart = (Get-ProcStartTicks $WrapperPid)
            appPid       = $appId
            appStart     = $appStart
            appImage     = $appImage
        }
        Set-Content -Path (Join-Path $RunDir "$Name.json") -Value ($rec | ConvertTo-Json) -Encoding ascii
    } catch {
        return $null
    }
}

function Read-ProcRecord([string]$RunDir, [string]$Name) {
    try {
        $file = Join-Path $RunDir "$Name.json"
        if (-not (Test-Path $file)) { return $null }
        $raw = Get-Content -Path $file -Raw -ErrorAction Stop
        if (-not $raw) { return $null }
        return ($raw | ConvertFrom-Json)
    } catch {
        return $null
    }
}

# True when ProcessId is a cmd.exe whose command line carries <RunDir>\<Name>.log
# (the redirect start-public gives every wrapper), so a reused pid is never mistaken for it.
function Test-WrapperPid([string]$RunDir, [string]$Name, [int]$ProcessId) {
    try {
        if ($ProcessId -le 0) { return $false }
        $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
        if (-not $cim) { return $false }
        if ($cim.Name -ne 'cmd.exe') { return $false }
        if (-not $cim.CommandLine) { return $false }
        $log = Join-Path $RunDir "$Name.log"
        return ($cim.CommandLine.IndexOf($log, [StringComparison]::OrdinalIgnoreCase) -ge 0)
    } catch {
        return $false
    }
}

# Pid-file wrapper pid (first line) or $null.
function Read-WrapperPidFile([string]$RunDir, [string]$Name) {
    try {
        $pidFile = Join-Path $RunDir "$Name.pid"
        if (-not (Test-Path $pidFile)) { return $null }
        $line = Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1
        $id = 0
        if ($line -and [int]::TryParse(([string]$line).Trim(), [ref]$id) -and $id -gt 0) { return $id }
        return $null
    } catch {
        return $null
    }
}

# Spec 5.3: json with appPid -> alive only when that pid exists with the same start time.
# Otherwise (legacy / appPid null) -> pid file's process must be our cmd wrapper.
function Test-ServiceAlive([string]$RunDir, [string]$Name) {
    try {
        $rec = Read-ProcRecord $RunDir $Name
        if ($rec -and $null -ne $rec.appPid -and $null -ne $rec.appStart) {
            $ticks = Get-ProcStartTicks ([int]$rec.appPid)
            return (($null -ne $ticks) -and ($ticks -eq [long]$rec.appStart))
        }
        $wp = Read-WrapperPidFile $RunDir $Name
        if ($null -eq $wp) { return $false }
        return (Test-WrapperPid $RunDir $Name $wp)
    } catch {
        return $false
    }
}

# Pure. The newest java.exe that runs the worker profile from $Root's own build output
# (worktree test JVMs have a different classpath path) and started at/after $NotBefore.
function Select-WorkerJvm($Processes, [string]$Root, [datetime]$NotBefore) {
    $classes = (Join-Path $Root 'build\classes\java\main')
    $best = $null
    foreach ($p in @($Processes)) {
        if (-not $p) { continue }
        if ($p.Name -ne 'java.exe') { continue }
        $cl = [string]$p.CommandLine
        if (-not $cl) { continue }
        if ($cl.IndexOf($classes, [StringComparison]::OrdinalIgnoreCase) -lt 0) { continue }
        if ($cl.IndexOf('--spring.profiles.active=worker', [StringComparison]::OrdinalIgnoreCase) -lt 0) { continue }
        if ($null -eq $p.CreationDate -or ([datetime]$p.CreationDate) -lt $NotBefore) { continue }
        if ($null -eq $best -or ([datetime]$p.CreationDate) -gt ([datetime]$best.CreationDate)) { $best = $p }
    }
    if ($null -eq $best) { return $null }
    return [int]$best.ProcessId
}

# Spec 5.2: the process that really does the work, or $null.
function Find-AppPid([string]$Name, [int]$WrapperPid, [string]$Root) {
    try {
        switch ($Name) {
            { $_ -in @('api', 'auth', 'front') } {
                $port = @{ api = 8090; auth = 9000; front = 3001 }[$Name]
                $owner = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
                    Select-Object -ExpandProperty OwningProcess -Unique | Select-Object -First 1
                if ($owner) { return [int]$owner }
                return $null
            }
            'worker' {
                $ticks = Get-ProcStartTicks $WrapperPid
                if ($null -eq $ticks) { return $null }
                $since = [datetime]::FromFileTimeUtc($ticks).ToLocalTime()
                return (Select-WorkerJvm (Get-CimInstance Win32_Process -ErrorAction SilentlyContinue) $Root $since)
            }
            'interview' {
                $node = Get-CimInstance Win32_Process -Filter "ParentProcessId=$WrapperPid" -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -eq 'node.exe' } | Sort-Object CreationDate -Descending | Select-Object -First 1
                if ($node) { return [int]$node.ProcessId }
                return $null
            }
            default { return $null }
        }
    } catch {
        return $null
    }
}

# The only place that runs taskkill (tests replace this function to simulate a failing kill).
# taskkill writes to stderr when the target is already gone or access is denied; under the
# caller's $ErrorActionPreference = 'Stop' that would throw, so it is relaxed here.
function Invoke-TaskKill([int]$ProcessId) {
    $ErrorActionPreference = 'Continue'
    try { & taskkill.exe /PID $ProcessId /T /F *> $null } catch { }
}

# Kills the process tree and answers $true only when the target is no longer running
# (waits up to ~3s for it to disappear). Never throws; refuses pid <= 4 and our own pid.
function Stop-ProcTree([int]$ProcessId) {
    try {
        if ($ProcessId -le 4 -or $ProcessId -eq $PID) { return $false }
        if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) { return $true }
        Invoke-TaskKill $ProcessId
        $deadline = (Get-Date).AddSeconds(3)
        while ((Get-Date) -lt $deadline) {
            if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) { return $true }
            Start-Sleep -Milliseconds 100
        }
        return (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue))
    } catch {
        return $false
    }
}

# Spec 5.4 steps 1-3. Returns the output lines:
#   "[stop] name (wrapper X, app Y)"  what matched was killed (only the parts that matched are shown)
#   "[gone] name"                     nothing of ours was running
#   "[fail] name (... still running)" a kill did not take; pid file and json are KEPT so a rerun can retry
#  1) json with appPid whose start time matches -> kill the app tree
#  2) pid-file wrapper that is our cmd (Test-WrapperPid) -> kill the wrapper tree; otherwise leave it
#  3) remove the pid file and json (only when nothing we meant to kill is still alive)
# Steps 1 and 2 are independent: a failure in one never skips the other or the reporting.
function Stop-ServiceRecord([string]$RunDir, [string]$Name) {
    $rec = $null; $wrapperId = $null; $appId = $null
    $wrapperMatched = $false; $appMatched = $false
    $appStopped = $true; $wrapperStopped = $true

    # Decide what is ours before killing anything (the wrapper exits by itself once its child dies).
    try {
        $rec = Read-ProcRecord $RunDir $Name
        $wrapperId = Read-WrapperPidFile $RunDir $Name
        if ($rec -and $null -ne $rec.appPid -and $null -ne $rec.appStart) {
            $ticks = Get-ProcStartTicks ([int]$rec.appPid)
            if (($null -ne $ticks) -and ($ticks -eq [long]$rec.appStart)) { $appMatched = $true; $appId = [int]$rec.appPid }
        }
        if ($null -ne $wrapperId -and (Test-WrapperPid $RunDir $Name $wrapperId)) { $wrapperMatched = $true }
    } catch { }

    if ($appMatched) {
        try { $appStopped = [bool](Stop-ProcTree $appId) } catch { $appStopped = $false }
    }
    if ($wrapperMatched) {
        try {
            if (Get-Process -Id $wrapperId -ErrorAction SilentlyContinue) {
                # still running: confirm it is still our wrapper (not a reused pid) before killing
                if (Test-WrapperPid $RunDir $Name $wrapperId) { $wrapperStopped = [bool](Stop-ProcTree $wrapperId) }
            }
        } catch { $wrapperStopped = $false }
    }

    $parts = @()
    if ($wrapperMatched) { $parts += "wrapper $wrapperId" }
    if ($appMatched) { $parts += "app $appId" }
    if ($appStopped -and $wrapperStopped) {
        Remove-Item (Join-Path $RunDir "$Name.pid") -Force -ErrorAction SilentlyContinue
        Remove-Item (Join-Path $RunDir "$Name.json") -Force -ErrorAction SilentlyContinue
        if ($parts.Count -gt 0) { return @("[stop] $Name ($($parts -join ', '))") }
        return @("[gone] $Name")
    }
    $left = @()
    if (-not $wrapperStopped) { $left += "wrapper $wrapperId" }
    if (-not $appStopped) { $left += "app $appId" }
    return @("[fail] $Name ($($left -join ', ') still running; kept $Name.pid/.json)")
}

# Pure. claude.exe processes running the stack's claude CLI whose parent is gone, or started
# after them (pid reuse). Listed only, never killed (spec 5.5).
function Select-OrphanClaude($Processes, [string]$ClaudeExe) {
    $all = @($Processes)
    $byId = @{}
    foreach ($p in $all) { if ($p) { $byId[[int]$p.ProcessId] = $p } }
    $result = @()
    foreach ($p in $all) {
        if (-not $p) { continue }
        if ($p.Name -ne 'claude.exe') { continue }
        if (-not $p.ExecutablePath -or -not [string]::Equals([string]$p.ExecutablePath, $ClaudeExe, [StringComparison]::OrdinalIgnoreCase)) { continue }
        $parent = $byId[[int]$p.ParentProcessId]
        if ($null -eq $parent -or ([datetime]$parent.CreationDate) -gt ([datetime]$p.CreationDate)) { $result += $p }
    }
    return $result
}
