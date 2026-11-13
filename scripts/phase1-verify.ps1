# Phase-1 trade back-half closed-loop acceptance (real API + MySQL + Redis + MQ)
# Usage: powershell -File scripts/phase1-verify.ps1
$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.Security
$Root = Split-Path -Parent $PSScriptRoot
$Base = 'http://127.0.0.1:8080'
$results = @()
$report = New-Object System.Collections.Generic.List[string]

function Log([string]$msg) {
  Write-Host $msg
  [void]$report.Add($msg)
}

function Pass([string]$name, $ok, [string]$detail) {
  $script:results += [pscustomobject]@{ item = $name; pass = [bool]$ok; detail = $detail }
  $tag = if ($ok) { 'PASS' } else { 'FAIL' }
  Log "[$tag] $name :: $detail"
}

function Format-LongId($value) {
  if ($null -eq $value) { return $null }
  return ([int64]([decimal]"$value")).ToString([cultureinfo]::InvariantCulture)
}

function Invoke-Api([string]$method, [string]$path, $token, $body, $extraHeaders) {
  # HttpClient keeps Authorization on POST+body; Windows PowerShell 5.1 IWR may drop it.
  $uri = $Base + $path
  $req = New-Object System.Net.Http.HttpRequestMessage
  $req.Method = New-Object System.Net.Http.HttpMethod($method.ToUpperInvariant())
  $req.RequestUri = New-Object System.Uri($uri)
  [void]$req.Headers.TryAddWithoutValidation('X-Request-Id', [guid]::NewGuid().ToString())
  [void]$req.Headers.TryAddWithoutValidation('X-Client-Type', 'WEB')
  if ($token) {
    [void]$req.Headers.TryAddWithoutValidation('Authorization', ('Bearer ' + $token))
  }
  if ($extraHeaders) {
    foreach ($k in $extraHeaders.Keys) {
      [void]$req.Headers.TryAddWithoutValidation([string]$k, [string]$extraHeaders[$k])
    }
  }
  if ($null -ne $body) {
    $json = if ($body -is [string]) { [string]$body } else { ($body | ConvertTo-Json -Compress -Depth 12) }
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $content = New-Object System.Net.Http.ByteArrayContent($bytes, 0, $bytes.Length)
    $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/json; charset=utf-8')
    $req.Content = $content
  }
  $client = New-Object System.Net.Http.HttpClient
  $client.Timeout = [TimeSpan]::FromSeconds(90)
  try {
    $resp = $client.SendAsync($req).GetAwaiter().GetResult()
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $jsonObj = $null
    try { $jsonObj = $text | ConvertFrom-Json } catch {}
    return @{ code = [int]$resp.StatusCode; json = $jsonObj; text = $text }
  } catch {
    return @{ code = 0; json = $null; text = [string]$_.Exception.Message }
  } finally {
    $client.Dispose()
    $req.Dispose()
  }
}

function Sql([string]$db, [string]$q) {
  $out = docker exec geek-mall-mysql-1 mysql -ugeek -pgeek_local_password -N -e "USE $db; $q" 2>$null
  return "$out"
}

function Sign-Payment([string]$channel, [string]$callbackId, [string]$paymentNo, [string]$merchantNo, $amount, [string]$status, [string]$channelTradeNo, [string]$secret) {
  $amt = ([decimal]$amount).ToString('0.00', [cultureinfo]::InvariantCulture)
  $canonical = ($channel.ToUpperInvariant() + '|' + $callbackId + '|' + $paymentNo + '|' + $merchantNo + '|' + $amt + '|' + $status + '|' + $channelTradeNo)
  $hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($secret))
  $hash = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))
  return ([BitConverter]::ToString($hash) -replace '-', '').ToLowerInvariant()
}

function Find-CartItemId($cartJson, [long]$skuId) {
  foreach ($g in @($cartJson.data.shops)) {
    foreach ($it in @($g.items)) {
      $sku = $it.sku_id
      if ($null -eq $sku) { $sku = $it.skuId }
      if ([long]$sku -eq $skuId) { return $it.id }
    }
  }
  return $null
}

