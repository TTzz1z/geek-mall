# Backup integrity drill for geek_ai
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$stampOut = & powershell -NoProfile -File (Join-Path $Root 'scripts\backup.ps1')
$backupExit = $LASTEXITCODE
if ($backupExit -ne 0) { throw "backup failed exitCode=$backupExit" }
$stamp = ($stampOut | Select-Object -Last 1).ToString().Trim()
$aiSql = Join-Path $Root "backups\geek_ai-$stamp.sql"
if (-not (Test-Path $aiSql)) { throw "missing $aiSql" }

& powershell -NoProfile -File (Join-Path $Root 'scripts\restore.ps1') -SqlFile $aiSql -Database geek_ai
$restoreExit = $LASTEXITCODE

$hashOk = Test-Path "$aiSql.sha256"
Write-Host ("[{0}] backup_checksum_file" -f ($(if ($hashOk) { 'PASS' } else { 'FAIL' })))
$drillOk = ($backupExit -eq 0 -and $restoreExit -eq 0 -and $hashOk)
Write-Host ("[{0}] backup_restore_drill stamp={1} backupExit={2} restoreExit={3}" -f ($(if ($drillOk) { 'PASS' } else { 'FAIL' })), $stamp, $backupExit, $restoreExit)
if (-not $drillOk) { exit 1 }
