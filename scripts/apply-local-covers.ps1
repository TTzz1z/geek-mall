# Point product/shop/brand cover URLs to mall-web static JPEG covers (relative /covers/* by default).
# Override with ASSET_BASE_URL (e.g. http://127.0.0.1:8088) when a device needs absolute URLs.
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Base = if ($env:ASSET_BASE_URL) { $env:ASSET_BASE_URL.TrimEnd('/') } else { '' }

$User = if ($env:MYSQL_USER) { $env:MYSQL_USER } else { 'geek' }
$Pass = if ($env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD } else { 'geek_local_password' }

$coverDir = Join-Path $Root 'frontend\apps\mall-web\public\covers'
if (-not (Test-Path $coverDir)) { throw "Missing covers dir: $coverDir" }

function CoverUrl([string]$name) {
  if ($Base) { return "$Base/covers/$name" }
  return "/covers/$name"
}

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add('USE geek_merchant_product;')
Get-ChildItem $coverDir -Filter 'geek-spu-*.jpg' | ForEach-Object {
  if ($_.BaseName -match 'geek-spu-(\d+)$') {
    $id = $Matches[1]
    $url = CoverUrl $_.Name
    $lines.Add("UPDATE product_spu SET cover_url='$url' WHERE id=$id;")
  }
}
$lines.Add("UPDATE shop SET logo_url='$(CoverUrl 'geek-spu-10003.jpg')' WHERE id=3000;")
$lines.Add("UPDATE shop SET logo_url='$(CoverUrl 'geek-spu-10001.jpg')' WHERE id=3001;")
$lines.Add("UPDATE shop SET logo_url='$(CoverUrl 'geek-spu-10002.jpg')' WHERE id=3002;")
$lines.Add("UPDATE shop SET logo_url='$(CoverUrl 'geek-spu-10007.jpg')' WHERE id=3003;")
$lines.Add("UPDATE product_brand SET logo_url='$(CoverUrl 'geek-spu-10004.jpg')' WHERE id=500;")
$lines.Add("UPDATE product_brand SET logo_url='$(CoverUrl 'geek-spu-10008.jpg')' WHERE id=501;")
$lines.Add("UPDATE product_brand SET logo_url='$(CoverUrl 'geek-spu-10003.jpg')' WHERE id=502;")
$lines.Add("UPDATE product_brand SET logo_url='$(CoverUrl 'geek-spu-10002.jpg')' WHERE id=503;")
$lines.Add("UPDATE product_brand SET logo_url='$(CoverUrl 'geek-spu-10009.jpg')' WHERE id=504;")
$lines.Add("UPDATE product_brand SET logo_url='$(CoverUrl 'geek-spu-10012.jpg')' WHERE id=505;")

$sql = ($lines -join "`n")
$composeFile = Join-Path $Root 'docker-compose.yml'
$sql | docker compose -f $composeFile exec -T mysql mysql "-u$User" "-p$Pass"
if ($LASTEXITCODE -ne 0) { throw "mysql update failed exit=$LASTEXITCODE" }
Write-Host "OK covers base=$(if ($Base) { $Base } else { '(relative /covers)' })"
Write-Host 'Tip: for WeChat device preview set ASSET_BASE_URL to a LAN-reachable host.'
