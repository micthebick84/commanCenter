# Registers (or removes) a Task Scheduler task that runs start-public.ps1 when
# the current user logs on (PowerShell 5.1+). No admin rights and no stored
# Windows password needed - the task runs in the user's own logon session,
# which the worker needs anyway (claude CLI login, ~/.claude.json, git credentials).
#
# For a true unattended reboot, enable Windows auto-logon for this account or
# keep the session logged in; an at-startup task would need admin + a stored password.
#
# Usage:
#   .\scripts\install-autostart.ps1                 register (API + auth + front)
#   .\scripts\install-autostart.ps1 -WithWorkers    also worker + interview service
#   .\scripts\install-autostart.ps1 -Remove         unregister
#   Start-ScheduledTask -TaskName netisMaker-public     run it now (test)
# Output of each run: .run\autostart.log
#
# Settings and secrets are NOT stored in the task. start-public.ps1 reads them from
# %USERPROFILE%\netis-maker\public.env - a template is created on first install.
# ASCII only on purpose: Windows PowerShell 5.1 misreads BOM-less UTF-8 scripts.

[CmdletBinding()]
param(
    [string]$TaskName = 'netisMaker-public',
    [switch]$WithWorkers,
    [int]$DelaySeconds = 30,
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'

if ($Remove) {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Write-Host "Removed task '$TaskName'. Running services were not stopped (use stop-public.ps1)."
    } else {
        Write-Host "Task '$TaskName' is not registered."
    }
    return
}

$Root    = Split-Path -Parent $PSScriptRoot
$Start   = Join-Path $PSScriptRoot 'start-public.ps1'
$RunDir  = Join-Path $Root '.run'
$EnvDir  = Join-Path $env:USERPROFILE 'netis-maker'
$EnvFile = Join-Path $EnvDir 'public.env'
New-Item -ItemType Directory -Force $RunDir, $EnvDir | Out-Null

if (-not (Test-Path $EnvFile)) {
    @(
        '# netisMaker public stack settings - read by scripts\start-public.ps1.'
        '# KEY=VALUE per line. Keep this file out of any repository: it holds secrets.'
        ''
        'AUTH_ORIGIN=https://auth.example.com'
        'APP_ORIGIN=https://app.example.com'
        ''
        '# Zip-installed PostgreSQL root (started when :5432 is not listening). Remove if PostgreSQL is a Windows service.'
        '#PG_DIR=C:\Users\me\pgsql'
        ''
        '#SPRING_DATASOURCE_PASSWORD='
        '#WORKER_API_KEY='
        '#GITHUB_PAT='
    ) | Set-Content -Path $EnvFile -Encoding ascii
    Write-Host "Created settings template: $EnvFile  <-- edit this before the first run"
}

$workers = if ($WithWorkers) { ' -WithWorkers' } else { '' }
$log     = Join-Path $RunDir 'autostart.log'
$command = "& '$Start'$workers *> '$log'"

$action   = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command `"$command`"" `
    -WorkingDirectory $Root
$trigger  = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
$trigger.Delay = "PT${DelaySeconds}S"
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 30)
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Force `
    -Description 'Starts netis-auth, netisMaker API and frontend (scripts\start-public.ps1) at logon.' | Out-Null

Write-Host "Registered task '$TaskName' (at logon of $env:USERNAME, +${DelaySeconds}s)$(if ($WithWorkers) { ' with workers' })."
Write-Host "  settings : $EnvFile"
Write-Host "  log      : $log"
Write-Host "  test now : Start-ScheduledTask -TaskName $TaskName"
Write-Host "  remove   : .\scripts\install-autostart.ps1 -Remove"
