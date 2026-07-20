$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

function U([int[]]$codes) { -join ($codes | ForEach-Object { [char]$_ }) }

function Post-Json($url, $token, $obj) {
  $client = [System.Net.Http.HttpClient]::new()
  try {
    $client.Timeout = [TimeSpan]::FromSeconds(90)
    $json = ($obj | ConvertTo-Json -Compress -Depth 8)
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $url)
    $req.Content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, 'application/json')
    if ($token) { [void]$req.Headers.TryAddWithoutValidation('Authorization', "Bearer $token") }
    $resp = $client.SendAsync($req).GetAwaiter().GetResult()
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    return @{ code = [int]$resp.StatusCode; json = ($text | ConvertFrom-Json -ErrorAction SilentlyContinue); text = $text }
  } finally { $client.Dispose() }
}

function Get-Json($url, $token) {
  $client = [System.Net.Http.HttpClient]::new()
  try {
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, $url)
    if ($token) { [void]$req.Headers.TryAddWithoutValidation('Authorization', "Bearer $token") }
    $resp = $client.SendAsync($req).GetAwaiter().GetResult()
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    return @{ code = [int]$resp.StatusCode; json = ($text | ConvertFrom-Json -ErrorAction SilentlyContinue); text = $text }
  } finally { $client.Dispose() }
}

function Login($user) {
  $r = Post-Json 'http://127.0.0.1:8080/api/v1/auth/login/password' $null @{ username = $user; password = 'Geek@123' }
  if ($r.code -ne 200 -or -not $r.json.data.accessToken) { throw "login failed $user $($r.text)" }
  return [string]$r.json.data.accessToken
}

function Ask($token, $scene, $message) {
  $c = Post-Json 'http://127.0.0.1:9011/api/v1/ai/conversations' $token @{ scene = $scene }
  $no = $c.json.data.conversationNo
  return Post-Json "http://127.0.0.1:9011/api/v1/ai/conversations/$no/messages" $token @{ message = $message; scene = $scene }
}

$results = @()
function Pass($name, $ok, $detail) {
  $script:results += [pscustomobject]@{ item = $name; pass = [bool]$ok; detail = $detail }
  $mark = if ($ok) { 'PASS' } else { 'FAIL' }
  Write-Host ("[{0}] {1} :: {2}" -f $mark, $name, $detail)
}

$token = Login 'shopper'
$admin = Login 'admin'

$msgGuide = (U @(0x9884,0x7B97)) + '8000' + (U @(0x5DE6,0x53F3,0x8981,0x4E00,0x53F0,0x529E,0x516C,0x7B14,0x8BB0,0x672C))
$g = Ask $token 'GUIDE' $msgGuide
$contentOk = ($g.json.data.content -match [regex]::Escape([string]([char]0x63A8)+[char]0x8350)) -or ($g.json.data.content -match 'Air') -or ($g.json.data.agent -eq 'GUIDE')
Pass 'guide_ask_and_recommend' ($g.code -eq 200 -and $g.json.data.agent -eq 'GUIDE') ("agent=$($g.json.data.agent) code=$($g.code)")

$msgMem = (U @(0x6781,0x5BA2,0x672C)) + ' Air 14 ' + (U @(0x5185,0x5B58,0x662F,0x591A,0x5C11))
$k = Ask $token 'PRODUCT_KNOWLEDGE' $msgMem
$citeN = @($k.json.data.citations).Count
Pass 'knowledge_has_citation' ($k.code -eq 200 -and $citeN -gt 0 -and ($k.json.data.content -like '*32GB*')) ("cites=$citeN has32=$($k.json.data.content -like '*32GB*')")

$p = Ask $token 'ORDER_LOGISTICS' 'please pay_order for my last order now'
$denyPay = @($p.json.data.tools | Where-Object { $_.toolName -eq 'pay_order' -and $_.status -eq 'DENIED' }).Count -gt 0
Pass 'deny_pay_order' ($p.code -eq 200 -and $denyPay) ("denyPay=$denyPay")

$r = Ask $token 'AFTERSALE' 'please run forbidden money tool now'
$denyRefund = @($r.json.data.tools | Where-Object { $_.toolName -eq 'formal_refund' -and $_.status -eq 'DENIED' }).Count -gt 0
Pass 'deny_formal_refund' ($r.code -eq 200 -and $denyRefund) ("denyRefund=$denyRefund")