function Wait-Aftersale([string]$no, [string]$status) {
  for ($attempt = 0; $attempt -lt 30; $attempt++) {
    $current = Invoke-Api 'GET' "/api/v1/aftersales/$no" $token $null $null
    if ($current.json.data.status -eq $status) { return $true }
    Start-Sleep -Seconds 1
  }
  return $false
}

function Wait-Fulfillment([string]$no) {
  for ($attempt = 0; $attempt -lt 20; $attempt++) {
    $list = Invoke-Api 'GET' '/api/v1/merchant/fulfillments' $mtoken $null $null
    foreach ($f in @($list.json.data)) {
      if ($f.orderNo -eq $no) { return $f.fulfillmentNo }
    }
    Start-Sleep -Seconds 1
  }
  return $null
}

function Snip([string]$text, [int]$n = 180) {
  if (-not $text) { return '' }
  if ($text.Length -le $n) { return $text }
  return $text.Substring(0, $n)
}

Log '== Phase1 seed prepare (shopper user_id=4) =='
[void](Sql 'geek_member' "INSERT INTO member_profile(user_id,level_code,growth_value,point_balance,frozen_points,updated_at) VALUES (4,'GOLD',2500,5000,0,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE level_code='GOLD',point_balance=5000,frozen_points=0,updated_at=CURRENT_TIMESTAMP;")
[void](Sql 'geek_member' "INSERT INTO member_user_address(id,user_id,receiver,mobile,region_code,region_name,detail,is_default,created_at,updated_at) VALUES (81004,4,'Geek User','13800000001','310000','Shanghai','Jiading Geek Rd 88',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE user_id=4,is_default=1,detail=VALUES(detail),updated_at=CURRENT_TIMESTAMP;")
[void](Sql 'geek_marketing' "INSERT INTO marketing_user_coupon(id,template_id,user_id,status,received_at) VALUES (71104,71001,4,'UNUSED',CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE status='UNUSED',user_id=4;")
[void](Sql 'geek_marketing' "DELETE FROM marketing_seckill_order WHERE user_id=4;")
[void](Sql 'geek_marketing' "UPDATE marketing_seckill_item SET warm_status='COLD',stock_qty=50 WHERE session_id=72001 AND sku_id=11001;")
[void](Sql 'geek_trade' "UPDATE trade_order SET checkout_token=CONCAT('SECKILL-LEGACY-',order_no) WHERE checkout_token='SECKILL';")
[void](docker exec geek-mall-redis-1 redis-cli SET seckill:stock:72001:11001 50)
[void](docker exec geek-mall-redis-1 redis-cli DEL seckill:user:72001:11001:4)

$login = Invoke-Api 'POST' '/api/v1/auth/login/password' $null @{ username = 'shopper'; password = 'Geek@123' } $null
$token = [string]$login.json.data.accessToken
Pass 'login_shopper' ($login.code -eq 200 -and $token) ("code=$($login.code)")

$mlogin = Invoke-Api 'POST' '/api/v1/auth/login/password' $null @{ username = 'merchant_a'; password = 'Geek@123' } $null
$mtoken = [string]$mlogin.json.data.accessToken
Pass 'login_merchant' ($mlogin.code -eq 200 -and $mtoken) ("code=$($mlogin.code)")

$addrs = Invoke-Api 'GET' '/api/v1/user/addresses' $token $null $null
$addressId = $null
if ($addrs.json.data) { $addressId = @($addrs.json.data)[0].id }
Pass 'seed_address' ($null -ne $addressId) ("addressId=$addressId count=$(@($addrs.json.data).Count)")

# ---- 1) Normal order ----
Log '== 1) Normal: cart -> checkout -> pay -> fulfill -> ship -> logistics =='
$cart = Invoke-Api 'POST' '/api/v1/trade/cart/items' $token @{ skuId = 11001; quantity = 2 } $null
Log ("POST /trade/cart/items => $($cart.code) " + (Snip $cart.text))
$cartList = Invoke-Api 'GET' '/api/v1/trade/cart' $token $null $null
$cartItemId = Find-CartItemId $cartList.json 11001
Pass 'add_cart_qty2' ($cart.code -eq 200 -and $cartItemId) ("cartItemId=$cartItemId")

