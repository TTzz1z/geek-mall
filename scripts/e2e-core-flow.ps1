# Core trade E2E (API): login -> search -> cart -> checkout -> order -> pay -> detail -> AI
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$Base = 'http://127.0.0.1:8080'
$results = @()

function Pass($name, $ok, $detail) {
  $script:results += [pscustomobject]@{ item = $name; pass = [bool]$ok; detail = $detail }
  Write-Host ("[{0}] {1} :: {2}" -f ($(if ($ok) { 'PASS' } else { 'FAIL' })), $name, $detail)
}

function Invoke($method, $path, $token, $body, $extraHeaders) {
  $c = [System.Net.Http.HttpClient]::new()
  try {
    $c.Timeout = [TimeSpan]::FromSeconds(90)
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($method), "$Base$path")
    [void]$req.Headers.TryAddWithoutValidation('X-Request-Id', [guid]::NewGuid().ToString())
    [void]$req.Headers.TryAddWithoutValidation('X-Client-Type', 'WEB')
    if ($token) { [void]$req.Headers.TryAddWithoutValidation('Authorization', "Bearer $token") }
    if ($extraHeaders) { foreach ($k in $extraHeaders.Keys) { [void]$req.Headers.TryAddWithoutValidation($k, [string]$extraHeaders[$k]) } }
    if ($null -ne $body) {
      $req.Content = [System.Net.Http.StringContent]::new(($body | ConvertTo-Json -Compress -Depth 8), [Text.Encoding]::UTF8, 'application/json')
    }
    $resp = $c.SendAsync($req).GetAwaiter().GetResult()
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $jsonObj = $null
    try { $jsonObj = $text | ConvertFrom-Json } catch {}
    return @{ code = [int]$resp.StatusCode; json = $jsonObj; text = $text }
  } finally { $c.Dispose() }
}

$E2eUser = if ($env:E2E_USERNAME) { $env:E2E_USERNAME } else { 'e2e_tester' }
$E2ePass = if ($env:E2E_PASSWORD) { $env:E2E_PASSWORD } else { 'Geek@123' }

$login = Invoke 'POST' '/api/v1/auth/login/password' $null @{ username = $E2eUser; password = $E2ePass } $null
$token = [string]$login.json.data.accessToken
Pass 'login' ($login.code -eq 200 -and $token) ("user=$E2eUser code=$($login.code)")

$search = Invoke 'GET' '/api/v1/search/products?keyword=Air&page=1&pageSize=5' $token $null $null
Pass 'search' ($search.code -eq 200) ("code=$($search.code)")

$cart = Invoke 'POST' '/api/v1/trade/cart/items' $token @{ skuId = 11001; quantity = 1 } $null
Pass 'add_cart' ($cart.code -eq 200) ("code=$($cart.code)")

$addrList = Invoke 'GET' '/api/v1/user/addresses' $token $null $null
$addressId = $null
if ($addrList.json.data) {
  $default = @($addrList.json.data | Where-Object { $_.isDefault -eq $true }) | Select-Object -First 1
  if (-not $default) { $default = @($addrList.json.data) | Select-Object -First 1 }
  if ($default) { $addressId = [long]$default.id }
}
if (-not $addressId) {
  $created = Invoke 'POST' '/api/v1/user/addresses' $token @{
    receiver = 'Demo User'
    mobile = '13800138000'
    regionCode = '310000'
    regionName = 'Shanghai'
    detail = 'Pudong Demo Road 1'
    isDefault = $true
  } $null
  $addressId = [long]$created.json.data.id
}
Pass 'address_ready' ($null -ne $addressId -and $addressId -gt 0) ("addressId=$addressId")

$preview = Invoke 'POST' '/api/v1/trade/checkout/preview' $token @{
  addressId = $addressId
  deliveryMethod = 'EXPRESS'
} $null
$checkoutToken = [string]$preview.json.data.checkoutToken
Pass 'checkout_preview' ($preview.code -eq 200 -and $checkoutToken) ("code=$($preview.code) tokenLen=$($checkoutToken.Length) biz=$($preview.json.code)")

$idem = 'e2e-' + [guid]::NewGuid().ToString('N')
$order = Invoke 'POST' '/api/v1/trade/orders' $token @{ checkoutToken = $checkoutToken } @{ 'Idempotency-Key' = $idem }
$orderNo = [string]$order.json.data.orderNo
Pass 'create_order' ($order.code -eq 200 -and $orderNo) ("code=$($order.code) orderNo=$orderNo biz=$($order.json.code)")

$payOk = $false
$payDetail = 'n/a'
if ($orderNo) {
  $payCreate = Invoke 'POST' '/api/v1/payments' $token @{ orderNo = $orderNo; channel = 'SIMULATED' } $null
  $paymentNo = [string]$payCreate.json.data.paymentNo
  if ($paymentNo) {
    $sim = Invoke 'POST' "/api/v1/payments/$paymentNo/simulate" $token @{} $null
    $payOk = ($payCreate.code -eq 200 -and ($sim.code -eq 200 -or $sim.json.code -eq '0'))
    $payDetail = "create=$($payCreate.code) simulate=$($sim.code) paymentNo=$paymentNo"
  } else {
    $snippet = $payCreate.text
    if ($snippet.Length -gt 160) { $snippet = $snippet.Substring(0, 160) }
    $payDetail = "create=$($payCreate.code) body=$snippet"
  }
}
Pass 'simulate_pay' $payOk $payDetail

$detail = Invoke 'GET' "/api/v1/trade/orders/$orderNo" $token $null $null
Pass 'order_detail_self' ($detail.code -eq 200) ("code=$($detail.code) status=$($detail.json.data.status)")

$ai = Invoke 'POST' '/api/v1/ai/conversations' $token @{ scene = 'GUIDE' } $null
$aiNo = [string]$ai.json.data.conversationNo
$aiMsg = Invoke 'POST' "/api/v1/ai/conversations/$aiNo/messages" $token @{ message = 'budget 8000 office laptop'; scene = 'GUIDE' } $null
Pass 'ai_guide' ($ai.code -eq 200 -and $aiMsg.code -eq 200) ("agent=$($aiMsg.json.data.agent)")

$fail = @($results | Where-Object { -not $_.pass }).Count
$passN = @($results | Where-Object { $_.pass }).Count
Write-Host "SUMMARY pass=$passN fail=$fail total=$($results.Count)"
$results | Format-Table -AutoSize
if ($fail -gt 0) { exit 1 }