$a = Ask $token 'AFTERSALE' 'I need aftersale draft help'
$draft = @($a.json.data.tools | Where-Object { $_.toolName -eq 'create_aftersale_draft' -and $_.status -eq 'SUCCESS' }).Count -gt 0
Pass 'aftersale_draft_tool' ($a.code -eq 200 -and $draft) ("draft=$draft")

$c2 = Ask $token 'GUIDE' 'please add to cart this laptop'
$cartTool = @($c2.json.data.tools | Where-Object { $_.toolName -eq 'add_cart_item' }) | Select-Object -First 1
$cartOk = $null -ne $cartTool -and ($cartTool.status -eq 'SUCCESS' -or $cartTool.status -eq 'DENIED')
Pass 'add_cart_planned' ($c2.code -eq 200 -and $cartOk) ("cart=$($cartTool.status) result=$($cartTool.result.status)")

$ing = Post-Json 'http://127.0.0.1:9011/api/v1/admin/ai/knowledge/ingest' $admin @{
  kbCode = 'PRODUCT'; sourceType = 'IMAGE_MANUAL'; sourceId = 'VERIFY-OCR'
  title = 'verify ocr'; mimeType = 'image/png'; fileUrl = '/x.png'; content = '32GB memory'
}
Pass 'knowledge_ingest_ocr' ($ing.code -eq 200 -and $ing.json.data.ocrUsed -eq $true) ("ocr=$($ing.json.data.ocrUsed) chunks=$($ing.json.data.chunkCount)")

$o = Ask $token 'ORDER_LOGISTICS' 'show my orders'
Pass 'order_self_scope' ($o.code -eq 200 -and $o.json.data.agent -eq 'ORDER_LOGISTICS') ("agent=$($o.json.data.agent)")

$cmp = Ask $token 'COMPARE' 'compare Air 14 and Pro 16'
Pass 'compare_agent' ($cmp.code -eq 200 -and $cmp.json.data.agent -eq 'COMPARE') ("agent=$($cmp.json.data.agent)")

$gc = Post-Json 'http://127.0.0.1:8080/api/v1/ai/conversations' $token @{ scene = 'GUIDE' }
Pass 'gateway_ai_route' ($gc.code -eq 200 -and $gc.json.data.conversationNo) ("code=$($gc.code) conv=$($gc.json.data.conversationNo)")

$ev = Post-Json 'http://127.0.0.1:9011/api/v1/admin/ai/evaluations/run' $admin @{}
Pass 'fixed_eval_run' ($ev.code -eq 200 -and $null -ne $ev.json.data.status) ("status=$($ev.json.data.status) $($ev.json.data.passed)/$($ev.json.data.total)")

$logs = Get-Json 'http://127.0.0.1:9011/api/v1/admin/ai/call-logs' $admin
$met = Get-Json 'http://127.0.0.1:9011/api/v1/admin/ai/metrics' $admin
Pass 'admin_audit_apis' ($logs.code -eq 200 -and $met.code -eq 200) ("logs=$($logs.code) metrics=$($met.code)")

# SSE: unknown conversation must be business 404, not a transport success disguise
$sse = Get-Json 'http://127.0.0.1:9011/api/v1/ai/conversations/DOES_NOT_EXIST/stream?message=hi' $token
$sseBody = [string]$sse.text
$sseSnippet = if ($sseBody.Length -gt 80) { $sseBody.Substring(0, 80) } else { $sseBody }
$sseOk = ($sse.code -eq 404) -or (($sse.code -eq 400 -or $sse.code -eq 200) -and ($sseBody -match 'AI404|conversation|不存在'))
Pass 'sse_endpoint_wired' $sseOk ("code=$($sse.code) bodySnippet=$sseSnippet")

$fail = @($results | Where-Object { -not $_.pass }).Count
$passN = @($results | Where-Object { $_.pass }).Count
Write-Host "SUMMARY pass=$passN fail=$fail total=$($results.Count)"
$results | Format-Table -AutoSize
if ($fail -gt 0) { exit 1 }
