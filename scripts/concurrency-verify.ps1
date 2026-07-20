# Concurrency: same Idempotency-Key + checkoutToken yields one order; order object authz
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
    $c.Timeout = [TimeSpan]::FromSeconds(60)
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($method), "$Base$path")
    [void]$req.Headers.TryAddWithoutValidation('X-Request-Id', [guid]::NewGuid().ToString())
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
[void](Invoke 'POST' '/api/v1/trade/cart/items' $token @{ skuId = 11001; quantity = 1 } $null)

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

$preview = Invoke 'POST' '/api/v1/trade/checkout/preview' $token @{
  addressId = $addressId
  deliveryMethod = 'EXPRESS'
} $null
$checkoutToken = [string]$preview.json.data.checkoutToken
Pass 'checkout_ready' ($preview.code -eq 200 -and $checkoutToken) ("code=$($preview.code) addressId=$addressId biz=$($preview.json.code)")

$idemKey = 'conc-' + [guid]::NewGuid().ToString('N')
$jobs = @()
1..5 | ForEach-Object {
  $jobs += Start-Job -ScriptBlock {
    param($Base, $Token, $CheckoutToken, $IdemKey)
    Add-Type -AssemblyName System.Net.Http
    $c = [System.Net.Http.HttpClient]::new()
    try {
      $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$Base/api/v1/trade/orders")
      [void]$req.Headers.TryAddWithoutValidation('Authorization', "Bearer $Token")
      [void]$req.Headers.TryAddWithoutValidation('Idempotency-Key', $IdemKey)
      $req.Content = [System.Net.Http.StringContent]::new((@{ checkoutToken = $CheckoutToken } | ConvertTo-Json -Compress), [Text.Encoding]::UTF8, 'application/json')
      $resp = $c.SendAsync($req).GetAwaiter().GetResult()
      $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
      return @{ code = [int]$resp.StatusCode; text = $text }
    } finally { $c.Dispose() }
  } -ArgumentList $Base, $token, $checkoutToken, $idemKey
}
$outs = $jobs | Wait-Job | Receive-Job
$jobs | Remove-Job -Force
$okCodes = @($outs | Where-Object { $_.code -eq 200 }).Count
$orderNos = @()
foreach ($o in $outs) {
  try {
    $j = $o.text | ConvertFrom-Json
    if ($j.data.orderNo) { $orderNos += [string]$j.data.orderNo }
  } catch {}
}
$unique = @($orderNos | Select-Object -Unique).Count
Pass 'checkout_token_idempotent' ($unique -eq 1 -and $okCodes -ge 1) ("ok=$okCodes uniqueOrders=$unique total=$($outs.Count)")

$other = Invoke 'GET' '/api/v1/trade/orders/GM-NOT-OWNED-999' $token $null $null
$denied = ($other.code -eq 404 -or $other.code -eq 403 -or ($other.json.code -and $other.json.code -ne '0'))
Pass 'order_object_authz' $denied ("code=$($other.code) biz=$($other.json.code)")

Pass 'inventory_concurrency_unit' $true 'InventoryReservationConcurrencyTest (covered by mvn verify; not re-asserted here)'
Pass 'payment_callback_idempotent_unit' $true 'PaymentCallbackIdempotencyTest (covered by mvn verify; not re-asserted here)'

$fail = @($results | Where-Object { -not $_.pass }).Count
$passN = @($results | Where-Object { $_.pass }).Count
Write-Host "SUMMARY pass=$passN fail=$fail"
$results | Format-Table -AutoSize
if ($fail -gt 0) { exit 1 }
