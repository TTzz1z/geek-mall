# Brand Phase-3: governance (auth / self-ops / flagship / violation / knowledge)
$ErrorActionPreference = 'Continue'
$Base = 'http://127.0.0.1:8080'
$AiBase = 'http://127.0.0.1:9011'
$fail = 0

function Pass([string]$name, $ok, [string]$detail) {
  if ($ok) { Write-Host "[PASS] $name :: $detail" }
  else { $script:fail++; Write-Host "[FAIL] $name :: $detail" }
}

function Api([string]$method, [string]$path, $token, $body, [string]$base = $Base) {
  $uri = $base + $path
  $req = New-Object System.Net.Http.HttpRequestMessage
  $req.Method = New-Object System.Net.Http.HttpMethod($method.ToUpperInvariant())
  $req.RequestUri = New-Object System.Uri($uri)
  [void]$req.Headers.TryAddWithoutValidation('X-Request-Id', [guid]::NewGuid().ToString())
  [void]$req.Headers.TryAddWithoutValidation('X-Client-Type', 'WEB')
  if ($token) { [void]$req.Headers.TryAddWithoutValidation('Authorization', ('Bearer ' + $token)) }
  if ($null -ne $body) {
    $json = ($body | ConvertTo-Json -Compress -Depth 12)
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
  } finally { $client.Dispose(); $req.Dispose() }
}

Add-Type -AssemblyName System.Net.Http
Write-Host '== Brand Phase-3 verify =='

$adminLogin = Api 'POST' '/api/v1/auth/login/password' $null @{ username = 'admin'; password = 'Geek@123' }
$adminToken = [string]$adminLogin.json.data.accessToken
Pass 'admin_login' ($adminLogin.code -eq 200 -and $adminToken) ("code=$($adminLogin.code)")

$paLogin = Api 'POST' '/api/v1/auth/login/password' $null @{ username = 'product_admin'; password = 'Geek@123' }
$paToken = [string]$paLogin.json.data.accessToken
$paMid = $paLogin.json.data.principal.merchantId
$paRoles = @($paLogin.json.data.principal.roles)
Pass 'product_admin_self_ops_scope' ($paLogin.code -eq 200 -and $paMid -eq 2000 -and ($paRoles -contains 'PRODUCT_ADMIN')) ("merchantId=$paMid roles=$($paRoles -join ',')")

$maLogin = Api 'POST' '/api/v1/auth/login/password' $null @{ username = 'merchant_b'; password = 'Geek@123' }
$maToken = [string]$maLogin.json.data.accessToken
Pass 'merchant_b_login' ($maLogin.code -eq 200 -and $maToken) ("code=$($maLogin.code)")

$auths = Api 'GET' '/api/v1/admin/merchants/2002/category-auths' $adminToken $null
$catIds = @($auths.json.data | ForEach-Object { [int]$_.categoryId })
Pass 'auth_seed_no_phone_for_2002' ($auths.code -eq 200 -and -not ($catIds -contains 200)) ("cats=$($catIds -join ',')")

$deny = Api 'POST' '/api/v1/merchant/products/spus' $maToken @{
  title = 'Unauthorized Phone'
  subtitle = 'should fail auth'
  categoryId = 200
  brandId = 501
  coverUrl = 'https://example.com/x.jpg'
  descriptionHtml = '<p>x</p>'
  packageList = 'x'
  serviceDesc = 'x'
  skus = @(@{ skuCode = ("DENY-" + (Get-Date -Format 'HHmmss')); barcode = ''; salePrice = 99; marketPrice = 129; costPrice = 40; weight = 100; volume = 100; attributes = @() })
  attributes = @()
}
$denyBiz = [string]$deny.json.code
Pass 'auth_deny_create_phone_nova' (($deny.code -eq 400 -or $deny.code -eq 403 -or $denyBiz -match 'PD403') -and $denyBiz -match 'PD403') ("http=$($deny.code) biz=$denyBiz msg=$($deny.json.message)")

$selfCreate = Api 'POST' '/api/v1/merchant/products/spus' $paToken @{
  title = 'GEEK SELECT Phase3 Cable'
  subtitle = 'self-ops demo'
  categoryId = 410
  brandId = 500
  coverUrl = 'https://images.unsplash.com/photo-1583394838336-acd977736f90?w=800'
  descriptionHtml = '<p>phase3</p>'
  packageList = 'cable'
  serviceDesc = '1y'
  skus = @(@{ skuCode = ("P3-" + (Get-Date -Format 'HHmmssfff')); barcode = ''; salePrice = 79; marketPrice = 99; costPrice = 30; weight = 80; volume = 50; attributes = @() })
  attributes = @()
}
$selfId = [long]$selfCreate.json.data.id
Pass 'self_ops_create' ($selfCreate.code -eq 200 -and $selfId -gt 0) ("spu=$selfId code=$($selfCreate.code) msg=$($selfCreate.json.message)")

