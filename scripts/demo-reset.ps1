<#
.SYNOPSIS
Resets only the local Geek Mall Docker Compose demo and runs a minimal smoke.

.EXAMPLE
powershell -File scripts\demo-reset.ps1 -Mode Fast -ConfirmLocalReset RESET_GEEK_MALL_LOCAL

.EXAMPLE
powershell -File scripts\demo-reset.ps1 -Mode Fast -ConfirmLocalReset RESET_GEEK_MALL_LOCAL -VerifyRepeatability

.EXAMPLE
powershell -File scripts\demo-reset.ps1 -Mode Full -ConfirmLocalReset RESET_GEEK_MALL_LOCAL
#>
param(
  [ValidateSet('Fast', 'Full')]
  [string]$Mode = 'Fast',

  [Parameter(Mandatory = $true)]
  [ValidateSet('RESET_GEEK_MALL_LOCAL')]
  [string]$ConfirmLocalReset,

  [switch]$VerifyRepeatability,
  [switch]$BuildImages,
  [int]$HealthTimeoutSeconds = 360,
  [string]$OutputJson
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$Root = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$ComposeFile = Join-Path $Root 'docker-compose.yml'
$InitSqlFile = Join-Path $Root 'deploy\mysql\init-databases.sql'
$ExpectedProject = 'geek-mall'
$ExpectedVolumes = @('elasticsearch-data', 'minio-data', 'mysql-data', 'rabbitmq-data', 'redis-data')
$Databases = @(
  'geek_system', 'geek_merchant_product', 'geek_inventory', 'geek_search',
  'geek_trade', 'geek_member', 'geek_marketing', 'geek_aftersale',
  'geek_service', 'geek_settlement', 'geek_ai'
)
$InfraServices = @('mysql', 'redis', 'rabbitmq', 'nacos', 'elasticsearch', 'minio')
$ApplicationServices = @(
  'auth-system', 'merchant-product', 'inventory-fulfillment', 'search-recommend',
  'trade', 'member', 'marketing', 'aftersale', 'service-center', 'settlement',
  'ai', 'gateway', 'mall-web'
)
$RequiredHealthyServices = @(
  'gateway', 'auth-system', 'merchant-product', 'inventory-fulfillment',
  'trade', 'ai', 'search-recommend'
)

if (-not $OutputJson) {
  $OutputJson = Join-Path $Root 'reports\demo-reset-result.json'
}

function Invoke-Compose {
  param(
    [Parameter(Mandatory = $true)][string[]]$Arguments,
    [switch]$Capture
  )

  $previous = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $output = @(& docker compose -f $ComposeFile @Arguments 2>&1)
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previous
  }

  if ($code -ne 0) {
    $output | ForEach-Object { Write-Host $_ }
    throw "docker compose failed ($code): $($Arguments -join ' ')"
  }

  if ($Capture) { return $output }
  $output | ForEach-Object { Write-Host $_ }
}

function Get-DotEnvValue {
  param([string]$Name)
  $path = Join-Path $Root '.env'
  if (-not (Test-Path -LiteralPath $path)) { return $null }
  $raw = Get-Content -LiteralPath $path -Raw -Encoding UTF8
  $match = [regex]::Match($raw, "(?m)^\s*$([regex]::Escape($Name))\s*=\s*([^\r\n#]+)")
  if (-not $match.Success) { return $null }
  return $match.Groups[1].Value.Trim().Trim('"').Trim("'")
}

