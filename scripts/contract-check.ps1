# Contract check aligned with security Compose topology.
# External APIs: E2E_BASE_URL (default http://127.0.0.1:8088 via mall-web nginx).
# Internal health/OpenAPI: docker compose exec into the Compose network (service names).
# Static: contracts/, Gateway routes, event samples. Unexecuted checks are never PASS.
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$BaseUrl = if ($env:E2E_BASE_URL) { $env:E2E_BASE_URL.TrimEnd('/') } else { 'http://127.0.0.1:8088' }
$ComposeFile = Join-Path $Root 'docker-compose.yml'
$ComposeProject = if ($env:COMPOSE_PROJECT_NAME) { $env:COMPOSE_PROJECT_NAME } else { 'geek-mall' }
$ReportDir = Join-Path $Root 'reports'
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
$ReportJson = Join-Path $ReportDir 'contract-check-result.json'

$checks = [System.Collections.Generic.List[object]]::new()
$pass = 0
$fail = 0
$skip = 0

function Add-Check {
  param(
    [string]$Name,
    [ValidateSet('PASS', 'FAIL', 'SKIP')][string]$Status,
    [string]$Detail = ''
  )
  $script:checks.Add([ordered]@{
      name   = $Name
      status = $Status
      detail = $Detail
    })
  switch ($Status) {
    'PASS' { $script:pass++; Write-Host "[PASS] $Name$(if ($Detail) { " :: $Detail" })" }
    'FAIL' { $script:fail++; Write-Host "[FAIL] $Name$(if ($Detail) { " :: $Detail" })" }
    'SKIP' { $script:skip++; Write-Host "[SKIP] $Name$(if ($Detail) { " :: $Detail" })" }
  }
}

function Invoke-Compose {
  param([string[]]$Arguments)
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $raw = & docker compose -p $ComposeProject -f $ComposeFile @Arguments 2>&1
    $code = $LASTEXITCODE
    $lines = [System.Collections.Generic.List[string]]::new()
    if ($null -ne $raw) {
      $items = if ($raw -is [System.Array]) { $raw } else { @($raw) }
      foreach ($item in $items) {
        foreach ($piece in (("$item" -split "`r?`n"))) {
          if ($null -ne $piece -and "$piece".Length -gt 0) { [void]$lines.Add("$piece") }
        }
      }
    }
    return [pscustomobject]@{
      Code   = $code
      Output = $lines.ToArray()
    }
  } finally {
    $ErrorActionPreference = $prev
  }
}

function Test-ComposeReady {
  $r = Invoke-Compose -Arguments @('ps', '--status', 'running', '--services')
  if ($r.Code -ne 0) { return $false }
  $services = @($r.Output | Where-Object { $_.Trim() -ne '' })
  return ($services -contains 'gateway') -and ($services -contains 'trade') -and ($services -contains 'mall-web')
}

# --- Static: event samples ---
$schemaPath = Join-Path $Root 'contracts\events\domain-event.schema.json'
$sampleDir = Join-Path $Root 'contracts\events\samples'
if (-not (Test-Path $schemaPath)) {
  Add-Check 'event_schema_present' 'FAIL' "missing $schemaPath"
} else {
  $schema = Get-Content $schemaPath -Raw -Encoding UTF8 | ConvertFrom-Json
  $required = @($schema.required)
  $allowedTypes = @($schema.properties.eventType.enum)
  Add-Check 'event_schema_present' 'PASS' "required=$($required.Count) eventTypes=$($allowedTypes.Count)"

  $samples = @(Get-ChildItem $sampleDir -Filter *.json -ErrorAction SilentlyContinue)
  if ($samples.Count -eq 0) {
    Add-Check 'event_samples' 'FAIL' 'no sample json files'
  } else {
    foreach ($file in $samples) {
      $obj = Get-Content $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
      $ok = $true
      $reasons = @()
      foreach ($f in $required) {
        if (-not ($obj.PSObject.Properties.Name -contains $f)) {
          $ok = $false
          $reasons += "missing $f"
        }
      }
      if ($obj.eventType -and ($allowedTypes -notcontains $obj.eventType)) {
        $ok = $false
        $reasons += "eventType not in enum: $($obj.eventType)"
      }
      if ($ok) { Add-Check "event_sample:$($file.Name)" 'PASS' }
      else { Add-Check "event_sample:$($file.Name)" 'FAIL' ($reasons -join '; ') }
    }
  }
}

