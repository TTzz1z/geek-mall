# Brand system Phase-2 acceptance: CMS home floors + publish/rollback + OPS
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
  } finally {
    $client.Dispose()
    $req.Dispose()
  }
}

Add-Type -AssemblyName System.Net.Http
Write-Host '== Brand Phase-2 verify =='

$cms = Api 'GET' '/api/v1/cms/pages/HOME' $null $null
$blocks = @($cms.json.data.blocks)
$types = @($blocks | ForEach-Object { [string]$_.type })
$hasSeckill = $types -contains 'SECKILL'
$hasFloor = $types -contains 'PRODUCT_FLOOR'
$cmsOk = ($cms.code -eq 200 -and [int]$cms.json.data.versionNo -ge 2 -and $hasFloor -and $hasSeckill)
Pass 'cms_published_home_v2' $cmsOk ("code=$($cms.code) v=$($cms.json.data.versionNo) types=$($types -join ',')")

$batch = Api 'GET' '/api/v1/products/batch?ids=10003,10004,10001' $null $null
$batchItems = @($batch.json.data)
Pass 'products_batch' ($batch.code -eq 200 -and $batchItems.Count -eq 3) ("code=$($batch.code) count=$($batchItems.Count)")

$sessions = Api 'GET' '/api/v1/marketing/seckill/sessions' $null $null
Pass 'seckill_sessions_api' ($sessions.code -eq 200) ("code=$($sessions.code) count=$(@($sessions.json.data).Count)")

$adminLogin = Api 'POST' '/api/v1/auth/login/password' $null @{ username = 'admin'; password = 'Geek@123' }
$adminToken = [string]$adminLogin.json.data.accessToken
$adminPerms = @($adminLogin.json.data.principal.permissions)
Pass 'admin_login' ($adminLogin.code -eq 200 -and $adminToken -and ($adminPerms -contains '*')) ("perms=$($adminPerms -join ',')")

$opsLogin = Api 'POST' '/api/v1/auth/login/password' $null @{ username = 'operator'; password = 'Geek@123' }
$opsToken = [string]$opsLogin.json.data.accessToken
$opsRoles = @($opsLogin.json.data.principal.roles)
$opsPerms = @($opsLogin.json.data.principal.permissions)
Pass 'ops_login_rbac' ($opsLogin.code -eq 200 -and $opsToken -and ($opsRoles -contains 'OPS') -and ($opsPerms -contains 'cms:page:edit')) ("roles=$($opsRoles -join ',') perms=$($opsPerms -join ',')")

$versions = Api 'GET' '/api/v1/admin/cms/pages/HOME/versions' $opsToken $null
$verRows = @($versions.json.data)
$publishedBefore = ($verRows | Where-Object { $_.status -eq 'PUBLISHED' } | Select-Object -First 1).versionNo
Pass 'ops_list_versions' ($versions.code -eq 200 -and $verRows.Count -ge 2 -and $publishedBefore) ("code=$($versions.code) count=$($verRows.Count) published=$publishedBefore")

$draftTitle = "phase2-verify-draft-$(Get-Date -Format 'HHmmss')"
$draftBody = @{
  title = $draftTitle
  blocks = @(
    @{ type = 'HERO'; title = 'Phase2 Draft Hero'; subtitle = 'verify'; cta = '立即选购'; spuId = 10003 }
    @{ type = 'PRODUCT_FLOOR'; code = 'verify_floor'; title = 'Verify Floor'; subtitle = 'ops draft'; spuIds = @(10003, 10004) }
    @{ type = 'SECKILL'; code = 'seckill'; title = '限时秒杀'; subtitle = 'verify' }
  )
}
$create = Api 'POST' '/api/v1/admin/cms/pages/HOME/versions' $opsToken $draftBody
$draftNo = [int]$create.json.data.versionNo
Pass 'ops_create_draft' ($create.code -eq 200 -and $draftNo -gt 0) ("code=$($create.code) draft=$draftNo msg=$($create.json.message)")

$updateBody = @{
  title = "$draftTitle-updated"
  blocks = $draftBody.blocks
}
$update = Api 'PUT' "/api/v1/admin/cms/pages/HOME/versions/$draftNo" $opsToken $updateBody
Pass 'ops_update_draft' ($update.code -eq 200 -and [string]$update.json.data.status -eq 'DRAFT') ("code=$($update.code) status=$($update.json.data.status)")

$publish = Api 'POST' "/api/v1/admin/cms/pages/HOME/publish?versionNo=$draftNo" $opsToken $null
$pubCheck = Api 'GET' '/api/v1/cms/pages/HOME' $null $null
$pubOk = ($publish.code -eq 200 -and [int]$pubCheck.json.data.versionNo -eq $draftNo -and [string]$pubCheck.json.data.title -eq "$draftTitle-updated")
Pass 'publish_draft_live' $pubOk ("publish=$($publish.code) liveV=$($pubCheck.json.data.versionNo) title=$($pubCheck.json.data.title)")

$rollback = Api 'POST' "/api/v1/admin/cms/pages/HOME/rollback?versionNo=$publishedBefore" $opsToken $null
$rbCheck = Api 'GET' '/api/v1/cms/pages/HOME' $null $null
$rbOk = ($rollback.code -eq 200 -and [int]$rbCheck.json.data.versionNo -eq [int]$publishedBefore)
Pass 'rollback_previous' $rbOk ("rollback=$($rollback.code) liveV=$($rbCheck.json.data.versionNo) expect=$publishedBefore")

# restore seed v2 content if rollback landed on v1 archived template
$liveBlocks = @($rbCheck.json.data.blocks)
$liveTypes = @($liveBlocks | ForEach-Object { [string]$_.type })
if (-not ($liveTypes -contains 'PRODUCT_FLOOR')) {
  $restore = Api 'POST' "/api/v1/admin/cms/pages/HOME/rollback?versionNo=2" $adminToken $null
  Pass 'restore_cms_v2' ($restore.code -eq 200) ("code=$($restore.code)")
} else {
  Pass 'restore_cms_v2' ($liveTypes -contains 'PRODUCT_FLOOR') 'live page already has PRODUCT_FLOOR'
}

$paLogin = Api 'POST' '/api/v1/auth/login/password' $null @{ username = 'product_admin'; password = 'Geek@123' }
$paRoles = @($paLogin.json.data.principal.roles)
$paPerms = @($paLogin.json.data.principal.permissions)
Pass 'product_admin_rbac' ($paLogin.code -eq 200 -and ($paRoles -contains 'PRODUCT_ADMIN') -and ($paPerms -contains 'catalog:manage')) ("roles=$($paRoles -join ',') perms=$($paPerms -join ',')")

$denied = Api 'GET' '/api/v1/admin/cms/pages/HOME/versions' ([string]$paLogin.json.data.accessToken) $null
Pass 'product_admin_cms_denied' ($denied.code -eq 403 -or [string]$denied.json.code -match '403|AUTH|FORBIDDEN' -or $denied.code -eq 401) ("http=$($denied.code) biz=$($denied.json.code)")

Write-Host "SUMMARY fail=$fail"
if ($fail -gt 0) { exit 1 }
exit 0