$previewBody = @{
  cartItemIds = @([long]$cartItemId)
  addressId = [long]$addressId
  receiver = 'Geek User'
  mobile = '13800000001'
  regionCode = '310000'
  addressDetail = 'Jiading Geek Rd 88'
  deliveryMethod = 'STANDARD'
  userCouponId = 71104
  pointsToUse = 100
}
$preview = Invoke-Api 'POST' '/api/v1/trade/checkout/preview' $token $previewBody $null
Log ("POST /checkout/preview => $($preview.code) discount=$($preview.json.data.discountAmount) payable=$($preview.json.data.payableAmount)")
$checkoutToken = [string]$preview.json.data.checkoutToken
Pass 'checkout_with_coupon_points' ($preview.code -eq 200 -and $checkoutToken) ("tokenLen=$($checkoutToken.Length) discount=$($preview.json.data.discountAmount)")

$idem = 'p1-normal-' + [guid]::NewGuid().ToString('N')
$order = Invoke-Api 'POST' '/api/v1/trade/orders' $token @{ checkoutToken = $checkoutToken; addressId = [long]$addressId } @{ 'Idempotency-Key' = $idem }
$orderNo = [string]$order.json.data.orderNo
Log ("POST /trade/orders => $($order.code) orderNo=$orderNo")
Pass 'create_normal_order' ($order.code -eq 200 -and $orderNo) ("orderNo=$orderNo")

$payCreate = Invoke-Api 'POST' '/api/v1/payments' $token @{ orderNo = $orderNo; channel = 'SIMULATED' } $null
$paymentNo = [string]$payCreate.json.data.paymentNo
$sim = Invoke-Api 'POST' ("/api/v1/payments/$paymentNo/simulate") $token @{} $null
Log ("simulate pay => create=$($payCreate.code) sim=$($sim.code) paymentNo=$paymentNo")
Pass 'simulate_pay_normal' ($payCreate.code -eq 200 -and $sim.code -eq 200) ("paymentNo=$paymentNo")

$secret = 'change-payment-callback-secret'
$payRow = (Sql 'geek_trade' "SELECT amount FROM trade_payment WHERE payment_no='$paymentNo' LIMIT 1;")
$amount = "$payRow".Trim()
$callbackId = 'dup-cb-' + [guid]::NewGuid().ToString('N')
$channelTradeNo = 'MOCK-DUP-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$sig = Sign-Payment 'SIMULATED' $callbackId $paymentNo 'GEEK-MALL' $amount 'SUCCESS' $channelTradeNo $secret
$cbBody = @{
  callbackId = $callbackId
  paymentNo = $paymentNo
  merchantNo = 'GEEK-MALL'
  amount = [decimal]$amount
  status = 'SUCCESS'
  channelTradeNo = $channelTradeNo
}
$cb1 = Invoke-Api 'POST' '/api/v1/payments/callback/SIMULATED' $null $cbBody @{ 'X-Payment-Signature' = $sig }
$cb2 = Invoke-Api 'POST' '/api/v1/payments/callback/SIMULATED' $null $cbBody @{ 'X-Payment-Signature' = $sig }
Log ("callback1=$($cb1.code) " + (Snip $cb1.text 120))
Log ("callback2=$($cb2.code) idempotentReplay=$($cb2.json.data.idempotentReplay)")
$cbOk = ($cb1.code -eq 200 -and $cb2.code -eq 200 -and [bool]$cb2.json.data.idempotentReplay)
Pass 'payment_callback_idempotent' $cbOk ("cb1=$($cb1.code) cb2=$($cb2.code) replay=$($cb2.json.data.idempotentReplay)")

Start-Sleep -Seconds 2
$detail = Invoke-Api 'GET' ("/api/v1/trade/orders/$orderNo") $token $null $null
$item = @($detail.json.data.subOrders)[0].items[0]
$orderItemId = $item.id
Pass 'order_paid' ($detail.json.data.status -eq 'PAID') ("status=$($detail.json.data.status) inventory=$($detail.json.data.inventoryStatus) itemId=$orderItemId")