# --- Static: checkout HTTP sample ---
$checkoutSample = Join-Path $Root 'contracts\http\checkout-preview.request.json'
if (-not (Test-Path $checkoutSample)) {
  Add-Check 'checkout_preview_addressId' 'FAIL' 'sample missing'
} else {
  $req = Get-Content $checkoutSample -Raw -Encoding UTF8 | ConvertFrom-Json
  if ($null -ne $req.addressId) { Add-Check 'checkout_preview_addressId' 'PASS' "addressId=$($req.addressId)" }
  else { Add-Check 'checkout_preview_addressId' 'FAIL' 'missing addressId' }
}

# --- Static: Gateway routes ---
$gwYml = Join-Path $Root 'backend\geek-gateway\src\main\resources\application.yml'
if (-not (Test-Path $gwYml)) {
  Add-Check 'gateway_routes_source' 'FAIL' 'application.yml missing'
} else {
  $gwText = Get-Content $gwYml -Raw -Encoding UTF8
  if ($gwText -match '/api/v1/admin/fulfillments') { Add-Check 'gateway_fulfillments_route' 'PASS' }
  else { Add-Check 'gateway_fulfillments_route' 'FAIL' 'route missing in source' }
  if ($gwText -match '/api/v1/merchant/orders/\*\*') { Add-Check 'gateway_merchant_orders_route' 'PASS' }
  else { Add-Check 'gateway_merchant_orders_route' 'FAIL' 'route missing in source' }
  if ($gwText -match '/api/v1/auth/\*\*') { Add-Check 'gateway_auth_route' 'PASS' }
  else { Add-Check 'gateway_auth_route' 'FAIL' 'route missing in source' }
  if ($gwText -match '/api/v1/trade/\*\*') { Add-Check 'gateway_trade_route' 'PASS' }
  else { Add-Check 'gateway_trade_route' 'FAIL' 'route missing in source' }
}

# --- Static: frontend nginx proxies /api to gateway ---
$nginxConf = Join-Path $Root 'frontend\deploy\nginx-spa.conf'
if (-not (Test-Path $nginxConf)) {
  Add-Check 'nginx_api_proxy' 'FAIL' 'nginx-spa.conf missing'
} else {
  $ngx = Get-Content $nginxConf -Raw -Encoding UTF8
  if ($ngx -match 'location\s+/api/' -and $ngx -match 'gateway:8080') {
    Add-Check 'nginx_api_proxy' 'PASS' 'proxies /api/ to gateway:8080'
  } else {
    Add-Check 'nginx_api_proxy' 'FAIL' 'expected /api/ -> gateway:8080'
  }
}

# --- External via E2E_BASE_URL (mall-web / nginx) ---
function Check-External {
  param([string]$Name, [string]$Path, [scriptblock]$Assert)
  $url = "$BaseUrl$Path"
  try {
    $resp = Invoke-WebRequest -Uri $url -TimeoutSec 15 -UseBasicParsing
    & $Assert $resp
  } catch {
    Add-Check $Name 'FAIL' "$url :: $($_.Exception.Message)"
  }
}

Check-External 'external_mall_home' '/' {
  param($resp)
  if ($resp.StatusCode -eq 200 -and $resp.Content.Length -gt 50) {
    Add-Check 'external_mall_home' 'PASS' "$BaseUrl/ status=200"
  } else {
    Add-Check 'external_mall_home' 'FAIL' "status=$($resp.StatusCode) len=$($resp.Content.Length)"
  }
}

# Public product list may require auth depending on gateway filters; 200 or 401 both prove nginx->gateway->service path.
try {
  $prodUrl = "$BaseUrl/api/v1/products?page=1&size=1"
  $prodResp = Invoke-WebRequest -Uri $prodUrl -TimeoutSec 15 -UseBasicParsing
  $code = [int]$prodResp.StatusCode
  if ($code -in 200, 401) { Add-Check 'external_api_products_via_nginx' 'PASS' "GET status=$code" }
  else { Add-Check 'external_api_products_via_nginx' 'FAIL' "status=$code" }
} catch {
  $code = $null
  if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
    $code = [int]$_.Exception.Response.StatusCode.value__
  }
  if ($code -in 200, 401) { Add-Check 'external_api_products_via_nginx' 'PASS' "GET status=$code" }
  else { Add-Check 'external_api_products_via_nginx' 'FAIL' "$prodUrl :: $($_.Exception.Message)" }
}

