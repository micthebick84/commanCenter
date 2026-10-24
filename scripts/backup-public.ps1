# Backs up the live data of this PC's netisMaker stack (Windows PowerShell 5.1+).
#   1) pg_dump (custom format) of the stack database: schema com (Symphony) and public (netis-auth),
#      checked afterwards with pg_restore --list
#   2) the attachments folder (app.attachment.dir) as attachments.zip
#   3) manifest.txt: sizes, SHA-256, git HEAD of commanCenter and netis-auth
# into <BACKUP_DIR>\<yyyyMMdd-HHmmss>\, keeping BACKUP_KEEP_DAYS days of them. With BACKUP_REMOTE
# (user@host of a Windows OpenSSH server, key auth) the folder is also copied with scp to
# BACKUP_REMOTE_DIR on that host, checked there by SHA-256, and BACKUP_REMOTE_KEEP_DAYS days are kept.
# Old folders are pruned only after a successful backup, and only folders named yyyyMMdd-HHmmss.
#
# NOT included on purpose: public.env. It holds the DB password, tokens and the key that decrypts the
# DB-connection passwords inside the dump - keep your own copy of it (and of ~\.cloudflared, ~\.ssh)
# in a password manager, apart from these backups.
#
# Settings (environment first, then %USERPROFILE%\netis-maker\public.env):
#   PG_DIR                      zip-installed PostgreSQL root (bin\pg_dump.exe); else pg_dump.exe on PATH
#   SPRING_DATASOURCE_PASSWORD  DB password (the apps use the same one)
#   APP_ATTACHMENT_DIR          attachments folder; default %USERPROFILE%\netis-maker\attachments
#   BACKUP_DIR                  default %USERPROFILE%\netis-maker\backups
#   BACKUP_KEEP_DAYS            default 14
#   BACKUP_REMOTE               e.g. Administrator@10.1.1.75; empty = no remote copy
#   BACKUP_REMOTE_DIR           default C:/netis-backups
#   BACKUP_REMOTE_KEEP_DAYS     default 30
# The database is localhost:5432/postgres as user postgres, like application.yml.
#
# Usage:  .\scripts\backup-public.ps1             back up now
#         .\scripts\backup-public.ps1 -NoRemote   skip the remote copy
#         .\scripts\install-backup-task.ps1       run it every day (task netisMaker-backup)
# Exit code: 0 ok, 1 backup failed (nothing pruned), 2 local backup ok but the remote copy failed.
# Log: .run\backup.log (appended). Restore steps: CLAUDE.md, environment notes, "DB backup".
# ASCII only on purpose: Windows PowerShell 5.1 misreads BOM-less UTF-8 scripts.

[CmdletBinding()]
param(
    [string]$EnvFile = (Join-Path $env:USERPROFILE 'netis-maker\public.env'),
    [switch]$NoRemote
)

$StampFormat = 'yyyyMMdd-HHmmss'

# ---- helpers (the pure ones are tested by scripts\tests\backup-public.test.ps1) ----

# Positive integer setting, or $Default.
function Get-IntSetting([string]$Value, [int]$Default) {
    $n = 0
    if ($Value -and [int]::TryParse($Value, [ref]$n) -and $n -ge 1) { return $n }
    return $Default
}

# One argument for Start-Process, which joins -ArgumentList with spaces and does not quote.
function ConvertTo-Arg([string]$Value) {
    if ($Value -match '[\s"]') { return '"' + ($Value -replace '"', '\"') + '"' }
    return $Value
}