# refresh merchant token before warehouse APIs
$mlogin = Invoke-Api 'POST' '/api/v1/auth/login/password' $null @{ username = 'merchant_a'; password = 'Geek@123' } $null
$mtoken = [string]$mlogin.json.data.accessToken

$fulfillmentNo = Wait-Fulfillment $orderNo
Pass 'fulfillment_created' ($null -ne $fulfillmentNo) ("fulfillmentNo=$fulfillmentNo")

$ffDetail = Invoke-Api 'GET' ("/api/v1/merchant/fulfillments/$fulfillmentNo") $mtoken $null $null
$shipLineItemId = Format-LongId (@($ffDetail.json.data.items)[0].orderItemId)
function Merchant-Ship([string]$fno, [string]$itemId, [string]$tracking, [string]$authToken) {
  $body = '{"carrierCode":"SF","trackingNo":"' + $tracking + '","lines":[{"orderItemId":' + $itemId + ',"quantity":1}]}'
  return Invoke-Api 'POST' ("/api/v1/merchant/fulfillments/$fno/ship") $authToken $body $null
}
$ship1 = Merchant-Ship $fulfillmentNo $shipLineItemId ('SF-P1-001-' + [guid]::NewGuid().ToString('N').Substring(0, 8)) $mtoken
$packageNo = [string]$ship1.json.data.packageNo
Log ("ship partial => $($ship1.code) " + (Snip $ship1.text 160) + " packageNo=$packageNo status=$($ship1.json.data.status)")
Pass 'partial_ship' ($ship1.code -eq 200 -and ($ship1.json.data.status -eq 'PARTIAL_SHIPPED' -or $ship1.json.data.status -eq 'SHIPPED')) ("packageNo=$packageNo status=$($ship1.json.data.status)")

$ship2 = Merchant-Ship $fulfillmentNo $shipLineItemId ('SF-P1-002-' + [guid]::NewGuid().ToString('N').Substring(0, 8)) $mtoken
Pass 'ship_remaining' ($ship2.code -eq 200 -and ($ship2.json.data.status -eq 'SHIPPED' -or $ship2.json.data.status -eq 'PARTIAL_SHIPPED')) ("status=$($ship2.json.data.status)")

$shipDup = Merchant-Ship $fulfillmentNo $shipLineItemId ('SF-P1-DUP-' + [guid]::NewGuid().ToString('N').Substring(0, 8)) $mtoken
$shipDupBlocked = ($shipDup.code -ge 400) -and ($shipDup.json.code -match '^FL409')
Pass 'ship_duplicate_rejected' $shipDupBlocked ("code=$($shipDup.code) biz=$($shipDup.json.code)")

$pkg = Invoke-Api 'GET' ("/api/v1/logistics/packages/$packageNo") $token $null $null
$trackCount = 0
if ($pkg.json.data -and $pkg.json.data.tracks) { $trackCount = @($pkg.json.data.tracks).Count }
Pass 'logistics_tracks' ($pkg.code -eq 200 -and $trackCount -ge 1) ("packageNo=$packageNo tracks=$trackCount")

# ---- 2) Seckill ----
Log '== 2) Seckill: Lua -> MQ -> order -> pay -> ship =='
$sk = Invoke-Api 'POST' '/api/v1/marketing/seckill/11001/orders' $token @{
  sessionId = 72001
  quantity = 1
  addressId = [long]$addressId
} @{ 'Idempotency-Key' = ('sk-' + [guid]::NewGuid().ToString('N')) }
Log ("seckill order => $($sk.code) " + (Snip $sk.text 200))
Pass 'seckill_queued' ($sk.code -eq 200 -and $sk.json.data.status -eq 'QUEUED') ("seckillOrderId=$($sk.json.data.seckillOrderId)")

