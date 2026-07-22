param([string]$OutputJson)

# Core API acceptance: trade -> payment state -> aftersale review -> AI tool/evidence path.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$Root = Split-Path -Parent $PSScriptRoot
$Base = if ($env:E2E_BASE_URL) { $env:E2E_BASE_URL.TrimEnd('/') } else { 'http://127.0.0.1:8080' }
if (-not $OutputJson) { $OutputJson = Join-Path $Root 'reports\e2e-core-flow-result.json' }
$results = @()

function Record-Result($name, $ok, $detail) {
  $script:results += [pscustomobject]@{ item = $name; pass = [bool]$ok; detail = $detail }
  Write-Host ("[{0}] {1} :: {2}" -f ($(if ($ok) { 'PASS' } else { 'FAIL' })), $name, $detail)
}

function Complete-Run([int]$exitCode) {
  $fail = @($script:results | Where-Object { -not $_.pass }).Count
  $passN = @($script:results | Where-Object { $_.pass }).Count
  $payload = [ordered]@{
    script = 'e2e-core-flow.ps1'
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    remoteLlmConfigured = (Test-RemoteLlmConfigured)
    summary = [ordered]@{ passed = $passN; failed = $fail; total = $script:results.Count }
    results = @($script:results)
  }
  $parent = Split-Path -Parent $OutputJson
  if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
  $payload | ConvertTo-Json -Depth 8 | Set-Content -Path $OutputJson -Encoding UTF8
  Write-Host "SUMMARY pass=$passN fail=$fail total=$($script:results.Count)"
  $script:results | Format-Table -AutoSize
  Write-Host "JSON $OutputJson"
  exit $exitCode
}

function Test-RemoteLlmConfigured {
  $candidate = [string]$env:LLM_API_KEY
  $enabled = ([string]$env:LLM_ENABLED).Trim().ToLowerInvariant() -ne 'false'
  if (Test-Path (Join-Path $Root '.env')) {
    $raw = Get-Content (Join-Path $Root '.env') -Raw
    if (-not $candidate) {
      $match = [regex]::Match($raw, '(?m)^\s*LLM_API_KEY\s*=\s*([^\r\n#]+)')
      if ($match.Success) { $candidate = $match.Groups[1].Value.Trim().Trim('"').Trim("'") }
    }
    if (-not $env:LLM_ENABLED) {
      $enabledMatch = [regex]::Match($raw, '(?m)^\s*LLM_ENABLED\s*=\s*([^\r\n#]+)')
      if ($enabledMatch.Success) { $enabled = $enabledMatch.Groups[1].Value.Trim().ToLowerInvariant() -ne 'false' }
    }
  }
  return ($enabled -and -not [string]::IsNullOrWhiteSpace($candidate) -and $candidate -notmatch 'CHANGE_ME|YOUR_|example|placeholder')
}