# Names among $Names that are backup folders (yyyyMMdd-HHmmss) older than $KeepDays at $Now.
# Never $Current, never a folder with any other name.
function Select-ExpiredBackups([string[]]$Names, [datetime]$Now, [int]$KeepDays, [string]$Current) {
    $cutoff = $Now.AddDays(-$KeepDays)
    foreach ($n in @($Names)) {
        if (-not $n -or $n -eq $Current) { continue }
        $t = [datetime]::MinValue
        if ([datetime]::TryParseExact($n, $StampFormat, [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::None, [ref]$t) -and $t -lt $cutoff) { $n }
    }
}

# True when pg_restore --list output holds table data of both schemas (com = Symphony, public = netis-auth).
function Test-DumpListing([string]$Listing) {
    return ($Listing -match '(?m)TABLE DATA com task ') -and ($Listing -match '(?m)TABLE DATA public ')
}

# Names of $Local (name -> SHA-256) whose hash is missing or different in the remote "<name> <hash>" lines.
function Compare-RemoteHashes([hashtable]$Local, [string]$RemoteOutput) {
    $remote = @{}
    foreach ($line in ([string]$RemoteOutput -split "`r?`n")) {
        if ($line -match '^\s*(\S+)\s+([0-9A-Fa-f]{64})\s*$') { $remote[$Matches[1]] = $Matches[2].ToUpperInvariant() }
    }
    foreach ($k in @($Local.Keys | Sort-Object)) {
        if ($remote[$k] -ne ([string]$Local[$k]).ToUpperInvariant()) { $k }
    }
}

# Runs an exe to completion; output goes through temp files so PowerShell 5.1 never turns stderr
# lines into errors. Answers ExitCode, Out, Err.
function Invoke-Exe([string]$Exe, [string[]]$Arguments) {
    $out = [IO.Path]::GetTempFileName()
    $err = [IO.Path]::GetTempFileName()
    try {
        $p = Start-Process -FilePath $Exe -ArgumentList ((@($Arguments) | ForEach-Object { ConvertTo-Arg $_ }) -join ' ') `
            -NoNewWindow -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
        $null = $p.Handle   # cache the handle so ExitCode is available after exit
        $p.WaitForExit()
        return [pscustomobject]@{ ExitCode = $p.ExitCode; Out = [IO.File]::ReadAllText($out); Err = [IO.File]::ReadAllText($err) }
    } finally {
        Remove-Item -LiteralPath $out, $err -Force -ErrorAction SilentlyContinue
    }
}

# Windows' own OpenSSH client first (the one the worker uses), else whatever is on PATH.
function Get-SshExe([string]$Name) {
    $own = Join-Path $env:WINDIR "System32\OpenSSH\$Name.exe"
    if (Test-Path -LiteralPath $own) { return $own }
    return $Name
}

# Runs a PowerShell script on the remote Windows host. -EncodedCommand keeps it clear of cmd / ssh quoting.
function Invoke-RemotePs([string]$Remote, [string]$Script) {
    $enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Script))
    return Invoke-Exe (Get-SshExe 'ssh') @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', $Remote,
        "powershell -NoProfile -NonInteractive -EncodedCommand $enc")
}

if ($MyInvocation.InvocationName -eq '.') { return }   # dot-sourced by the tests: helpers only

# ---- backup -------------------------------------------------------------------

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'public-proc.ps1')   # Import-PublicEnv

$Root    = Split-Path -Parent $PSScriptRoot
$RunDir  = Join-Path $Root '.run'
$LogFile = Join-Path $RunDir 'backup.log'
New-Item -ItemType Directory -Force $RunDir | Out-Null

function Write-Log([string]$Message) {
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Write-Host $line
    Add-Content -LiteralPath $LogFile -Value $line -Encoding UTF8
}

function Format-Size([long]$Bytes) { '{0:N1} MB' -f ($Bytes / 1MB) }

$dest = $null
$stamp = $null
$hashes = @{}
try {
    Import-PublicEnv $EnvFile
    $backupDir = if ($env:BACKUP_DIR) { $env:BACKUP_DIR } else { Join-Path $env:USERPROFILE 'netis-maker\backups' }
    $keepDays  = Get-IntSetting $env:BACKUP_KEEP_DAYS 14
    $attDir    = if ($env:APP_ATTACHMENT_DIR) { $env:APP_ATTACHMENT_DIR } else { Join-Path $env:USERPROFILE 'netis-maker\attachments' }
    if (-not $env:SPRING_DATASOURCE_PASSWORD) { throw "SPRING_DATASOURCE_PASSWORD is not set ($EnvFile)" }

    $pgDump = $null
    if ($env:PG_DIR -and (Test-Path -LiteralPath (Join-Path $env:PG_DIR 'bin\pg_dump.exe'))) {
        $pgDump = Join-Path $env:PG_DIR 'bin\pg_dump.exe'
    } else {
        $pgDump = (Get-Command 'pg_dump.exe' -ErrorAction Stop).Source
    }
    $pgRestore = Join-Path (Split-Path -Parent $pgDump) 'pg_restore.exe'

    $stamp = Get-Date -Format $StampFormat
    $dest = Join-Path $backupDir $stamp
    New-Item -ItemType Directory -Force $dest | Out-Null
    Write-Log "[..]   backup $stamp -> $dest"

    # 1) database: both schemas of 'postgres'; the password goes to pg_dump through PGPASSWORD only
    $dump = Join-Path $dest 'postgres.dump'
    $savedPassword = $env:PGPASSWORD
    $env:PGPASSWORD = $env:SPRING_DATASOURCE_PASSWORD
    try {
        $r = Invoke-Exe $pgDump @('--host=localhost', '--port=5432', '--username=postgres', '--dbname=postgres',
            '--format=custom', '--no-password', "--file=$dump")
    } finally {
        $env:PGPASSWORD = $savedPassword
    }
    if ($r.ExitCode -ne 0) { throw "pg_dump failed (exit $($r.ExitCode)): $($r.Err.Trim())" }
    $list = Invoke-Exe $pgRestore @('--list', $dump)
    if ($list.ExitCode -ne 0 -or -not (Test-DumpListing $list.Out)) {
        throw "dump check failed: pg_restore --list exit $($list.ExitCode), com/public table data present = $(Test-DumpListing $list.Out)"
    }

    # 2) attachments
    if (Test-Path -LiteralPath $attDir) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [IO.Compression.ZipFile]::CreateFromDirectory($attDir, (Join-Path $dest 'attachments.zip'),
            [IO.Compression.CompressionLevel]::Optimal, $false)
    } else {
        Write-Log "[..]   no attachments folder ($attDir) - skipped"
    }

    # 3) manifest
    $lines = @(
        "created       $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
        "host          $env:COMPUTERNAME",
        "pg_dump       $((Invoke-Exe $pgDump @('--version')).Out.Trim())"
    )
    foreach ($repo in @(@('commanCenter', $Root), @('netis-auth', (Join-Path (Split-Path -Parent $Root) 'netis-auth')))) {
        $head = '-'
        try {
            $g = Invoke-Exe 'git' @('-C', $repo[1], 'rev-parse', 'HEAD')
            if ($g.ExitCode -eq 0) { $head = $g.Out.Trim() }
        } catch { }
        $lines += ('{0,-13} {1}' -f $repo[0], $head)
    }
    $lines += 'files:'
    foreach ($f in @(Get-ChildItem -LiteralPath $dest -File | Sort-Object Name)) {
        $h = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash
        $hashes[$f.Name] = $h
        $lines += ('  {0,-16} {1,14} {2}' -f $f.Name, $f.Length, $h)
    }
    $manifest = Join-Path $dest 'manifest.txt'
    Set-Content -LiteralPath $manifest -Value $lines -Encoding ascii
    $hashes['manifest.txt'] = (Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash

    $sizes = (Get-ChildItem -LiteralPath $dest -File | Sort-Object Name | ForEach-Object { "$($_.Name) $(Format-Size $_.Length)" }) -join ', '
    Write-Log "[ok]   local: $sizes"

    $expired = @(Select-ExpiredBackups @(Get-ChildItem -LiteralPath $backupDir -Directory | ForEach-Object { $_.Name }) (Get-Date) $keepDays $stamp)
    foreach ($n in $expired) { Remove-Item -LiteralPath (Join-Path $backupDir $n) -Recurse -Force }
    if ($expired.Count -gt 0) { Write-Log "[..]   pruned local (older than $keepDays days): $($expired -join ', ')" }
} catch {
    Write-Log "[FAIL] backup: $($_.Exception.Message)"
    if ($dest -and (Test-Path -LiteralPath $dest)) { Remove-Item -LiteralPath $dest -Recurse -Force -ErrorAction SilentlyContinue }
    exit 1
}

# ---- remote copy ----------------------------------------------------------------

$remote = if ($NoRemote) { '' } else { [string]$env:BACKUP_REMOTE }
if ($remote) {
    try {
        $remoteDir  = if ($env:BACKUP_REMOTE_DIR) { $env:BACKUP_REMOTE_DIR.TrimEnd('/', '\') } else { 'C:/netis-backups' }
        $remoteKeep = Get-IntSetting $env:BACKUP_REMOTE_KEEP_DAYS 30
        $q = $remoteDir -replace "'", "''"

        $r = Invoke-RemotePs $remote "New-Item -ItemType Directory -Force -Path '$q' | Out-Null"
        if ($r.ExitCode -ne 0) { throw "ssh failed (exit $($r.ExitCode)): $($r.Err.Trim())" }
        $r = Invoke-Exe (Get-SshExe 'scp') @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', '-r', $dest, "${remote}:$remoteDir/")
        if ($r.ExitCode -ne 0) { throw "scp failed (exit $($r.ExitCode)): $($r.Err.Trim())" }

        $r = Invoke-RemotePs $remote ("Get-ChildItem -LiteralPath '$q/$stamp' -File | ForEach-Object { " +
            "`$_.Name + ' ' + (Get-FileHash -LiteralPath `$_.FullName -Algorithm SHA256).Hash }")
        if ($r.ExitCode -ne 0) { throw "remote hash failed (exit $($r.ExitCode)): $($r.Err.Trim())" }
        $bad = @(Compare-RemoteHashes $hashes $r.Out)
        if ($bad.Count -gt 0) { throw "remote copy differs from the local one: $($bad -join ', ')" }
        Write-Log "[ok]   remote: ${remote}:$remoteDir/$stamp (SHA-256 match)"

        $r = Invoke-RemotePs $remote "Get-ChildItem -LiteralPath '$q' -Directory | ForEach-Object { `$_.Name }"
        if ($r.ExitCode -ne 0) { throw "remote listing failed (exit $($r.ExitCode)): $($r.Err.Trim())" }
        $expired = @(Select-ExpiredBackups @($r.Out -split "`r?`n" | ForEach-Object { $_.Trim() }) (Get-Date) $remoteKeep $stamp)
        if ($expired.Count -gt 0) {
            $names = ($expired | ForEach-Object { "'$_'" }) -join ','
            $r = Invoke-RemotePs $remote "foreach (`$n in @($names)) { Remove-Item -LiteralPath (Join-Path '$q' `$n) -Recurse -Force }"
            if ($r.ExitCode -ne 0) { throw "remote prune failed (exit $($r.ExitCode)): $($r.Err.Trim())" }
            Write-Log "[..]   pruned remote (older than $remoteKeep days): $($expired -join ', ')"
        }
    } catch {
        Write-Log "[FAIL] remote copy: $($_.Exception.Message)"
        exit 2
    }
}

Write-Log "[ok]   backup $stamp done"
exit 0
