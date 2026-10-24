# Stops what scripts/start-public.ps1 started (PowerShell 5.1+).
#   1) per service: kills the real JVM / node process recorded in .run\<name>.json (checked by pid + start time)
#      and the cmd wrapper of .run\<name>.pid (checked by its command line); see scripts/public-proc.ps1
#   2) frees ports 9000 / 8090 / 3001 of the selected services if something is still listening
#      (a Gradle-forked JVM can outlive its wrapper)
#   3) lists claude.exe processes of this stack whose parent is gone (never kills them)
# cloudflared and PostgreSQL are left alone.
#
# Usage:  .\scripts\stop-public.ps1                       stop everything
#         .\scripts\stop-public.ps1 -KeepWorkers          leave worker + interview running
#         .\scripts\stop-public.ps1 -Only worker          stop only the worker (restart it with start-public.ps1 -WithWorkers)
#         .\scripts\stop-public.ps1 -Only worker,interview
#   -Only takes interview, worker, front, api, auth; it cannot be combined with -KeepWorkers.
# ASCII only on purpose: Windows PowerShell 5.1 misreads BOM-less UTF-8 scripts.

[CmdletBinding()]
param([switch]$KeepWorkers, [string[]]$Only)

# Shared helpers: Stop-ServiceRecord, Import-PublicEnv, Select-OrphanClaude, ...
. (Join-Path $PSScriptRoot 'public-proc.ps1')

$Root   = Split-Path -Parent $PSScriptRoot
$RunDir = Join-Path $Root '.run'

# Stop order: workers first, then front, api, auth.
$allNames = @('interview', 'worker', 'front', 'api', 'auth')
$names = $allNames
if ($KeepWorkers) { $names = @('front', 'api', 'auth') }

if ($PSBoundParameters.ContainsKey('Only')) {
    if ($KeepWorkers) { throw "-Only and -KeepWorkers cannot be used together." }
    # also accept one comma-separated string (powershell -File passes "worker,interview" as a single value)
    $wanted = @($Only | ForEach-Object { ([string]$_) -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($wanted.Count -eq 0) { throw "-Only needs at least one of: $($allNames -join ', ')" }
    foreach ($w in $wanted) {
        if ($allNames -notcontains $w) { throw "Unknown -Only value '$w'. Use: $($allNames -join ', ')" }
    }
    $names = @($allNames | Where-Object { $wanted -contains $_ })
}

foreach ($name in $names) {
    foreach ($line in @(Stop-ServiceRecord $RunDir $name)) { Write-Host $line }
}

# Port sweep: only the selected services' ports (taskkill stderr is discarded, never terminating here).
$ports = @{ 9000 = 'auth'; 8090 = 'api'; 3001 = 'front' }
foreach ($port in $ports.Keys) {
    if ($names -notcontains $ports[$port]) { continue }
    $owners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($owner in $owners) {
        if ($owner -le 4) { continue }
        & taskkill.exe /PID $owner /T /F *> $null
        Write-Host "[stop] $($ports[$port]) leftover on :$port (pid $owner)"
    }
}

# Orphan claude.exe of this stack's CLI (listed only; the user's own claude sessions are never touched).
try {
    Import-PublicEnv (Join-Path $env:USERPROFILE 'netis-maker\public.env')
    $claudeExe = $env:CLAUDE_CLI
    if (-not $claudeExe) { $claudeExe = Join-Path $env:USERPROFILE '.local\bin\claude.exe' }
    $claudeExe = $claudeExe -replace '/', '\'
    $orphans = @(Select-OrphanClaude (Get-CimInstance Win32_Process -ErrorAction SilentlyContinue) $claudeExe)
    foreach ($o in $orphans) {
        $started = ''
        if ($o.CreationDate) { $started = ([datetime]$o.CreationDate).ToString('yyyy-MM-dd HH:mm:ss') }
        $cmd = [string]$o.CommandLine
        if ($cmd.Length -gt 200) { $cmd = $cmd.Substring(0, 200) }
        Write-Host "[warn] orphan claude: pid $($o.ProcessId) started $started  $cmd"
        Write-Host "       not stopped here. If it is not in use: taskkill /PID $($o.ProcessId) /T /F"
    }
} catch {
    Write-Host "[warn] orphan claude check skipped: $($_.Exception.Message)"
}
Write-Host "Done."
