param([string]$OutputJson)

# Concurrency: same Idempotency-Key + checkoutToken yields one order; order object authz;
# inventory/payment concurrency guarantees are verified by their real Maven tests.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$Root = Split-Path -Parent $PSScriptRoot
$Base = if ($env:E2E_BASE_URL) { $env:E2E_BASE_URL.TrimEnd('/') } else { 'http://127.0.0.1:8080' }
if (-not $OutputJson) { $OutputJson = Join-Path $Root 'reports\concurrency-verify-result.json' }
$results = @()

function Record-Result($name, $ok, $detail) {
  $script:results += [pscustomobject]@{ item = $name; pass = [bool]$ok; detail = $detail }
  Write-Host ("[{0}] {1} :: {2}" -f ($(if ($ok) { 'PASS' } else { 'FAIL' })), $name, $detail)
}

function Complete-Run([int]$exitCode) {
  $fail = @($script:results | Where-Object { -not $_.pass }).Count
  $passN = @($script:results | Where-Object { $_.pass }).Count
  $payload = [ordered]@{
    script = 'concurrency-verify.ps1'
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    summary = [ordered]@{ passed = $passN; failed = $fail; total = $script:results.Count }
    results = @($script:results)
  }
  $parent = Split-Path -Parent $OutputJson
  if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
  $payload | ConvertTo-Json -Depth 6 | Set-Content -Path $OutputJson -Encoding UTF8
  Write-Host "SUMMARY pass=$passN fail=$fail total=$($script:results.Count)"
  $script:results | Format-Table -AutoSize
  Write-Host "JSON $OutputJson"
  exit $exitCode
}

function Run-MavenTest($module, $testClass, $resultName) {
  $maven = Get-Command mvn.cmd, mvn -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $maven) {
    Record-Result $resultName $false "Maven executable not found; $testClass was not run"
    return
  }
  Push-Location (Join-Path $Root 'backend')
  try {
    & $maven.Path -B -ntp -pl $module -am "-Dtest=$testClass" '-Dsurefire.failIfNoSpecifiedTests=false' test
    $code = $LASTEXITCODE
  } finally {
    Pop-Location
  }
  Record-Result $resultName ($code -eq 0) "mavenExitCode=$code module=$module test=$testClass"
}

trap {
  Record-Result 'script_unhandled_error' $false $_.Exception.Message
  Complete-Run 1
}

# Run backend guarantees independently of API-stack availability.
Run-MavenTest 'geek-inventory-fulfillment' 'InventoryReservationConcurrencyTest' 'inventory_concurrency_unit'
Run-MavenTest 'geek-trade' 'PaymentCallbackIdempotencyTest' 'payment_callback_idempotent_unit'

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
Record-Result 'checkout_ready' ($preview.code -eq 200 -and $checkoutToken) ("code=$($preview.code) addressId=$addressId biz=$($preview.json.code)")

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
$allFiveReplayed = ($outs.Count -eq 5 -and $okCodes -eq 5 -and $orderNos.Count -eq 5 -and $unique -eq 1)
Record-Result 'checkout_token_idempotent' $allFiveReplayed ("ok=$okCodes orderNos=$($orderNos.Count) uniqueOrders=$unique total=$($outs.Count) expected=5")

$other = Invoke 'GET' '/api/v1/trade/orders/GM-NOT-OWNED-999' $token $null $null
$denied = ($other.code -eq 404 -or $other.code -eq 403 -or ($other.json.code -and $other.json.code -ne '0'))
Record-Result 'order_object_authz' $denied ("code=$($other.code) biz=$($other.json.code)")

$fail = @($results | Where-Object { -not $_.pass }).Count
Complete-Run ($(if ($fail -gt 0) { 1 } else { 0 }))