$skOrderNo = $null
$skId = $sk.json.data.seckillOrderId
for ($i = 0; $i -lt 40; $i++) {
  Start-Sleep -Seconds 1
  $row = (Sql 'geek_marketing' "SELECT CONCAT(status,'|',IFNULL(trade_order_no,'')) FROM marketing_seckill_order WHERE id=$skId;").Trim()
  if ($i -eq 0 -or ($i % 5 -eq 0) -or ($row -match 'CREATED')) { Log ("seckill poll $i => $row") }
  if ($row -match '^CREATED\|(SK\d+)') {
    $skOrderNo = $Matches[1]
    break
  }
}
if (-not $skOrderNo) {
  $row = (Sql 'geek_marketing' "SELECT CONCAT(status,'|',IFNULL(trade_order_no,'')) FROM marketing_seckill_order WHERE id=$skId;").Trim()
  if ($row -match 'CREATED\|(SK\d+)') { $skOrderNo = $Matches[1] }
}
Pass 'seckill_mq_create' ($null -ne $skOrderNo -and $skOrderNo -like 'SK*') ("tradeOrderNo=$skOrderNo")

if ($skOrderNo) {
  $skPay = Invoke-Api 'POST' '/api/v1/payments' $token @{ orderNo = $skOrderNo; channel = 'SIMULATED' } $null
  $skPaymentNo = [string]$skPay.json.data.paymentNo
  $skSim = Invoke-Api 'POST' ("/api/v1/payments/$skPaymentNo/simulate") $token @{} $null
  Pass 'seckill_pay' ($skPay.code -eq 200 -and $skSim.code -eq 200) ("paymentNo=$skPaymentNo")
  $skFNo = Wait-Fulfillment $skOrderNo
  if ($skFNo) {
    $skFd = Invoke-Api 'GET' ("/api/v1/merchant/fulfillments/$skFNo") $mtoken $null $null
    $skItemId = Format-LongId (@($skFd.json.data.items)[0].orderItemId)
    $skShipBody = '{"carrierCode":"YT","trackingNo":"YT-SK-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '","lines":[{"orderItemId":' + $skItemId + ',"quantity":1}]}'
    $skShip = Invoke-Api 'POST' ("/api/v1/merchant/fulfillments/$skFNo/ship") $mtoken $skShipBody $null
    Pass 'seckill_ship' ($skShip.code -eq 200) ("fulfillmentNo=$skFNo status=$($skShip.json.data.status) http=$($skShip.code)")
  } else {
    Pass 'seckill_ship' $false 'fulfillment missing'
  }
} else {
  Pass 'seckill_pay' $false 'no order'
  Pass 'seckill_ship' $false 'no order'
}

# ---- 3) REFUND_ONLY ----
Log '== 3) REFUND_ONLY =='
$refundTargetNo = $skOrderNo
if (-not $refundTargetNo) { $refundTargetNo = $orderNo }
$rd = Invoke-Api 'GET' ("/api/v1/trade/orders/$refundTargetNo") $token $null $null
$rItem = @($rd.json.data.subOrders)[0].items[0]
$rMerchant = @($rd.json.data.subOrders)[0].merchantId
$half = [math]::Round(([decimal]$rItem.payableAmount) / 2, 2)
$as1 = Invoke-Api 'POST' '/api/v1/aftersales' $token @{
  orderNo = $refundTargetNo
  orderItemId = [long]$rItem.id
  merchantId = [long]$rMerchant
  type = 'REFUND_ONLY'
  quantity = 1
  reason = 'phase1-refund-only'
  amount = $half
} $null
$as1No = [string]$as1.json.data.aftersaleNo
Log ("apply REFUND_ONLY => $($as1.code) aftersaleNo=$as1No")
Pass 'apply_refund_only' ($as1.code -eq 200 -and $as1No) ("aftersaleNo=$as1No")

$ap1 = Invoke-Api 'POST' ("/api/v1/merchant/aftersales/$as1No/approve?amount=$half") $mtoken $null $null
Log ("approve REFUND_ONLY => $($ap1.code) status=$($ap1.json.data.status)")
Pass 'approve_refund_only' ($ap1.code -eq 200 -and (Wait-Aftersale $as1No 'COMPLETED')) ("approvalStatus=$($ap1.json.data.status)")
$rf1 = Sql 'geek_trade' "SELECT refund_no,amount,status FROM trade_refund WHERE aftersale_id='$as1No';"
Pass 'refund_row_refund_only' ("$rf1" -match 'SUCCESS') ("row=$rf1")

