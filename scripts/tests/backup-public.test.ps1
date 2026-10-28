# Tests for the pure helpers of scripts/backup-public.ps1 (no Pester; plain assertions, exit 1 on failure).
# Run:  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\backup-public.test.ps1
# Dot-sources the script, which then defines its helpers and returns before backing anything up.
# ASCII only on purpose: Windows PowerShell 5.1 misreads BOM-less UTF-8 scripts.

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot '..\backup-public.ps1')

$script:failures = 0
function Assert-True([bool]$Cond, [string]$Name, [string]$Detail = '') {
    if ($Cond) { Write-Host "PASS $Name" }
    else { $script:failures++; Write-Host "FAIL ${Name}: $Detail" }
}
function Assert-Equal($Expected, $Actual, [string]$Name) {
    if ("$Expected" -eq "$Actual") { Write-Host "PASS $Name" }
    else { $script:failures++; Write-Host "FAIL ${Name}: expected [$Expected] actual [$Actual]" }
}

try {
    # ---- Get-IntSetting ------------------------------------------------------
    Assert-Equal 14 (Get-IntSetting '' 14) 'int setting: empty -> default'
    Assert-Equal 7 (Get-IntSetting '7' 14) 'int setting: number'
    Assert-Equal 14 (Get-IntSetting '0' 14) 'int setting: zero -> default'
    Assert-Equal 14 (Get-IntSetting 'abc' 14) 'int setting: garbage -> default'

    # ---- ConvertTo-Arg -------------------------------------------------------
    Assert-Equal '--file=C:\x\a.dump' (ConvertTo-Arg '--file=C:\x\a.dump') 'arg: plain stays bare'
    Assert-Equal '"--file=C:\a b\x.dump"' (ConvertTo-Arg '--file=C:\a b\x.dump') 'arg: spaces get quoted'
    Assert-Equal '"say \"hi\""' (ConvertTo-Arg 'say "hi"') 'arg: inner quotes escaped'

    # ---- Select-ExpiredBackups -----------------------------------------------
    $names = @('20261001-020000', '20261010-020000', '20261010-020001', '20261023-020000', '20261024-020000',
               'notes', '20261001-020000-failed', '2026101-020000', '')
    # cutoff 2026-10-10 01:59:00: 10-10 02:00 is newer than that, only 10-01 is older
    $exp = @(Select-ExpiredBackups $names ([datetime]'2026-10-24 01:59:00') 14 '20261024-020000')
    Assert-True (($exp.Count -eq 1) -and ($exp[0] -eq '20261001-020000')) 'expired: only stamps older than the cutoff' ($exp -join ',')
    # cutoff 2026-10-23 03:00: everything up to 10-23 02:00 is older, but the current folder is never listed
    $exp = @(Select-ExpiredBackups $names ([datetime]'2026-10-24 03:00:00') 1 '20261001-020000')
    Assert-True (($exp.Count -eq 3) -and ($exp -contains '20261010-020000') -and ($exp -contains '20261010-020001') -and ($exp -contains '20261023-020000')) `
        'expired: older stamps listed' ($exp -join ',')
    Assert-True (-not ($exp -contains '20261001-020000')) 'expired: current folder never listed' ($exp -join ',')
    Assert-True (-not ($exp -contains 'notes') -and -not ($exp -contains '20261001-020000-failed') -and -not ($exp -contains '2026101-020000')) `
        'expired: other names never listed' ($exp -join ',')
    Assert-Equal 0 (@(Select-ExpiredBackups @() ([datetime]'2026-10-24') 14 'x')).Count 'expired: empty list'

    # ---- Test-DumpListing ----------------------------------------------------
    $listing = @(
        ';',
        '; Archive created at 2026-10-24 02:00:01 KST',
        '3712; 0 16521 TABLE DATA com task postgres',
        '3713; 0 16530 TABLE DATA public users postgres'
    ) -join "`r`n"
    Assert-True (Test-DumpListing $listing) 'dump listing: both schemas present'
    Assert-True (-not (Test-DumpListing ($listing -replace 'TABLE DATA public users', 'TABLE public users'))) 'dump listing: no public table data -> false'
    Assert-True (-not (Test-DumpListing ($listing -replace 'com task', 'com task_x'))) 'dump listing: no com.task data -> false'
    Assert-True (-not (Test-DumpListing '')) 'dump listing: empty -> false'

    # ---- Compare-RemoteHashes ------------------------------------------------
    $h1 = 'A' * 64; $h2 = 'b' * 64; $h3 = 'C' * 64
    $local = @{ 'postgres.dump' = $h1; 'attachments.zip' = $h2.ToUpper(); 'manifest.txt' = $h3 }
    $remoteOut = "postgres.dump $h1`r`nattachments.zip $h2`r`nmanifest.txt $h3`r`n"
    Assert-Equal 0 (@(Compare-RemoteHashes $local $remoteOut)).Count 'hashes: all match (case-insensitive)'
    $diff = @(Compare-RemoteHashes $local "postgres.dump $h1`r`nattachments.zip $('D' * 64)")
    Assert-True (($diff.Count -eq 2) -and ($diff -contains 'attachments.zip') -and ($diff -contains 'manifest.txt')) 'hashes: different and missing are reported' ($diff -join ',')

    # ---- New-AttachmentsZip --------------------------------------------------
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("att-zip-test-" + [guid]::NewGuid().ToString('N'))
    try {
        $src = Join-Path $tmp 'src'
        foreach ($d in @('task-1', 'task-1\1-a.pptx.render', '.render-work\x', 'task-2\deep\2-b.render', 'task-2\deep')) {
            New-Item -ItemType Directory -Force (Join-Path $src $d) | Out-Null
        }
        foreach ($f in @('task-1\1-a.pptx', 'task-1\1-a.pptx.render\slide-001.png', '.render-work\x\input.pptx',
                         'task-2\deep\2-b.render\slide-001.png', 'task-2\deep\keep.txt', 'task-2\notes.render.txt')) {
            Set-Content -LiteralPath (Join-Path $src $f) -Value 'x' -Encoding ascii
        }
        $zipPath = Join-Path $tmp 'attachments.zip'
        New-AttachmentsZip $src $zipPath
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [IO.Compression.ZipFile]::OpenRead($zipPath)
        try { $entries = @($zip.Entries | ForEach-Object { $_.FullName -replace '\\', '/' } | Sort-Object) } finally { $zip.Dispose() }
        $want = @('task-1/1-a.pptx', 'task-2/deep/keep.txt', 'task-2/notes.render.txt')
        Assert-Equal ($want -join ',') ($entries -join ',') 'zip: render outputs and .render-work excluded, other files kept'
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
catch {
    $script:failures++
    Write-Host "FAIL unexpected error: $($_.Exception.Message) at line $($_.InvocationInfo.ScriptLineNumber)"
}

if ($script:failures -gt 0) { Write-Host "$($script:failures) FAILED"; exit 1 }
Write-Host 'ALL PASS'
exit 0
