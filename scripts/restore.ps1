param(
  [Parameter(Mandatory = $true)][string]$SqlFile,
  [string]$Database = ''
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path $SqlFile)) { throw "file not found: $SqlFile" }

$hashFile = "$SqlFile.sha256"
if (Test-Path $hashFile) {
  $expected = ((Get-Content $hashFile -Raw).Trim() -split '\s+')[0]
  $actual = (Get-FileHash -Algorithm SHA256 -Path $SqlFile).Hash
  if ($expected.ToUpper() -ne $actual.ToUpper()) { throw "checksum mismatch expected=$expected actual=$actual" }
  Write-Host 'checksum OK'
} else {
  Write-Host 'WARN no .sha256 beside backup'
}

if (-not $Database) {
  $base = Split-Path $SqlFile -Leaf
  if ($base -match '^(geek_[a-z_]+)-') { $Database = $Matches[1] }
  else { throw 'cannot infer database; pass -Database' }
}

$rootPwd = $env:MYSQL_ROOT_PASSWORD
if (-not $rootPwd -and (Test-Path (Join-Path $Root '.env'))) {
  Get-Content (Join-Path $Root '.env') | ForEach-Object {
    if ($_ -match '^MYSQL_ROOT_PASSWORD=(.+)$') { $rootPwd = $Matches[1].Trim() }
  }
}
if (-not $rootPwd) { $rootPwd = 'change-root-password' }

$compose = Join-Path $Root 'docker-compose.yml'
Write-Host "restore $SqlFile -> $Database"
$inner = "mysql -uroot -p$rootPwd $Database"
cmd /c "docker compose -f `"$compose`" exec -T mysql sh -c `"$inner`" < `"$SqlFile`""
if ($LASTEXITCODE -ne 0) { throw "mysql restore failed exit=$LASTEXITCODE" }
Write-Host "Restored $Database"