# ---- 4) RETURN_REFUND ----
Log '== 4) RETURN_REFUND full path =='
[void](Invoke-Api 'POST' '/api/v1/trade/cart/items' $token @{ skuId = 11001; quantity = 1 } $null)
$cartList2 = Invoke-Api 'GET' '/api/v1/trade/cart' $token $null $null
$cid2 = Find-CartItemId $cartList2.json 11001
$pv2 = Invoke-Api 'POST' '/api/v1/trade/checkout/preview' $token @{
  cartItemIds = @([long]$cid2)
  addressId = [long]$addressId
  receiver = 'Geek User'
  mobile = '13800000001'
  regionCode = '310000'
  addressDetail = 'Jiading Geek Rd 88'
  deliveryMethod = 'STANDARD'
} $null
$ord2 = Invoke-Api 'POST' '/api/v1/trade/orders' $token @{
  checkoutToken = [string]$pv2.json.data.checkoutToken
  addressId = [long]$addressId
} @{ 'Idempotency-Key' = ('p1-rr-' + [guid]::NewGuid().ToString('N')) }
$orderNo2 = [string]$ord2.json.data.orderNo
$pay2 = Invoke-Api 'POST' '/api/v1/payments' $token @{ orderNo = $orderNo2; channel = 'SIMULATED' } $null
[void](Invoke-Api 'POST' ("/api/v1/payments/$($pay2.json.data.paymentNo)/simulate") $token @{} $null)
Start-Sleep -Seconds 2
$d2 = Invoke-Api 'GET' ("/api/v1/trade/orders/$orderNo2") $token $null $null
$item2 = @($d2.json.data.subOrders)[0].items[0]
$mer2 = @($d2.json.data.subOrders)[0].merchantId
$as2 = Invoke-Api 'POST' '/api/v1/aftersales' $token @{
  orderNo = $orderNo2
  orderItemId = [long]$item2.id
  merchantId = [long]$mer2
  type = 'RETURN_REFUND'
  quantity = 1
  reason = 'phase1-return-refund'
  amount = [decimal]$item2.payableAmount
} $null
$as2No = [string]$as2.json.data.aftersaleNo
Pass 'apply_return_refund' ($as2.code -eq 200) ("aftersaleNo=$as2No")

$ap2 = Invoke-Api 'POST' ("/api/v1/merchant/aftersales/$as2No/approve") $mtoken $null $null
Pass 'approve_stay_approved' ($ap2.code -eq 200 -and $ap2.json.data.status -eq 'APPROVED') ("status=$($ap2.json.data.status)")

$rl = Invoke-Api 'POST' ("/api/v1/aftersales/$as2No/return-logistics?trackingNo=RTN-P1-001") $token $null $null
Pass 'user_return_logistics' ($rl.code -eq 200 -and $rl.json.data.status -eq 'RETURNING') ("status=$($rl.json.data.status)")

$recv1 = Invoke-Api 'POST' ("/api/v1/merchant/aftersales/$as2No/receive") $mtoken $null $null
Pass 'warehouse_receive_refund' ($recv1.code -eq 200 -and (Wait-Aftersale $as2No 'COMPLETED')) ("receiveStatus=$($recv1.json.data.status)")
$recv2 = Invoke-Api 'POST' ("/api/v1/merchant/aftersales/$as2No/receive") $mtoken $null $null
Pass 'warehouse_receive_idempotent' ($recv2.code -eq 200) ("status=$($recv2.json.data.status)")
$rf2 = Sql 'geek_trade' "SELECT refund_no,amount,status FROM trade_refund WHERE aftersale_id='$as2No';"
Pass 'refund_row_return' ("$rf2" -match 'SUCCESS') ("row=$rf2")
$rfCount = (Sql 'geek_trade' "SELECT COUNT(*) FROM trade_refund WHERE aftersale_id='$as2No';").Trim()
Pass 'refund_idempotent_single_row' ($rfCount -eq '1') ("refundCount=$rfCount")

