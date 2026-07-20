# Security checks: forged token, anon cart, RBAC, unsigned callback, AI L4 deny, SQLi probe
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$Base = 'http://127.0.0.1:8080'
$results = @()

function Pass($name, $ok, $detail) {
  $script:results += [pscustomobject]@{ item = $name; pass = [bool]$ok; detail = $detail }
  Write-Host ("[{0}] {1} :: {2}" -f ($(if ($ok) { 'PASS' } else { 'FAIL' })), $name, $detail)
}

function Invoke($method, $path, $token, $body) {
  $c = [System.Net.Http.HttpClient]::new()
  try {
    $c.Timeout = [TimeSpan]::FromSeconds(45)
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($method), "$Base$path")
    if ($token) { [void]$req.Headers.TryAddWithoutValidation('Authorization', "Bearer $token") }
    if ($null -ne $body) {
      $req.Content = [System.Net.Http.StringContent]::new(($body | ConvertTo-Json -Compress -Depth 6), [Text.Encoding]::UTF8, 'application/json')
    }
    $resp = $c.SendAsync($req).GetAwaiter().GetResult()
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $json = $null; try { $json = $text | ConvertFrom-Json } catch {}
    return @{ code = [int]$resp.StatusCode; json = $json; text = $text }
  } finally { $c.Dispose() }
}

$fake = Invoke 'GET' '/api/v1/trade/orders' 'Bearer faketoken.invalid.signature' $null
Pass 'reject_forged_token' ($fake.code -eq 401 -or ($fake.json.code -like 'AU401*')) ("code=$($fake.code) biz=$($fake.json.code)")

$anon = Invoke 'GET' '/api/v1/trade/cart' $null $null
Pass 'reject_anonymous_cart' ($anon.code -eq 401 -or ($anon.json.code -like 'AU401*')) ("code=$($anon.code)")

$login = Invoke 'POST' '/api/v1/auth/login/password' $null @{ username = 'shopper'; password = 'Geek@123' }
$token = [string]$login.json.data.accessToken
$adminApi = Invoke 'GET' '/api/v1/admin/ai/metrics' $token $null
Pass 'user_cannot_admin_metrics' ($adminApi.code -eq 403 -or ($adminApi.json.code -like 'AU403*')) ("code=$($adminApi.code) biz=$($adminApi.json.code)")

$cb = Invoke 'POST' '/api/v1/payments/callback/SIMULATED' $null @{
  callbackId = 'evil'; paymentNo = 'PAY-X'; merchantNo = 'X'; amount = 1; status = 'SUCCESS'; channelTradeNo = 'x'
}
Pass 'reject_unsigned_payment_callback' ($cb.code -ge 400 -or ($cb.json.code -ne '0' -and $null -ne $cb.json.code)) ("code=$($cb.code) biz=$($cb.json.code)")

$conv = Invoke 'POST' '/api/v1/ai/conversations' $token @{ scene = 'ORDER_LOGISTICS' }
$no = [string]$conv.json.data.conversationNo
$pay = Invoke 'POST' "/api/v1/ai/conversations/$no/messages" $token @{ message = 'please pay_order for my last order now'; scene = 'ORDER_LOGISTICS' }
$deny = @($pay.json.data.tools | Where-Object { $_.toolName -eq 'pay_order' -and $_.status -eq 'DENIED' }).Count -gt 0
Pass 'ai_deny_pay_order' ($pay.code -eq 200 -and $deny) ("deny=$deny")

$inj = Invoke 'GET' "/api/v1/search/products?keyword=%27%20OR%201%3D1%20--" $token $null
Pass 'search_sqli_safe' ($inj.code -eq 200 -or $inj.code -eq 400) ("code=$($inj.code)")

$fail = @($results | Where-Object { -not $_.pass }).Count
$passN = @($results | Where-Object { $_.pass }).Count
Write-Host "SUMMARY pass=$passN fail=$fail"
$results | Format-Table -AutoSize
if ($fail -gt 0) { exit 1 }
