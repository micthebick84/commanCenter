# Registers (or removes) a Task Scheduler task that runs scripts\backup-public.ps1 every day
# (PowerShell 5.1+). Like install-autostart.ps1 it needs no admin rights and no stored Windows
# password: the task runs in the user's own logon session (where the stack itself runs) and uses
# that user's ssh key for the remote copy. A run missed while the PC was asleep or off starts as
# soon as it is possible again (StartWhenAvailable).
#
# Usage:
#   .\scripts\install-backup-task.ps1                register (daily at 02:00)
#   .\scripts\install-backup-task.ps1 -At 01:30      another time
#   .\scripts\install-backup-task.ps1 -Remove        unregister
#   Start-ScheduledTask -TaskName netisMaker-backup  run it now (test)
# Settings (BACKUP_*) live in %USERPROFILE%\netis-maker\public.env - see backup-public.ps1.
# Results: .run\backup.log, and the task's Last Run Result (0 ok, 1 backup failed, 2 remote copy failed).
# 02:00 is before the nightly clean-ups (worktrees 02:30, repo cache 03:00, attachments 03:30,
# interview checkouts 03:50), so the backup still holds what they remove.
# ASCII only on purpose: Windows PowerShell 5.1 misreads BOM-less UTF-8 scripts.

[CmdletBinding()]
param(
    [string]$TaskName = 'netisMaker-backup',
    [string]$At = '02:00',
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'

if ($Remove) {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Write-Host "Removed task '$TaskName'. Existing backups were left in place."
    } else {
        Write-Host "Task '$TaskName' is not registered."
    }
    return
}

$Root   = Split-Path -Parent $PSScriptRoot
$Backup = Join-Path $PSScriptRoot 'backup-public.ps1'
$log    = Join-Path $Root '.run\backup.log'

$action   = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$Backup`"" `
    -WorkingDirectory $Root
$trigger  = New-ScheduledTaskTrigger -Daily -At $At
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 1)
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Force `
    -Description 'Backs up the netisMaker database and attachments (scripts\backup-public.ps1).' | Out-Null

Write-Host "Registered task '$TaskName' (daily at $At, as $env:USERNAME)."
Write-Host "  log      : $log"
Write-Host "  test now : Start-ScheduledTask -TaskName $TaskName"
Write-Host "  remove   : .\scripts\install-backup-task.ps1 -Remove"