# ---- 5) Partial refund ceiling ----
Log '== 5) Partial refund cannot exceed payable =='
[void](Invoke-Api 'POST' '/api/v1/trade/cart/items' $token @{ skuId = 11001; quantity = 1 } $null)
$cartList3 = Invoke-Api 'GET' '/api/v1/trade/cart' $token $null $null
$cid3 = Find-CartItemId $cartList3.json 11001
$pv3 = Invoke-Api 'POST' '/api/v1/trade/checkout/preview' $token @{
  cartItemIds = @([long]$cid3)
  addressId = [long]$addressId
  receiver = 'Geek User'
  mobile = '13800000001'
  regionCode = '310000'
  addressDetail = 'Jiading Geek Rd 88'
  deliveryMethod = 'STANDARD'
} $null
$ord3 = Invoke-Api 'POST' '/api/v1/trade/orders' $token @{
  checkoutToken = [string]$pv3.json.data.checkoutToken
  addressId = [long]$addressId
} @{ 'Idempotency-Key' = ('p1-pr-' + [guid]::NewGuid().ToString('N')) }
$orderNo3 = [string]$ord3.json.data.orderNo
$pay3 = Invoke-Api 'POST' '/api/v1/payments' $token @{ orderNo = $orderNo3; channel = 'SIMULATED' } $null
[void](Invoke-Api 'POST' ("/api/v1/payments/$($pay3.json.data.paymentNo)/simulate") $token @{} $null)
Start-Sleep -Seconds 2
$d3 = Invoke-Api 'GET' ("/api/v1/trade/orders/$orderNo3") $token $null $null
$item3 = @($d3.json.data.subOrders)[0].items[0]
$mer3 = @($d3.json.data.subOrders)[0].merchantId
$payable3 = [decimal]$item3.payableAmount
$a60 = [math]::Round($payable3 * 0.6, 2)
$as3a = Invoke-Api 'POST' '/api/v1/aftersales' $token @{
  orderNo = $orderNo3
  orderItemId = [long]$item3.id
  merchantId = [long]$mer3
  type = 'REFUND_ONLY'
  quantity = 1
  reason = 'partial-a'
  amount = $a60
} $null
[void](Invoke-Api 'POST' ("/api/v1/merchant/aftersales/$($as3a.json.data.aftersaleNo)/approve?amount=$a60") $mtoken $null $null)
Pass 'partial_refund_first_completed' (Wait-Aftersale $as3a.json.data.aftersaleNo 'COMPLETED') ("aftersaleNo=$($as3a.json.data.aftersaleNo)")
$as3b = Invoke-Api 'POST' '/api/v1/aftersales' $token @{
  orderNo = $orderNo3
  orderItemId = [long]$item3.id
  merchantId = [long]$mer3
  type = 'REFUND_ONLY'
  quantity = 1
  reason = 'partial-over'
  amount = $a60
} $null
$overApprove = $null
$overCode = 'n/a'
if ($as3b.code -eq 200 -and $as3b.json.data.aftersaleNo) {
  $overApprove = Invoke-Api 'POST' ("/api/v1/merchant/aftersales/$($as3b.json.data.aftersaleNo)/approve?amount=$a60") $mtoken $null $null
  $overCode = "$($overApprove.code)/$($overApprove.json.code)"
}
$blocked = ($as3b.code -ge 400) -or ($overApprove -and (($overApprove.code -ge 400) -or ($overApprove.json.code -ne '0') -or (Wait-Aftersale $as3b.json.data.aftersaleNo 'REJECTED')))
Pass 'partial_refund_ceiling' $blocked ("first=60% secondBlocked=$blocked as3b=$($as3b.code) over=$overCode")

