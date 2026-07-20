# Demo bootstrap: infra + apps without image rebuild
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

if (-not (Test-Path '.env')) { Copy-Item '.env.example' '.env' }

function Invoke-ComposeUp([string[]]$Services) {
  # Docker may write orphan warnings to stderr; do not treat as terminating errors.
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $out = & docker compose up -d --no-build @Services 2>&1
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $prev
  }
  if ($code -ne 0) {
    $out | ForEach-Object { Write-Host $_ }
    throw "docker compose up failed exit=$code services=$($Services -join ',')"
  }
}

function Test-PortUp([int]$Port) {
  try {
    return ((Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 3).status -eq 'UP')
  } catch {
    return $false
  }
}

Write-Host '== infra / app containers (no rebuild) =='
Invoke-ComposeUp @('mysql','redis','rabbitmq','nacos','elasticsearch','minio')
# Prefer host hot-reload for AI when compose image is missing (common on this machine).
Invoke-ComposeUp @('auth-system','merchant-product','inventory-fulfillment','search-recommend','trade','member','marketing','aftersale','service-center','settlement')

$aiMode = 'compose'
$prevEa = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$aiUp = & docker compose up -d --no-build ai 2>&1
$aiCode = $LASTEXITCODE
$ErrorActionPreference = $prevEa
if ($aiCode -ne 0) {
  Write-Host 'compose ai unavailable (no image or port busy) - using host hot-reload'
  $aiMode = 'host'
  # Avoid port clash if a partial compose ai container exists
  & docker compose stop ai 2>&1 | Out-Null
  if (-not (Test-PortUp 9011)) {
    powershell -NoProfile -File (Join-Path $Root 'scripts\hot-reload-service.ps1') -Module geek-ai -LocalPort 9011 -SkipPackage
  } else {
    Write-Host 'ai already UP on :9011'
  }
  $env:AI_SERVICE_URI = 'http://host.docker.internal:9011'
  $env:AI_SERVICE_URL = 'http://host.docker.internal:9011'
} else {
  Start-Sleep -Seconds 3
  if (-not (Test-PortUp 9011)) {
    Write-Host 'compose ai started but not healthy - fallback host hot-reload'
    $aiMode = 'host'
    & docker compose stop ai 2>&1 | Out-Null
    powershell -NoProfile -File (Join-Path $Root 'scripts\hot-reload-service.ps1') -Module geek-ai -LocalPort 9011 -SkipPackage
    $env:AI_SERVICE_URI = 'http://host.docker.internal:9011'
    $env:AI_SERVICE_URL = 'http://host.docker.internal:9011'
  }
}

Write-Host "ai_mode=$aiMode"
# Recreate gateway so AI_SERVICE_* from process env / .env is applied
$prevEa = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& docker compose up -d --no-deps --force-recreate --no-build gateway 2>&1 | Out-Null
$ErrorActionPreference = $prevEa
Invoke-ComposeUp @('mall-web','admin-web')

Write-Host '== health matrix =='
@(8080,9001,9002,9003,9004,9005,9006,9007,9008,9009,9010,9011) | ForEach-Object {
  try { $s = (Invoke-RestMethod "http://127.0.0.1:$_/actuator/health" -TimeoutSec 3).status; "$_=$s" }
  catch { "$_=DOWN" }
}

Write-Host ''
Write-Host 'Demo URLs'
Write-Host '- Mall:    http://localhost:8088/'
Write-Host '- Admin:   http://localhost:8089/'
Write-Host '- AI page: http://localhost:8088/ai-shopping'
Write-Host '- API GW:  http://localhost:8080/'
Write-Host '- Grafana: http://localhost:3000/ (admin / geek_local_password)'
Write-Host 'Accounts: shopper / Geek@123 , admin / Geek@123'