function Assert-LocalSafety {
  if ($ConfirmLocalReset -ne 'RESET_GEEK_MALL_LOCAL') {
    throw 'Confirmation token mismatch.'
  }
  if ([string]$env:CI -match '^(?i:true|1|yes)$') {
    throw 'Refusing to reset from CI.'
  }

  $sharedProcess = [string]$env:GEEK_SHARED_ENVIRONMENT
  $sharedFile = [string](Get-DotEnvValue 'GEEK_SHARED_ENVIRONMENT')
  if ($sharedProcess -match '^(?i:true|1|yes)$' -or $sharedFile -match '^(?i:true|1|yes)$') {
    throw 'Refusing reset because GEEK_SHARED_ENVIRONMENT is enabled.'
  }

  $context = [string](& docker context show 2>&1)
  if ($LASTEXITCODE -ne 0) { throw 'Unable to read Docker context.' }
  $endpoint = [string](& docker context inspect --format '{{json .Endpoints.docker.Host}}' $context 2>&1)
  if ($LASTEXITCODE -ne 0) { throw "Unable to inspect Docker context '$context'." }
  $endpoint = $endpoint.Trim().Trim('"')
  if ($endpoint -notmatch '^(npipe:|unix:)') {
    throw "Refusing non-local Docker endpoint: $endpoint"
  }

  $configText = (Invoke-Compose -Arguments @('config', '--format', 'json') -Capture) -join "`n"
  $config = $configText | ConvertFrom-Json
  if ([string]$config.name -ne $ExpectedProject) {
    throw "Refusing Compose project '$($config.name)'; expected '$ExpectedProject'."
  }
  if ($config.services.mysql.PSObject.Properties.Name -contains 'ports') {
    throw 'Refusing reset because the base Compose file publishes a MySQL host port.'
  }

  if ($Mode -eq 'Full') {
    $actualVolumes = @((Invoke-Compose -Arguments @('config', '--volumes') -Capture) | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ } | Sort-Object)
    $expected = @($ExpectedVolumes | Sort-Object)
    if (($actualVolumes -join '|') -ne ($expected -join '|')) {
      throw "Refusing full reset: Compose volumes changed. actual=$($actualVolumes -join ',')"
    }
  }

  Write-Host "[SAFE] root=$Root project=$ExpectedProject context=$context endpoint=$endpoint shared=false"
}

function Get-ContainerId {
  param([string]$Service)
  $lines = @(Invoke-Compose -Arguments @('ps', '-q', $Service) -Capture)
  return ([string]($lines | Select-Object -Last 1)).Trim()
}

function Assert-ComposeContainer {
  param([string]$Service)
  $id = Get-ContainerId $Service
  if (-not $id) { throw "Compose service '$Service' has no container." }
  $inspectRaw = @(& docker inspect $id 2>&1)
  if ($LASTEXITCODE -ne 0) { throw "Unable to inspect container for $Service." }
  $inspect = (($inspectRaw -join "`n") | ConvertFrom-Json)[0]
  $projectLabel = [string]$inspect.Config.Labels.'com.docker.compose.project'
  $serviceLabel = [string]$inspect.Config.Labels.'com.docker.compose.service'
  if ($projectLabel -ne $ExpectedProject -or $serviceLabel -ne $Service) {
    throw "Container label guard failed for ${Service}: project=$projectLabel service=$serviceLabel"
  }
  return $id
}

function Wait-ServiceHealthy {
  param([string]$Service)
  $deadline = [DateTime]::UtcNow.AddSeconds($HealthTimeoutSeconds)
  $last = ''
  while ([DateTime]::UtcNow -lt $deadline) {
    $id = Get-ContainerId $Service
    if ($id) {
      $inspectRaw = @(& docker inspect $id 2>$null)
      if ($LASTEXITCODE -eq 0) {
        $inspect = (($inspectRaw -join "`n") | ConvertFrom-Json)[0]
        $state = if ($null -ne $inspect.State.Health) { [string]$inspect.State.Health.Status } else { [string]$inspect.State.Status }
      } else {
        $state = 'inspect-error'
      }
      if ($state -ne $last) {
        Write-Host "[WAIT] $Service=$state"
        $last = $state
      }
      if ($state -eq 'healthy' -or $state -eq 'running') {
        Write-Host "[UP] $Service=$state"
        return
      }
    }
    Start-Sleep -Seconds 3
  }
  throw "Timed out waiting for healthy service: $Service (last=$last)"
}

function Start-Infrastructure {
  $args = @('up', '-d')
  if ($BuildImages) { $args += '--build' } else { $args += '--no-build' }
  $args += $InfraServices
  Invoke-Compose -Arguments $args
  Wait-ServiceHealthy 'mysql'
  [void](Assert-ComposeContainer 'mysql')
}

function Start-Applications {
  param([switch]$ForceRecreate)
  $args = @('up', '-d')
  if ($BuildImages) { $args += '--build' } else { $args += '--no-build' }
  if ($ForceRecreate) { $args += '--force-recreate' }
  $args += $ApplicationServices
  Invoke-Compose -Arguments $args
  foreach ($service in $RequiredHealthyServices) {
    Wait-ServiceHealthy $service
  }
}

