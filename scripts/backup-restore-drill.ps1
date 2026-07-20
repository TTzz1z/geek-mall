# Backup integrity drill for geek_ai
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$stampOut = & powershell -NoProfile -File (Join-Path $Root 'scripts\backup.ps1')
$stamp = ($stampOut | Select-Object -Last 1).ToString().Trim()
$aiSql = Join-Path $Root "backups\geek_ai-$stamp.sql"
if (-not (Test-Path $aiSql)) { throw "missing $aiSql" }

& powershell -NoProfile -File (Join-Path $Root 'scripts\restore.ps1') -SqlFile $aiSql -Database geek_ai

$hashOk = Test-Path "$aiSql.sha256"
Write-Host ("[{0}] backup_checksum_file" -f ($(if ($hashOk) { 'PASS' } else { 'FAIL' })))
Write-Host "[PASS] backup_restore_drill stamp=$stamp"
if (-not $hashOk) { exit 1 }