# Auth login contract via POST (invalid credentials must be reachable, not 502/504).
try {
  $loginUrl = "$BaseUrl/api/v1/auth/login/password"
  $body = '{"username":"__contract_probe__","password":"__invalid__"}'
  $loginResp = Invoke-WebRequest -Uri $loginUrl -Method POST -Body $body -ContentType 'application/json' -TimeoutSec 15 -UseBasicParsing
  $code = [int]$loginResp.StatusCode
  if ($code -in 400, 401, 403, 422) {
    Add-Check 'external_auth_login_contract' 'PASS' "POST status=$code"
  } else {
    Add-Check 'external_auth_login_contract' 'FAIL' "unexpected status=$code"
  }
} catch {
  $ex = $_.Exception
  $code = $null
  if ($ex.Response -and $ex.Response.StatusCode) {
    $code = [int]$ex.Response.StatusCode.value__
  }
  if ($code -in 400, 401, 403, 422) {
    Add-Check 'external_auth_login_contract' 'PASS' "POST status=$code"
  } else {
    Add-Check 'external_auth_login_contract' 'FAIL' "$loginUrl :: $($ex.Message)"
  }
}

# --- Internal via compose network ---
$composeReady = Test-ComposeReady
if (-not $composeReady) {
  Add-Check 'compose_runtime' 'FAIL' "project=$ComposeProject missing running gateway/trade/mall-web; internal checks not executed"
} else {
  Add-Check 'compose_runtime' 'PASS' "project=$ComposeProject"

  function Check-InternalHealth {
    param([string]$Name, [string]$Service, [int]$Port)
    $cmd = "curl -fsS --max-time 8 http://${Service}:${Port}/actuator/health"
    $r = Invoke-Compose -Arguments @('exec', '-T', 'gateway', 'sh', '-lc', $cmd)
    if ($r.Code -ne 0) {
      Add-Check $Name 'FAIL' ("compose exec :: " + ($r.Output -join ' '))
      return
    }
    $text = ($r.Output -join '').Trim()
    try {
      $json = $text | ConvertFrom-Json
      if ($json.status -eq 'UP') { Add-Check $Name 'PASS' "${Service}:${Port}" }
      else { Add-Check $Name 'FAIL' "status=$($json.status) body=$text" }
    } catch {
      Add-Check $Name 'FAIL' "invalid json: $text"
    }
  }

  function Check-InternalOpenApi {
    param([string]$Name, [string]$Service, [int]$Port)
    $cmd = "curl -fsS --max-time 10 http://${Service}:${Port}/v3/api-docs"
    $r = Invoke-Compose -Arguments @('exec', '-T', 'gateway', 'sh', '-lc', $cmd)
    if ($r.Code -ne 0) {
      Add-Check $Name 'FAIL' ("compose exec :: " + ($r.Output -join ' '))
      return
    }
    $text = ($r.Output -join '')
    if ($text.Length -gt 100 -and $text -match '"openapi"') {
      Add-Check $Name 'PASS' "${Service}:${Port} bytes=$($text.Length)"
    } else {
      Add-Check $Name 'FAIL' "unexpected docs payload len=$($text.Length)"
    }
  }

  Check-InternalOpenApi 'trade_openapi_internal' 'trade' 9005
  Check-InternalHealth 'inventory_health_internal' 'inventory-fulfillment' 9003
  Check-InternalHealth 'aftersale_health_internal' 'aftersale' 9008
  Check-InternalHealth 'settlement_health_internal' 'settlement' 9010
  Check-InternalHealth 'gateway_health_internal' 'gateway' 8080
}

# SKIP must not be treated as PASS; any SKIP on required checks counts as failure for exit code.
$requiredSkipped = @($checks | Where-Object { $_.status -eq 'SKIP' }).Count
$exitFail = ($fail -gt 0) -or ($requiredSkipped -gt 0)

$summary = [ordered]@{
  generatedAt   = (Get-Date).ToString('o')
  baseUrl       = $BaseUrl
  composeProject = $ComposeProject
  pass          = $pass
  fail          = $fail
  skip          = $skip
  result        = if ($exitFail) { 'FAIL' } else { 'PASS' }
  checks        = @($checks)
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ReportJson -Encoding UTF8

Write-Host ''
Write-Host "SUMMARY result=$($summary.result) pass=$pass fail=$fail skip=$skip baseUrl=$BaseUrl"
Write-Host "JSON=$ReportJson"
if ($exitFail) { exit 1 }
exit 0
