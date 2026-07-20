# Contract check: event samples + critical OpenAPI surfaces
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$schemaPath = Join-Path $Root 'contracts\events\domain-event.schema.json'
$sampleDir = Join-Path $Root 'contracts\events\samples'
$schema = Get-Content $schemaPath -Raw -Encoding UTF8 | ConvertFrom-Json
$required = @($schema.required)
$allowedTypes = @($schema.properties.eventType.enum)
$fail = 0
$pass = 0

Get-ChildItem $sampleDir -Filter *.json | ForEach-Object {
  $obj = Get-Content $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
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
  if ($ok) { $pass++; Write-Host "[PASS] $($_.Name)" }
  else { $fail++; Write-Host "[FAIL] $($_.Name) :: $($reasons -join '; ')" }
}

# Checkout contract sample: addressId required by PreviewRequest
$checkoutSample = Join-Path $Root 'contracts\http\checkout-preview.request.json'
if (Test-Path $checkoutSample) {
  $req = Get-Content $checkoutSample -Raw -Encoding UTF8 | ConvertFrom-Json
  if ($null -ne $req.addressId) { $pass++; Write-Host '[PASS] checkout_preview_addressId' }
  else { $fail++; Write-Host '[FAIL] checkout_preview_addressId :: missing addressId' }
} else {
  $fail++; Write-Host '[FAIL] checkout_preview_addressId :: sample missing'
}

# Gateway route source must include admin fulfillments
$gwYml = Join-Path $Root 'backend\geek-gateway\src\main\resources\application.yml'
$gwText = Get-Content $gwYml -Raw -Encoding UTF8
if ($gwText -match '/api/v1/admin/fulfillments') { $pass++; Write-Host '[PASS] gateway_fulfillments_route' }
else { $fail++; Write-Host '[FAIL] gateway_fulfillments_route' }

# Runtime OpenAPI / health for critical services (trade must expose docs; others health)
function Check-OpenApi($name, $url) {
  try {
    $docs = Invoke-WebRequest $url -TimeoutSec 8 -UseBasicParsing
    if ($docs.StatusCode -eq 200 -and $docs.Content.Length -gt 100) {
      $script:pass++; Write-Host "[PASS] ${name}_openapi"
    } else {
      $script:fail++; Write-Host "[FAIL] ${name}_openapi"
    }
  } catch {
    $script:fail++; Write-Host ("[FAIL] ${name}_openapi :: " + $_.Exception.Message)
  }
}

function Check-Health($name, $url) {
  try {
    $h = Invoke-RestMethod $url -TimeoutSec 8
    if ($h.status -eq 'UP') { $script:pass++; Write-Host "[PASS] ${name}_health" }
    else { $script:fail++; Write-Host "[FAIL] ${name}_health :: $($h.status)" }
  } catch {
    $script:fail++; Write-Host ("[FAIL] ${name}_health :: " + $_.Exception.Message)
  }
}

Check-OpenApi 'trade' 'http://127.0.0.1:9005/v3/api-docs'
Check-Health 'inventory' 'http://127.0.0.1:9003/actuator/health'
Check-Health 'aftersale' 'http://127.0.0.1:9008/actuator/health'
Check-Health 'settlement' 'http://127.0.0.1:9010/actuator/health'
Check-Health 'gateway' 'http://127.0.0.1:8080/actuator/health'

Write-Host "SUMMARY pass=$pass fail=$fail"
if ($fail -gt 0) { exit 1 }