function Invoke-MySqlScalar {
  param(
    [string]$Database,
    [string]$Query,
    [switch]$AllowMissing
  )
  if ($Query.Contains("'")) { throw 'Internal summary query must not contain single quotes.' }
  $command = 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -s -D ' + $Database + " -e '$Query'"
  $previous = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $raw = @(& docker compose -f $ComposeFile exec -T mysql sh -lc $command 2>&1)
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previous
  }
  if ($code -ne 0) {
    if ($AllowMissing) { return [long]0 }
    $raw | ForEach-Object { Write-Host $_ }
    throw "Summary query failed: $Database :: $Query"
  }
  $number = $raw | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -match '^-?\d+$' } | Select-Object -Last 1
  if ($null -eq $number -or [string]::IsNullOrWhiteSpace([string]$number)) {
    if ($AllowMissing) { return [long]0 }
    throw "Summary query returned no integer: $Database :: $Query"
  }
  return [long]$number
}

function Get-DemoSummary {
  param([switch]$AllowMissing)
  return [ordered]@{
    accounts = Invoke-MySqlScalar 'geek_system' 'SELECT COUNT(*) FROM sys_user' -AllowMissing:$AllowMissing
    e2eAccounts = Invoke-MySqlScalar 'geek_system' 'SELECT COUNT(*) FROM sys_user WHERE id=7' -AllowMissing:$AllowMissing
    products = Invoke-MySqlScalar 'geek_merchant_product' 'SELECT COUNT(*) FROM product_spu' -AllowMissing:$AllowMissing
    skus = Invoke-MySqlScalar 'geek_merchant_product' 'SELECT COUNT(*) FROM product_sku' -AllowMissing:$AllowMissing
    stockRows = Invoke-MySqlScalar 'geek_inventory' 'SELECT COUNT(*) FROM inventory_sku_stock' -AllowMissing:$AllowMissing
    stockPhysical = Invoke-MySqlScalar 'geek_inventory' 'SELECT COALESCE(SUM(physical_qty),0) FROM inventory_sku_stock' -AllowMissing:$AllowMissing
    stockLocked = Invoke-MySqlScalar 'geek_inventory' 'SELECT COALESCE(SUM(locked_qty),0) FROM inventory_sku_stock' -AllowMissing:$AllowMissing
    addresses = Invoke-MySqlScalar 'geek_member' 'SELECT COUNT(*) FROM member_user_address' -AllowMissing:$AllowMissing
    couponTemplates = Invoke-MySqlScalar 'geek_marketing' 'SELECT COUNT(*) FROM marketing_coupon_template' -AllowMissing:$AllowMissing
    userCoupons = Invoke-MySqlScalar 'geek_marketing' 'SELECT COUNT(*) FROM marketing_user_coupon' -AllowMissing:$AllowMissing
    knowledgeDocuments = Invoke-MySqlScalar 'geek_ai' 'SELECT COUNT(*) FROM knowledge_document' -AllowMissing:$AllowMissing
    knowledgeChunks = Invoke-MySqlScalar 'geek_ai' 'SELECT COUNT(*) FROM knowledge_chunk' -AllowMissing:$AllowMissing
    e2eCartItems = Invoke-MySqlScalar 'geek_trade' 'SELECT COUNT(*) FROM trade_cart_item WHERE user_id=7' -AllowMissing:$AllowMissing
    e2eOrders = Invoke-MySqlScalar 'geek_trade' 'SELECT COUNT(*) FROM trade_order WHERE user_id=7' -AllowMissing:$AllowMissing
    e2ePayments = Invoke-MySqlScalar 'geek_trade' 'SELECT COUNT(*) FROM trade_payment p JOIN trade_order o ON o.id=p.order_id WHERE o.user_id=7' -AllowMissing:$AllowMissing
    e2eSearchHistory = Invoke-MySqlScalar 'geek_search' 'SELECT COUNT(*) FROM search_history WHERE user_id=7' -AllowMissing:$AllowMissing
    shopperCartItems = Invoke-MySqlScalar 'geek_trade' 'SELECT COUNT(*) FROM trade_cart_item WHERE user_id=4' -AllowMissing:$AllowMissing
    shopperOrders = Invoke-MySqlScalar 'geek_trade' 'SELECT COUNT(*) FROM trade_order WHERE user_id=4' -AllowMissing:$AllowMissing
  }
}

