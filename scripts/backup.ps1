param(
  [string]$OutDir = ''
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $Root 'backups' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$dbs = @(
  'geek_system','geek_merchant_product','geek_inventory','geek_search','geek_trade',
  'geek_member','geek_marketing','geek_aftersale','geek_service','geek_settlement','geek_ai'
)

$rootPwd = $env:MYSQL_ROOT_PASSWORD
if (-not $rootPwd -and (Test-Path (Join-Path $Root '.env'))) {
  Get-Content (Join-Path $Root '.env') | ForEach-Object {
    if ($_ -match '^MYSQL_ROOT_PASSWORD=(.+)$') { $rootPwd = $Matches[1].Trim() }
  }
}
if (-not $rootPwd) { $rootPwd = 'change-root-password' }

$compose = Join-Path $Root 'docker-compose.yml'
$manifest = @()
foreach ($db in $dbs) {
  $file = Join-Path $OutDir "$db-$stamp.sql"
  Write-Host "dump $db -> $file"
  # Use cmd redirection to avoid PowerShell UTF-16 corruption of SQL dumps.
  $inner = "mysqldump -uroot -p$rootPwd --single-transaction --routines --triggers --default-character-set=utf8mb4 $db"
  cmd /c "docker compose -f `"$compose`" exec -T mysql sh -c `"$inner`" > `"$file`""
  if ($LASTEXITCODE -ne 0) { throw "mysqldump failed for $db exit=$LASTEXITCODE" }
  if (-not (Test-Path $file) -or (Get-Item $file).Length -lt 20) { throw "backup empty: $db" }
  $hash = (Get-FileHash -Algorithm SHA256 -Path $file).Hash
  Set-Content -Path "$file.sha256" -Value "$hash  $(Split-Path $file -Leaf)" -Encoding ASCII
  $manifest += [pscustomobject]@{ database = $db; file = (Split-Path $file -Leaf); sha256 = $hash; bytes = (Get-Item $file).Length }
}

$manPath = Join-Path $OutDir "manifest-$stamp.json"
$manifest | ConvertTo-Json -Depth 4 | Set-Content $manPath -Encoding UTF8
Write-Host "OK backups stamp=$stamp count=$($manifest.Count)"
Write-Output $stamp
