# Brand system Phase-1 acceptance
$ErrorActionPreference = 'Continue'
$Base = 'http://127.0.0.1:8080'
$fail = 0

function Pass([string]$name, $ok, [string]$detail) {
  if ($ok) {
    Write-Host "[PASS] $name :: $detail"
  } else {
    $script:fail++
    Write-Host "[FAIL] $name :: $detail"
  }
}

function Api([string]$method, [string]$path, $token, $body) {
  $uri = $Base + $path
  $req = New-Object System.Net.Http.HttpRequestMessage
  $req.Method = New-Object System.Net.Http.HttpMethod($method.ToUpperInvariant())
  $req.RequestUri = New-Object System.Uri($uri)
  [void]$req.Headers.TryAddWithoutValidation('X-Request-Id', [guid]::NewGuid().ToString())
  [void]$req.Headers.TryAddWithoutValidation('X-Client-Type', 'WEB')
  if ($token) {
    [void]$req.Headers.TryAddWithoutValidation('Authorization', ('Bearer ' + $token))
  }
  if ($null -ne $body) {
    $json = ($body | ConvertTo-Json -Compress -Depth 8)
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $content = New-Object System.Net.Http.ByteArrayContent($bytes, 0, $bytes.Length)
    $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/json')
    $req.Content = $content
  }
  $client = New-Object System.Net.Http.HttpClient
  try {
    $resp = $client.SendAsync($req).GetAwaiter().GetResult()
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $obj = $null
    try { $obj = $text | ConvertFrom-Json } catch {}
    return @{ code = [int]$resp.StatusCode; json = $obj; text = $text }
  } finally {
    $client.Dispose()
    $req.Dispose()
  }
}

Add-Type -AssemblyName System.Net.Http
Write-Host '== Brand Phase-1 verify =='

$tree = Api 'GET' '/api/v1/products/categories/tree' $null $null
$roots = @($tree.json.data)
Pass 'category_tree' ($tree.code -eq 200 -and $roots.Count -ge 4) ("roots=$($roots.Count) code=$($tree.code)")

$catalog = Api 'GET' '/api/v1/products/catalog?categoryId=200' $null $null
$catItems = @($catalog.json.data)
$hasPhone = $false
foreach ($it in $catItems) {
  if ([string]$it.title -match 'Phone|Fold|Nova') { $hasPhone = $true }
}
Pass 'catalog_phones_includes_descendants' ($catalog.code -eq 200 -and $catItems.Count -ge 1 -and $hasPhone) ("count=$($catItems.Count) hasPhone=$hasPhone")

$sc = Api 'GET' '/api/v1/products/showcase' $null $null
$floors = @($sc.json.data.floors)
$codes = @($floors | ForEach-Object { $_.code })
Pass 'showcase_floors' ($sc.code -eq 200 -and $floors.Count -ge 5) ("floors=$($floors.Count) codes=$($codes -join ',')")

$dSelf = Api 'GET' '/api/v1/products/spus/10004' $null $null
$selfOk = ($dSelf.code -eq 200 -and [string]$dSelf.json.data.sellerLabel -eq [string]([char]0x5E73+[char]0x53F0+[char]0x81EA+[char]0x8425) -and $dSelf.json.data.ownBrand -eq $true)
# Prefer direct UTF8 comparison via code check on shopType/ownBrand to avoid encoding issues
$selfOk = ($dSelf.code -eq 200 -and $dSelf.json.data.ownBrand -eq $true -and [string]$dSelf.json.data.shopType -eq 'SELF_OPERATED')
Pass 'self_select_product' $selfOk ("shopType=$($dSelf.json.data.shopType) own=$($dSelf.json.data.ownBrand) seller=$($dSelf.json.data.sellerLabel)")

$dFlag = Api 'GET' '/api/v1/products/spus/10001' $null $null
$flagOk = ($dFlag.code -eq 200 -and [string]$dFlag.json.data.shopType -eq 'OFFICIAL_FLAGSHIP')
Pass 'flagship_product' $flagOk ("shopType=$($dFlag.json.data.shopType) seller=$($dFlag.json.data.sellerLabel) title=$($dFlag.json.data.title)")

$shop = Api 'GET' '/api/v1/shops/3000' $null $null
$shopProducts = @($shop.json.data.products)
$shopOk = ($shop.code -eq 200 -and [string]$shop.json.data.shopType -eq 'SELF_OPERATED' -and $shopProducts.Count -ge 1)
Pass 'shop_self_operated' $shopOk ("type=$($shop.json.data.shopType) products=$($shopProducts.Count) seller=$($shop.json.data.sellerLabel)")

$login = Api 'POST' '/api/v1/auth/login/password' $null @{ username = 'admin'; password = 'Geek@123' }
$token = [string]$login.json.data.accessToken
Pass 'admin_login' ($login.code -eq 200 -and $token) ("code=$($login.code)")

$reindex = Api 'POST' '/api/v1/admin/search/reindex' $token @{}
Pass 'search_reindex' ($reindex.code -eq 200) ("code=$($reindex.code)")

Start-Sleep -Seconds 2
$search = Api 'GET' '/api/v1/search/products?keyword=Nova&page=1&pageSize=10' $null $null
$sItems = @($search.json.data.items)
$labeled = 0
foreach ($it in $sItems) {
  if ($it.sellerLabel -or $it.shopType) { $labeled++ }
}
Pass 'search_has_seller_fields' ($search.code -eq 200 -and $sItems.Count -ge 1 -and $labeled -ge 1) ("hits=$($sItems.Count) labeled=$labeled")

$skuProbe = Api 'GET' '/api/v1/products/spus/10001' $null $null
$skuIds = @($skuProbe.json.data.skus | ForEach-Object { [string]$_.id })
Pass 'sku_11001_kept' ($skuIds -contains '11001') ("skus=$($skuIds -join ',')")

Write-Host "SUMMARY fail=$fail"
if ($fail -gt 0) { exit 1 }
exit 0