function Show-Summary {
  param([string]$Title, $Summary)
  Write-Host "== $Title =="
  $Summary.GetEnumerator() | ForEach-Object {
    [pscustomobject]@{ Metric = $_.Key; Count = $_.Value }
  } | Format-Table -AutoSize | Out-Host
}

function Reset-BusinessDatabases {
  $dropSql = ($Databases | ForEach-Object { "DROP DATABASE IF EXISTS $_;" }) -join "`n"
  $initSql = Get-Content -LiteralPath $InitSqlFile -Raw -Encoding UTF8
  $sql = "$dropSql`n$initSql"
  $previous = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $output = @($sql | & docker compose -f $ComposeFile exec -T mysql sh -lc 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' 2>&1)
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previous
  }
  if ($code -ne 0) {
    $output | ForEach-Object { Write-Host $_ }
    throw 'Unable to recreate local Geek Mall databases.'
  }
  Write-Host '[RESET] recreated 11 business databases; Flyway will migrate and seed them.'
}

function Clear-FastModeState {
  Invoke-Compose -Arguments @('exec', '-T', 'redis', 'redis-cli', 'FLUSHALL')
  Invoke-Compose -Arguments @(
    'exec', '-T', 'rabbitmq', 'sh', '-lc',
    'export PATH=/opt/erlang/lib/erlang/bin:/opt/rabbitmq/sbin:$PATH; for q in $(rabbitmqctl list_queues -q name); do [ "$q" = name ] || rabbitmqctl purge_queue "$q"; done'
  )
  Invoke-Compose -Arguments @(
    'exec', '-T', 'elasticsearch', 'sh', '-lc',
    'curl -sS -X DELETE http://localhost:9200/geek-products-v1 || true'
  )
  Write-Host '[RESET] cleared Redis, RabbitMQ queue messages and geek-products-v1.'
}