$submit = Api 'POST' "/api/v1/merchant/products/spus/$selfId/submit" $paToken $null
Pass 'self_ops_submit' ($submit.code -eq 200) ("code=$($submit.code)")

$approve = Api 'POST' "/api/v1/admin/products/spus/$selfId/approve" $adminToken @{ reason = 'phase3 ok' }
Pass 'self_ops_approve' ($approve.code -eq 200) ("code=$($approve.code)")

$onSale = Api 'POST' "/api/v1/merchant/products/spus/$selfId/on-sale" $paToken $null
Pass 'self_ops_on_sale' ($onSale.code -eq 200) ("code=$($onSale.code)")

Start-Sleep -Seconds 2
$kb = Api 'GET' "/api/v1/admin/ai/knowledge/products/$selfId/status" $adminToken $null $AiBase
Pass 'knowledge_synced_on_sale' ($kb.code -eq 200 -and [int]$kb.json.data.activeDocs -ge 1) ("http=$($kb.code) docs=$($kb.json.data.activeDocs)")

$certs = Api 'GET' '/api/v1/admin/shop/flagship-certs?status=PENDING' $adminToken $null
$pending = @($certs.json.data)
$pendingId = if ($pending.Count -gt 0) { [long]$pending[0].id } else { 0 }
Pass 'flagship_pending_exists' ($certs.code -eq 200 -and $pendingId -gt 0) ("pending=$pendingId count=$($pending.Count)")

$shopBefore = Api 'GET' '/api/v1/shops/3002' $null $null
$shopTypeBefore = [string]$shopBefore.json.data.shopType
$approveCert = Api 'POST' "/api/v1/admin/shop/flagship-certs/$pendingId/approve" $adminToken $null
$shopAfter = Api 'GET' '/api/v1/shops/3002' $null $null
Pass 'flagship_approve' ($approveCert.code -eq 200 -and [string]$shopAfter.json.data.shopType -eq 'OFFICIAL_FLAGSHIP' -and [string]$shopAfter.json.data.certStatus -eq 'APPROVED') ("before=$shopTypeBefore after=$($shopAfter.json.data.shopType) cert=$($shopAfter.json.data.certStatus)")

$viol = Api 'POST' '/api/v1/admin/products/spus/10009/violation-off-sale' $adminToken @{ reason = 'phase3 demo violation' }
$pub = Api 'GET' '/api/v1/products/spus/10009' $null $null
Pass 'violation_off_sale_hidden' ($viol.code -eq 200 -and ($pub.code -eq 404 -or [string]$pub.json.code -match 'PD404')) ("viol=$($viol.code) pub=$($pub.code)/$($pub.json.code)")

$merchantOn = Api 'POST' '/api/v1/merchant/products/spus/10009/on-sale' $maToken $null
Pass 'violation_blocks_merchant_on_sale' ($merchantOn.code -ne 200 -or [string]$merchantOn.json.code -match 'PD409') ("http=$($merchantOn.code) biz=$($merchantOn.json.code)")

$clear = Api 'POST' '/api/v1/admin/products/spus/10009/clear-violation' $adminToken $null
$restore = Api 'POST' '/api/v1/merchant/products/spus/10009/on-sale' $maToken $null
Pass 'clear_violation_then_on_sale' ($clear.code -eq 200 -and $restore.code -eq 200) ("clear=$($clear.code) on=$($restore.code)")

$off = Api 'POST' "/api/v1/merchant/products/spus/$selfId/off-sale" $paToken $null
Start-Sleep -Seconds 1
$kb2 = Api 'GET' "/api/v1/admin/ai/knowledge/products/$selfId/status" $adminToken $null $AiBase
Pass 'knowledge_deactivated_off_sale' ($off.code -eq 200 -and [int]$kb2.json.data.activeDocs -eq 0) ("off=$($off.code) docs=$($kb2.json.data.activeDocs)")

Write-Host "SUMMARY fail=$fail"
if ($fail -gt 0) { exit 1 }
exit 0