trap {
  Record-Result 'script_unhandled_error' $false $_.Exception.Message
  Complete-Run 1
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
Record-Result 'login' ($login.code -eq 200 -and $token) ("user=$E2eUser code=$($login.code)")

$search = Invoke 'GET' '/api/v1/search/products?keyword=Air&page=1&pageSize=5' $token $null $null
Record-Result 'search' ($search.code -eq 200) ("code=$($search.code)")

$cart = Invoke 'POST' '/api/v1/trade/cart/items' $token @{ skuId = 11001; quantity = 1 } $null
Record-Result 'add_cart' ($cart.code -eq 200) ("code=$($cart.code)")

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
Record-Result 'address_ready' ($null -ne $addressId -and $addressId -gt 0) ("addressId=$addressId")

$preview = Invoke 'POST' '/api/v1/trade/checkout/preview' $token @{
  addressId = $addressId
  deliveryMethod = 'EXPRESS'
} $null
$checkoutToken = [string]$preview.json.data.checkoutToken
Record-Result 'checkout_preview' ($preview.code -eq 200 -and $checkoutToken) ("code=$($preview.code) tokenLen=$($checkoutToken.Length) biz=$($preview.json.code)")

$idem = 'e2e-' + [guid]::NewGuid().ToString('N')
$order = Invoke 'POST' '/api/v1/trade/orders' $token @{ checkoutToken = $checkoutToken } @{ 'Idempotency-Key' = $idem }
$orderNo = [string]$order.json.data.orderNo
Record-Result 'create_order' ($order.code -eq 200 -and $orderNo) ("code=$($order.code) orderNo=$orderNo biz=$($order.json.code)")

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
Record-Result 'simulate_pay_callback' $payOk $payDetail

$detail = Invoke 'GET' "/api/v1/trade/orders/$orderNo" $token $null $null
$paymentDetail = if ($paymentNo) { Invoke 'GET' "/api/v1/payments/$paymentNo" $token $null $null } else { $null }
$orderStatus = [string]$detail.json.data.status
$paymentStatus = if ($paymentDetail) { [string]$paymentDetail.json.data.status } else { '' }
$inventoryStatus = [string]$detail.json.data.inventoryStatus
$inventoryAccepted = @('DEDUCTED', 'RESERVED') -contains $inventoryStatus
Record-Result 'order_paid' ($detail.code -eq 200 -and $orderStatus -eq 'PAID') ("code=$($detail.code) status=$orderStatus")
Record-Result 'payment_success' ($paymentDetail.code -eq 200 -and $paymentStatus -eq 'SUCCESS') ("code=$($paymentDetail.code) status=$paymentStatus")
Record-Result 'inventory_after_payment' $inventoryAccepted ("status=$inventoryStatus expected=DEDUCTED acceptableCompensation=RESERVED")

# Minimal aftersale loop: paid order item -> user applies -> owning merchant approves -> user observes APPROVED.
$merchantUser = if ($env:E2E_MERCHANT_USERNAME) { $env:E2E_MERCHANT_USERNAME } else { 'merchant_a' }
$merchantPass = if ($env:E2E_MERCHANT_PASSWORD) { $env:E2E_MERCHANT_PASSWORD } else { $E2ePass }
$merchantLogin = Invoke 'POST' '/api/v1/auth/login/password' $null @{ username = $merchantUser; password = $merchantPass } $null
$merchantToken = [string]$merchantLogin.json.data.accessToken
$merchantPrincipalId = [long]$merchantLogin.json.data.principal.merchantId
$subOrder = @($detail.json.data.subOrders) | Select-Object -First 1
$orderItem = @($subOrder.items) | Select-Object -First 1
$merchantId = if ($subOrder) { [long]$subOrder.merchantId } else { 0 }
$subOrderNo = if ($subOrder) { [string]$subOrder.subOrderNo } else { '' }
$orderItemId = if ($orderItem) { [long]$orderItem.id } else { 0 }
Record-Result 'merchant_login_for_aftersale' ($merchantLogin.code -eq 200 -and $merchantToken) ("user=$merchantUser code=$($merchantLogin.code)")

# Merchant order source and tenant boundary: owner sees the real trade sub-order and another merchant cannot.
$merchantOrders = Invoke 'GET' '/api/v1/merchant/orders' $merchantToken $null $null
$ownerRows = @($merchantOrders.json.data | Where-Object { [string]$_.subOrderNo -eq $subOrderNo })
$ownerOrderOk = ($merchantOrders.code -eq 200 -and $merchantPrincipalId -eq $merchantId -and
  $ownerRows.Count -eq 1 -and @($ownerRows[0].items).Count -gt 0)
Record-Result 'merchant_orders_use_real_trade_data' $ownerOrderOk ("code=$($merchantOrders.code) merchantId=$merchantPrincipalId subOrder=$subOrderNo matches=$($ownerRows.Count)")

$otherMerchantUser = if ($merchantUser -eq 'merchant_b') { 'merchant_a' } else { 'merchant_b' }
$otherMerchantLogin = Invoke 'POST' '/api/v1/auth/login/password' $null @{ username = $otherMerchantUser; password = $merchantPass } $null
$otherMerchantToken = [string]$otherMerchantLogin.json.data.accessToken
$otherMerchantId = [long]$otherMerchantLogin.json.data.principal.merchantId
$otherOrders = Invoke 'GET' '/api/v1/merchant/orders' $otherMerchantToken $null $null
$otherHasOrder = @($otherOrders.json.data | Where-Object { [string]$_.subOrderNo -eq $subOrderNo }).Count -gt 0
$listIsolated = ($otherMerchantLogin.code -eq 200 -and $otherOrders.code -eq 200 -and
  $otherMerchantId -ne $merchantId -and -not $otherHasOrder)
Record-Result 'merchant_order_list_tenant_isolation' $listIsolated ("user=$otherMerchantUser merchantId=$otherMerchantId containsTarget=$otherHasOrder")

$crossTenantDetail = Invoke 'GET' "/api/v1/merchant/orders/$subOrderNo" $otherMerchantToken $null $null
Record-Result 'merchant_order_detail_tenant_isolation' ($crossTenantDetail.code -eq 404 -and $crossTenantDetail.json.code -eq 'TR40410') ("http=$($crossTenantDetail.code) biz=$($crossTenantDetail.json.code)")

$aftersaleNo = ''
if ($orderStatus -eq 'PAID' -and $orderItemId -gt 0 -and $merchantId -gt 0) {
  $apply = Invoke 'POST' '/api/v1/aftersales' $token @{
    orderNo = $orderNo
    orderItemId = $orderItemId
    merchantId = $merchantId
    type = 'RETURN_REFUND'
    quantity = 1
    reason = 'API acceptance minimal loop'
  } $null
  $aftersaleNo = [string]$apply.json.data.aftersaleNo
  Record-Result 'aftersale_created' ($apply.code -eq 200 -and $aftersaleNo -and [string]$apply.json.data.status -eq 'APPLIED') ("code=$($apply.code) no=$aftersaleNo status=$($apply.json.data.status)")
} else {
  Record-Result 'aftersale_created' $false "missing paid order item orderStatus=$orderStatus merchantId=$merchantId orderItemId=$orderItemId"
}

if ($aftersaleNo -and $merchantToken) {
  $approve = Invoke 'POST' "/api/v1/merchant/aftersales/$aftersaleNo/approve" $merchantToken $null $null
  $aftersaleDetail = Invoke 'GET' "/api/v1/aftersales/$aftersaleNo" $token $null $null
  $logStates = @($aftersaleDetail.json.data.logs | ForEach-Object { [string]$_.toStatus })
  $stateOk = ($approve.code -eq 200 -and [string]$approve.json.data.status -eq 'APPROVED' -and
    $aftersaleDetail.code -eq 200 -and [string]$aftersaleDetail.json.data.status -eq 'APPROVED' -and
    ($logStates -contains 'APPLIED') -and ($logStates -contains 'APPROVED'))
  Record-Result 'aftersale_merchant_approved' $stateOk ("approve=$($approve.code) detail=$($aftersaleDetail.code) status=$($aftersaleDetail.json.data.status) logs=$($logStates -join ',')")
} else {
  Record-Result 'aftersale_merchant_approved' $false 'aftersale number or merchant token missing'
}

$ai = Invoke 'POST' '/api/v1/ai/conversations' $token @{ scene = 'GUIDE' } $null
$aiNo = [string]$ai.json.data.conversationNo
$aiMsg = Invoke 'POST' "/api/v1/ai/conversations/$aiNo/messages" $token @{ message = 'search products for an office laptop under 8000'; scene = 'GUIDE' } $null
$searchTool = @($aiMsg.json.data.tools | Where-Object { $_.toolName -eq 'search_products' -and $_.status -eq 'SUCCESS' -and $null -ne $_.result }) | Select-Object -First 1
$toolResultOk = ($ai.code -eq 200 -and $aiMsg.code -eq 200 -and $null -ne $searchTool -and $null -ne $searchTool.result)
Record-Result 'ai_tool_result' $toolResultOk ("agent=$($aiMsg.json.data.agent) tool=$($searchTool.toolName) status=$($searchTool.status)")

$remoteLlmConfigured = Test-RemoteLlmConfigured
if ($remoteLlmConfigured) {
  $remoteOk = ($aiMsg.json.data.degraded -eq $false -and $aiMsg.json.data.model.degraded -eq $false -and
    [string]$aiMsg.json.data.plannerMode -eq 'LLM')
  Record-Result 'ai_remote_model' $remoteOk ("remoteConfigured=true model=$($aiMsg.json.data.model.modelName) degraded=$($aiMsg.json.data.degraded) planner=$($aiMsg.json.data.plannerMode)")
} else {
  $fallbackOk = ($aiMsg.json.data.degraded -eq $true -and $aiMsg.json.data.model.degraded -eq $true -and
    [string]$aiMsg.json.data.plannerMode -eq 'RULE' -and [string]$aiMsg.json.data.fallbackReason -eq 'LLM_DISABLED')
  Record-Result 'ai_local_fallback' $fallbackOk ("remoteConfigured=false model=$($aiMsg.json.data.model.modelName) degraded=$($aiMsg.json.data.degraded) planner=$($aiMsg.json.data.plannerMode) reason=$($aiMsg.json.data.fallbackReason)")
}

$knowledge = Invoke 'POST' '/api/v1/ai/conversations' $token @{ scene = 'PRODUCT_KNOWLEDGE' } $null
$knowledgeNo = [string]$knowledge.json.data.conversationNo
$knowledgeMsg = Invoke 'POST' "/api/v1/ai/conversations/$knowledgeNo/messages" $token @{ message = '极客本 Air 14 的内存是多少'; scene = 'PRODUCT_KNOWLEDGE' } $null
$citationCount = @($knowledgeMsg.json.data.citations).Count
Record-Result 'ai_knowledge_citation' ($knowledge.code -eq 200 -and $knowledgeMsg.code -eq 200 -and $citationCount -gt 0 -and $knowledgeMsg.json.data.noEvidence -eq $false) ("citations=$citationCount noEvidence=$($knowledgeMsg.json.data.noEvidence)")

$fail = @($results | Where-Object { -not $_.pass }).Count
Complete-Run ($(if ($fail -gt 0) { 1 } else { 0 }))