function Invoke-Api {
  param(
    [string]$Method,
    [string]$Path,
    [string]$Token,
    $Body,
    [hashtable]$Headers
  )
  $client = [System.Net.Http.HttpClient]::new()
  try {
    $client.Timeout = [TimeSpan]::FromSeconds(90)
    $request = [System.Net.Http.HttpRequestMessage]::new(
      [System.Net.Http.HttpMethod]::new($Method),
      "http://127.0.0.1:8088$Path"
    )
    [void]$request.Headers.TryAddWithoutValidation('X-Request-Id', [guid]::NewGuid().ToString())
    [void]$request.Headers.TryAddWithoutValidation('X-Client-Type', 'WEB')
    if ($Token) { [void]$request.Headers.TryAddWithoutValidation('Authorization', "Bearer $Token") }
    if ($Headers) {
      foreach ($key in $Headers.Keys) {
        [void]$request.Headers.TryAddWithoutValidation($key, [string]$Headers[$key])
      }
    }
    if ($null -ne $Body) {
      $json = $Body | ConvertTo-Json -Compress -Depth 8
      $request.Content = [System.Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
    }
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $parsed = $null
    try { $parsed = $text | ConvertFrom-Json } catch {}
    return [pscustomobject]@{ Code = [int]$response.StatusCode; Json = $parsed; Text = $text }
  } finally {
    $client.Dispose()
  }
}

function Invoke-MinimalSmoke {
  $results = New-Object System.Collections.Generic.List[object]
  function Add-SmokeResult([string]$Name, [bool]$Pass, [string]$Detail) {
    $results.Add([pscustomobject]@{ item = $Name; pass = $Pass; detail = $Detail })
    Write-Host ("[{0}] {1} :: {2}" -f $(if ($Pass) { 'PASS' } else { 'FAIL' }), $Name, $Detail)
  }

  $login = Invoke-Api 'POST' '/api/v1/auth/login/password' $null @{ username = 'e2e_tester'; password = 'Geek@123' } $null
  $token = if ($login.Json) { [string]$login.Json.data.accessToken } else { '' }
  $loginName = if ($login.Json) { [string]$login.Json.data.principal.username } else { '' }
  Add-SmokeResult 'login_e2e_tester' ($login.Code -eq 200 -and $token -and $loginName -eq 'e2e_tester') "http=$($login.Code) user=$loginName"

  $search = $null
  $searchItems = 0
  $searchDeadline = [DateTime]::UtcNow.AddSeconds(90)
  do {
    $search = Invoke-Api 'GET' '/api/v1/search/products?keyword=Air&page=1&pageSize=5' $token $null $null
    if ($search.Json) { $searchItems = @($search.Json.data.items).Count }
    if ($search.Code -eq 200 -and $searchItems -gt 0) { break }
    Start-Sleep -Seconds 3
  } while ([DateTime]::UtcNow -lt $searchDeadline)
  Add-SmokeResult 'search' ($search.Code -eq 200 -and $searchItems -gt 0) "http=$($search.Code) items=$searchItems"

  $cart = Invoke-Api 'POST' '/api/v1/trade/cart/items' $token @{ skuId = 11001; quantity = 1 } $null
  Add-SmokeResult 'add_cart' ($cart.Code -eq 200) "http=$($cart.Code)"

  $addresses = Invoke-Api 'GET' '/api/v1/user/addresses' $token $null $null
  $address = if ($addresses.Json) { @($addresses.Json.data | Where-Object { $_.isDefault -eq $true }) | Select-Object -First 1 } else { $null }
  if (-not $address -and $addresses.Json) { $address = @($addresses.Json.data) | Select-Object -First 1 }
  $addressSource = 'seed'
  if (-not $address) {
    $createdAddress = Invoke-Api 'POST' '/api/v1/user/addresses' $token @{
      receiver = 'E2E User'
      mobile = '13900000007'
      regionCode = '310000'
      regionName = 'Shanghai'
      detail = 'E2E Demo Address 7'
      isDefault = $true
    } $null
    if ($createdAddress.Json) { $address = $createdAddress.Json.data }
    $addressSource = 'smoke-fallback'
  }
  $addressId = if ($address) { [long]$address.id } else { 0 }
  Add-SmokeResult 'address_ready' ($addresses.Code -eq 200 -and $addressId -gt 0) "http=$($addresses.Code) addressId=$addressId source=$addressSource"

  $preview = Invoke-Api 'POST' '/api/v1/trade/checkout/preview' $token @{ addressId = $addressId; deliveryMethod = 'EXPRESS' } $null
  $checkoutToken = if ($preview.Json -and $preview.Json.data -and ($preview.Json.data.PSObject.Properties.Name -contains 'checkoutToken')) { [string]$preview.Json.data.checkoutToken } else { '' }
  Add-SmokeResult 'checkout_preview' ($preview.Code -eq 200 -and $checkoutToken) "http=$($preview.Code) tokenLength=$($checkoutToken.Length)"

  $idempotencyKey = 'demo-reset-e2e-order'
  $headers = @{ 'Idempotency-Key' = $idempotencyKey }
  $order1 = Invoke-Api 'POST' '/api/v1/trade/orders' $token @{ checkoutToken = $checkoutToken } $headers
  $order2 = Invoke-Api 'POST' '/api/v1/trade/orders' $token @{ checkoutToken = $checkoutToken } $headers
  $orderNo1 = if ($order1.Json) { [string]$order1.Json.data.orderNo } else { '' }
  $orderNo2 = if ($order2.Json) { [string]$order2.Json.data.orderNo } else { '' }
  Add-SmokeResult 'idempotent_order' ($order1.Code -eq 200 -and $order2.Code -eq 200 -and $orderNo1 -and $orderNo1 -eq $orderNo2) "first=$orderNo1 second=$orderNo2"

  $payment = Invoke-Api 'POST' '/api/v1/payments' $token @{ orderNo = $orderNo1; channel = 'SIMULATED' } $null
  $paymentNo = if ($payment.Json) { [string]$payment.Json.data.paymentNo } else { '' }
  $simulate = if ($paymentNo) { Invoke-Api 'POST' "/api/v1/payments/$paymentNo/simulate" $token @{} $null } else { $null }
  $simulateCode = if ($simulate) { $simulate.Code } else { 0 }
  Add-SmokeResult 'simulated_payment' ($payment.Code -eq 200 -and $paymentNo -and $simulateCode -eq 200) "create=$($payment.Code) simulate=$simulateCode paymentNo=$paymentNo"

  $detail = $null
  $status = ''
  $inventoryStatus = ''
  $paidDeadline = [DateTime]::UtcNow.AddSeconds(45)
  do {
    $detail = Invoke-Api 'GET' "/api/v1/trade/orders/$orderNo1" $token $null $null
    if ($detail.Json) {
      $status = [string]$detail.Json.data.status
      $inventoryStatus = [string]$detail.Json.data.inventoryStatus
    }
    if ($status -eq 'PAID' -and $inventoryStatus -eq 'DEDUCTED') { break }
    Start-Sleep -Seconds 2
  } while ([DateTime]::UtcNow -lt $paidDeadline)
  Add-SmokeResult 'order_paid' ($detail.Code -eq 200 -and $status -eq 'PAID') "http=$($detail.Code) status=$status inventory=$inventoryStatus"

  $failed = @($results | Where-Object { -not $_.pass }).Count
  return [ordered]@{
    pass = ($failed -eq 0)
    passed = @($results | Where-Object { $_.pass }).Count
    failed = $failed
    orderNo = $orderNo1
    paymentNo = $paymentNo
    results = $results.ToArray()
  }
}

function Invoke-ResetCycle {
  param([int]$Cycle)
  Write-Host "`n========== DEMO RESET cycle=$Cycle mode=$Mode =========="

  Start-Infrastructure
  $before = Get-DemoSummary -AllowMissing
  Show-Summary "cycle $Cycle before" $before

  if ($Mode -eq 'Full') {
    [void](Assert-ComposeContainer 'mysql')
    Invoke-Compose -Arguments @('down', '--volumes', '--remove-orphans')
    Start-Infrastructure
  } else {
    Invoke-Compose -Arguments (@('stop') + $ApplicationServices)
    Reset-BusinessDatabases
    Clear-FastModeState
  }

  Start-Applications -ForceRecreate:($Mode -eq 'Fast')
  $smoke = Invoke-MinimalSmoke
  $after = Get-DemoSummary
  Show-Summary "cycle $Cycle after smoke" $after

  if ([long]$after.e2eAccounts -ne 1) { throw 'e2e_tester seed is not deterministic.' }
  if ([long]$after.shopperCartItems -ne 0 -or [long]$after.shopperOrders -ne 0) {
    throw 'Smoke polluted shopper data.'
  }
  if (-not [bool]$smoke.pass) { throw "Smoke failed in cycle $Cycle." }

  return [ordered]@{ cycle = $Cycle; before = $before; after = $after; smoke = $smoke }
}

function Compare-Summaries {
  param($First, $Second)
  $differences = New-Object System.Collections.Generic.List[object]
  foreach ($key in $First.Keys) {
    if ([long]$First[$key] -ne [long]$Second[$key]) {
      $differences.Add([pscustomobject]@{ metric = $key; first = $First[$key]; second = $Second[$key] })
    }
  }
  $pass = ($differences.Count -eq 0)
  Write-Host ("[{0}] repeatability :: differences={1}" -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $differences.Count)
  if (-not $pass) { $differences | Format-Table -AutoSize | Out-Host }
  return [ordered]@{ pass = $pass; differences = $differences.ToArray() }
}

$originalLocation = Get-Location
try {
  Set-Location $Root
  if (-not (Test-Path -LiteralPath (Join-Path $Root '.env'))) {
    Copy-Item -LiteralPath (Join-Path $Root '.env.example') -Destination (Join-Path $Root '.env')
  }

  Assert-LocalSafety
  $cycles = New-Object System.Collections.Generic.List[object]
  $cycleCount = if ($VerifyRepeatability) { 2 } else { 1 }
  for ($cycle = 1; $cycle -le $cycleCount; $cycle++) {
    $cycles.Add((Invoke-ResetCycle $cycle))
  }

  $repeatability = if ($cycleCount -eq 2) {
    Compare-Summaries $cycles[0].after $cycles[1].after
  } else {
    [ordered]@{ pass = $null; differences = @() }
  }

  $payload = [ordered]@{
    script = 'demo-reset.ps1'
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    mode = $Mode
    localSafety = [ordered]@{
      project = $ExpectedProject
      sharedEnvironment = $false
      confirmationRequired = 'RESET_GEEK_MALL_LOCAL'
    }
    cycles = $cycles.ToArray()
    repeatability = $repeatability
  }
  $parent = Split-Path -Parent $OutputJson
  if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
  $payload | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputJson -Encoding UTF8
  Write-Host "JSON $OutputJson"

  if ($cycleCount -eq 2 -and -not [bool]$repeatability.pass) {
    throw 'Repeatability comparison failed.'
  }
  Write-Host "[PASS] demo reset completed mode=$Mode cycles=$cycleCount"
} finally {
  Set-Location $originalLocation
}
