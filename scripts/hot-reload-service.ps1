# ?? Maven ?? + ??�?Docker ???? jar???? docker build�?
# ??:
#   powershell -File scripts\hot-reload-service.ps1 -Module geek-gateway -Container geek-mall-gateway-1
#   powershell -File scripts\hot-reload-service.ps1 -Module geek-ai -LocalPort 9011

param(
  [Parameter(Mandatory = $true)]
  [string]$Module,
  [string]$Container = "",
  [int]$LocalPort = 0,
  [switch]$SkipPackage
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Backend = Join-Path $Root "backend"
$Jar = Join-Path $Backend "$Module\target\$Module-0.1.0-SNAPSHOT.jar"

if ($LocalPort -gt 0) {
  # ????????�?Windows �?jar ?? repackage ??
  Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -and ($_.CommandLine -like "*$Module-0.1.0-SNAPSHOT.jar*") } |
    ForEach-Object {
      Write-Host "stop pid=$($_.ProcessId)"
      Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
  Start-Sleep -Seconds 2
}

if (-not $SkipPackage) {
  Write-Host "== mvn package $Module =="
  Push-Location $Backend
  try {
    & mvn -B -ntp -pl $Module -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven package failed: $Module" }
  } finally {
    Pop-Location
  }
}

if (-not (Test-Path $Jar)) {
  throw "Jar not found: $Jar"
}

if ($LocalPort -gt 0) {
  Write-Host "== start $Module on host :$LocalPort =="

  # Load repo .env first (LLM_API_KEY etc.), then fill connection defaults if missing
  $dotenv = Join-Path $Root '.env'
  if (Test-Path $dotenv) {
    Get-Content $dotenv | ForEach-Object {
      $line = $_.Trim()
      if (-not $line -or $line.StartsWith('#')) { return }
      $idx = $line.IndexOf('=')
      if ($idx -lt 1) { return }
      Set-Item -Path ("Env:" + $line.Substring(0, $idx).Trim()) -Value $line.Substring($idx + 1).Trim()
    }
  }
  $defaults = @{
    AI_MYSQL_URL = "jdbc:mysql://127.0.0.1:3306/geek_ai?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
    MYSQL_USER = "geek"
    MYSQL_PASSWORD = "geek_local_password"
    NACOS_ADDR = "127.0.0.1:8848"
    JWT_SECRET = "change-this-local-secret-at-least-32-bytes-long"
    ELASTICSEARCH_URL = "http://127.0.0.1:9200"
    PRODUCT_SERVICE_URL = "http://127.0.0.1:9002"
    TRADE_SERVICE_URL = "http://127.0.0.1:9005"
    SEARCH_SERVICE_URL = "http://127.0.0.1:9004"
    AFTERSALE_SERVICE_URL = "http://127.0.0.1:9008"
    AI_PORT = "$LocalPort"
  }
  foreach ($k in $defaults.Keys) {
    $cur = [Environment]::GetEnvironmentVariable($k, 'Process')
    if ([string]::IsNullOrWhiteSpace($cur) -or $k -eq 'AI_PORT' -or $k -like '*_URL' -or $k -eq 'NACOS_ADDR' -or $k -eq 'AI_MYSQL_URL') {
      Set-Item -Path "Env:$k" -Value $defaults[$k]
    }
  }
  if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('INTERNAL_SERVICE_KEY', 'Process'))) {
    throw 'INTERNAL_SERVICE_KEY must be set in the process environment or .env'
  }

  $work = Join-Path $Backend $Module
  Start-Process -FilePath "java" -ArgumentList @("-jar", $Jar) -WorkingDirectory $work -WindowStyle Hidden
  $ready = $false
  for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Seconds 1
    try {
      if ((Invoke-RestMethod "http://127.0.0.1:$LocalPort/actuator/health" -TimeoutSec 2).status -eq "UP") {
        $ready = $true
        break
      }
    } catch {}
  }
  if (-not $ready) { throw "$Module failed to become healthy on :$LocalPort" }
  Write-Host "OK host http://127.0.0.1:$LocalPort"
  return
}

if (-not $Container) {
  $guess = "geek-mall-" + ($Module -replace "^geek-", "") + "-1"
  if ($Module -eq "geek-auth-system") { $guess = "geek-mall-auth-system-1" }
  if ($Module -eq "geek-merchant-product") { $guess = "geek-mall-merchant-product-1" }
  if ($Module -eq "geek-inventory-fulfillment") { $guess = "geek-mall-inventory-fulfillment-1" }
  if ($Module -eq "geek-search-recommend") { $guess = "geek-mall-search-recommend-1" }
  if ($Module -eq "geek-service-center") { $guess = "geek-mall-service-center-1" }
  if ($Module -eq "geek-aftersale-service") { $guess = "geek-mall-aftersale-1" }
  $Container = $guess
}

Write-Host "== docker cp -> $Container =="
docker inspect $Container 1>$null 2>$null
if ($LASTEXITCODE -ne 0) { throw "Container not found: $Container" }

docker cp $Jar "${Container}:/app/app.jar"
if ($LASTEXITCODE -ne 0) { throw "docker cp failed" }
docker restart $Container | Out-Null
Write-Host "OK restarted $Container"
Write-Host "Tip: avoid 'docker compose build' on this machine; use this script instead."
