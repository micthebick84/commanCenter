# Stops what scripts/start-public.ps1 started (PowerShell 5.1+).
#   1) kills each .run\<name>.pid process tree (cmd -> gradle wrapper -> JVM / node)
#   2) frees ports 9000 / 8090 / 3001 if something is still listening
#      (a Gradle-forked JVM can outlive its wrapper)
# cloudflared and PostgreSQL are left alone.
#
# Usage:  .\scripts\stop-public.ps1            stop everything
#         .\scripts\stop-public.ps1 -KeepWorkers   leave worker + interview running
# ASCII only on purpose: Windows PowerShell 5.1 misreads BOM-less UTF-8 scripts.

[CmdletBinding()]
param([switch]$KeepWorkers)

$Root   = Split-Path -Parent $PSScriptRoot
$RunDir = Join-Path $Root '.run'

$names = @('front', 'api', 'auth')
if (-not $KeepWorkers) { $names = @('interview', 'worker') + $names }

foreach ($name in $names) {
    $pidFile = Join-Path $RunDir "$name.pid"
    if (-not (Test-Path $pidFile)) { continue }
    $id = Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($id -and (Get-Process -Id $id -ErrorAction SilentlyContinue)) {
        & taskkill.exe /PID $id /T /F *> $null
        Write-Host "[stop] $name (pid $id)"
    } else {
        Write-Host "[gone] $name"
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

$ports = @{ 9000 = 'auth'; 8090 = 'api'; 3001 = 'front' }
foreach ($port in $ports.Keys) {
    $owners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($owner in $owners) {
        if ($owner -le 4) { continue }
        & taskkill.exe /PID $owner /T /F *> $null
        Write-Host "[stop] $($ports[$port]) leftover on :$port (pid $owner)"
    }
}
Write-Host "Done."
