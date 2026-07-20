# Stage 4 verify: seed safety markers + e2e user + analytics dashboard + core API flow
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$results = @()
function Pass($name, $ok, $detail) {
  $script:results += [pscustomobject]@{ item = $name; pass = [bool]$ok; detail = $detail }
  Write-Host ("[{0}] {1} :: {2}" -f ($(if ($ok) { 'PASS' } else { 'FAIL' })), $name, $detail)
}

# 1) Seed files no longer overwrite runtime qty/points/coupon status
$inv = Get-Content "$Root\backend\geek-inventory-fulfillment\src\main\resources\db\migration\R__phase2_inventory_seed.sql" -Raw
Pass 'inventory_seed_no_physical_overwrite' ($inv -notmatch 'physical_qty=VALUES\(physical_qty\)') 'R__ inventory'
$mem = Get-Content "$Root\backend\geek-member\src\main\resources\db\migration\R__phase4_member_seed.sql" -Raw
Pass 'member_seed_insert_only' ($mem -match 'WHERE NOT EXISTS' -and $mem -notmatch 'point_balance=VALUES') 'R__ member'
$mkt = Get-Content "$Root\backend\geek-marketing\src\main\resources\db\migration\R__phase4_marketing_seed.sql" -Raw
Pass 'coupon_seed_no_status_reset' ($mkt -notmatch "status='UNUSED', user_id") 'R__ marketing coupon'
$stl = Get-Content "$Root\backend\geek-settlement\src\main\resources\db\migration\R__phase5_settlement_seed.sql" -Raw
Pass 'analytics_seed_no_value_overwrite' ($stl -match 'WHERE NOT EXISTS' -and $stl -notmatch 'metric_value=VALUES') 'R__ settlement metrics'

# 2) Docs markers (public docs)
Pass 'product_doc' (Test-Path "$Root\PRODUCT.md") 'PRODUCT.md'
Pass 'engineering_doc' (Test-Path "$Root\ENGINEERING.md") 'ENGINEERING.md'
Pass 'acceptance_doc' (Test-Path "$Root\docs\ACCEPTANCE.md") 'docs/ACCEPTANCE.md'
Pass 'ai_orchestration_doc' (Test-Path "$Root\docs\ai-orchestration.md") 'docs/ai-orchestration.md'
Pass 'deprecated_packages' (
  (Test-Path "$Root\frontend\packages\shared-utils\DEPRECATED.md") -and
  (Test-Path "$Root\frontend\packages\shared-icons\DEPRECATED.md") -and
  (Test-Path "$Root\frontend\packages\business-enums\DEPRECATED.md")
) 'packages'

# 3) Runtime API checks (requires stack up)
$Base = 'http://127.0.0.1:8080'
try {
  $loginBody = '{"username":"e2e_tester","password":"Geek@123"}'
  $login = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/auth/login/password" -ContentType 'application/json' -Body $loginBody -TimeoutSec 20
  $token = [string]$login.data.accessToken
  Pass 'e2e_tester_login' ([bool]$token) 'login'
  if ($token) {
    $h = @{ Authorization = "Bearer $token" }
    $adminLogin = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/auth/login/password" -ContentType 'application/json' -Body '{"username":"admin","password":"Geek@123"}' -TimeoutSec 20
    $adminToken = [string]$adminLogin.data.accessToken
    $ah = @{ Authorization = "Bearer $adminToken" }
    $dash = Invoke-RestMethod -Uri "$Base/api/v1/admin/analytics/dashboard" -Headers $ah -TimeoutSec 20
    Pass 'analytics_dashboard' ($null -ne $dash.data.overview) ("carryForward=$($dash.data.carryForward) metricDate=$($dash.data.metricDate)")
  }
} catch {
  Pass 'runtime_api' $false $_.Exception.Message
}

# 4) Nested scripts
& "$Root\scripts\db-consistency-check.ps1"
Pass 'db_consistency_script' ($LASTEXITCODE -eq 0) ("exit=$LASTEXITCODE")

$env:E2E_USERNAME = 'e2e_tester'
& "$Root\scripts\e2e-core-flow.ps1"
Pass 'e2e_core_flow' ($LASTEXITCODE -eq 0) ("exit=$LASTEXITCODE")

$fail = @($results | Where-Object { -not $_.pass }).Count
Write-Host ("STAGE4 SUMMARY pass={0} fail={1}" -f (@($results | Where-Object pass).Count), $fail)
if ($fail -gt 0) { exit 1 }