# ---- 7) Snapshot ----
Log '== 7) Final DB consistency snapshot =='
$snapOrder = Sql 'geek_trade' "SELECT order_no,status,inventory_status FROM trade_order WHERE order_no IN ('$orderNo','$skOrderNo','$orderNo2','$orderNo3');"
$snapFf = Sql 'geek_inventory' "SELECT fulfillment_no,order_no,status FROM inventory_fulfillment WHERE order_no IN ('$orderNo','$skOrderNo') ORDER BY id DESC LIMIT 5;"
$snapAs = Sql 'geek_aftersale' "SELECT aftersale_no,type,status FROM aftersale_order WHERE aftersale_no IN ('$as1No','$as2No');"
$snapRf = Sql 'geek_trade' "SELECT aftersale_id,refund_no,amount,status FROM trade_refund WHERE aftersale_id IN ('$as1No','$as2No');"
$snapCoupon = Sql 'geek_marketing' "SELECT id,status FROM marketing_user_coupon WHERE id=71104;"
$snapPoints = Sql 'geek_member' "SELECT user_id,point_balance,frozen_points FROM member_profile WHERE user_id=4;"
$snapStock = Sql 'geek_inventory' "SELECT sku_id,physical_qty,locked_qty,(physical_qty-locked_qty) AS sellable FROM inventory_sku_stock WHERE sku_id=11001 LIMIT 3;"
$snapPkg = Sql 'geek_inventory' "SELECT package_no,status FROM inventory_package WHERE fulfillment_id IN (SELECT id FROM inventory_fulfillment WHERE order_no IN ('$orderNo','$skOrderNo')) LIMIT 5;"
Log "orders: $snapOrder"
Log "fulfillments: $snapFf"
Log "packages: $snapPkg"
Log "aftersales: $snapAs"
Log "refunds: $snapRf"
Log "coupon71104: $snapCoupon"
Log "points: $snapPoints"
Log "inventory: $snapStock"
$ffShipped = ("$snapFf" -match 'SHIPPED|PARTIAL_SHIPPED')
$couponUsed = ("$snapCoupon" -match 'USED')
$pointsOk = ("$snapPoints" -match '\t4900\t|\s4900\s')
Pass 'consistency_snapshot_ok' ([bool]$snapOrder -and [bool]$snapAs -and [bool]$snapRf -and $ffShipped -and $couponUsed -and [bool]$snapStock) ("ffShipped=$ffShipped couponUsed=$couponUsed points=$snapPoints stock=$snapStock")

Log '== Known gaps (explicit) =='
Log '[NOT AUTOMATED] EXCHANGE restock fulfillment'
Log '[NOT AUTOMATED] Carrier real callback'

$fail = @($results | Where-Object { -not $_.pass }).Count
$passN = @($results | Where-Object { $_.pass }).Count
Log "SUMMARY pass=$passN fail=$fail total=$($results.Count)"
$results | Format-Table -AutoSize | Out-String | ForEach-Object { Log $_ }

$outFile = Join-Path $Root 'reports\phase1-trade-backhalf-acceptance.md'
New-Item -ItemType Directory -Force -Path (Split-Path $outFile) | Out-Null
$lines = @()
$lines += '# Phase-1 Trade Back-Half Acceptance'
$lines += ''
$lines += ('Time: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
$lines += ("Result: pass=$passN fail=$fail")
$lines += ''
$lines += '## Step log'
$lines += '```'
$lines += ($report -join "`n")
$lines += '```'
$lines += ''
$lines += '## Cases'
$lines += '| item | pass | detail |'
$lines += '|---|---|---|'
foreach ($r in $results) {
  $d = ([string]$r.detail).Replace('|', '/')
  $lines += "| $($r.item) | $($r.pass) | $d |"
}
$lines += ''
$lines += '## Explicit gaps'
$lines += '- Exchange restock fulfillment: NOT DONE'
$lines += '- Carrier real callback: NOT DONE'
$lines += ''
if ($fail -eq 0) {
  $lines += '## Conclusion'
  $lines += 'Phase-1 trade back-half **ACCEPTED** (fail=0) within declared gaps.'
  $lines += ''
  $lines += 'Status: **trade back-half accepted** within declared gaps (see docs/ACCEPTANCE.md).'
  $lines += ''
  $lines += 'Still unfinished (explicit): exchange/restock fulfillment; carrier real callback.'
} else {
  $lines += '## Conclusion'
  $lines += 'Phase-1 NOT accepted. See FAIL rows.'
}
Set-Content -Path $outFile -Value ($lines -join "`n") -Encoding UTF8
Log "report => $outFile"

if ($fail -gt 0) { exit 1 }
exit 0
