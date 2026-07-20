# Phase 7 aggregate verification
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$results = @()

function Pass($name, $ok, $detail) {
  $script:results += [pscustomobject]@{ item = $name; pass = [bool]$ok; detail = "$detail" }
  Write-Host ("[{0}] {1} :: {2}" -f ($(if ($ok) { 'PASS' } else { 'FAIL' })), $name, $detail)
}

function Run-Step($name, $file) {
  try {
    & powershell -NoProfile -File (Join-Path $Root $file)
    Pass $name ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
  } catch {
    Pass $name $false $_.Exception.Message
  }
}

Write-Host '== backend mvn verify =='
Push-Location (Join-Path $Root 'backend')
try {
  # Stop local AI jar lock if present
  Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -and ($_.CommandLine -like '*geek-ai-0.1.0-SNAPSHOT.jar*') } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
  & mvn -B -ntp verify
  Pass 'backend_mvn_verify' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
} finally { Pop-Location }

# Restart AI for live checks
powershell -NoProfile -File (Join-Path $Root 'scripts\hot-reload-service.ps1') -Module geek-ai -LocalPort 9011 -SkipPackage | Out-Host

Write-Host '== frontend =='
Push-Location (Join-Path $Root 'frontend')
try {
  & pnpm typecheck; $tc = $LASTEXITCODE
  & pnpm test; $tt = $LASTEXITCODE
  & pnpm build; $tb = $LASTEXITCODE
  Pass 'frontend_build' ($tc -eq 0 -and $tt -eq 0 -and $tb -eq 0) "typecheck=$tc test=$tt build=$tb"
} finally { Pop-Location }

& docker compose -f docker-compose.yml config --quiet; $c1 = $LASTEXITCODE
& docker compose -f docker-compose.yml -f docker-compose.test.yml config --quiet; $c2 = $LASTEXITCODE
& docker compose -f docker-compose.yml -f docker-compose.observability.yml config --quiet; $c3 = $LASTEXITCODE
Pass 'compose_config' ($c1 -eq 0 -and $c2 -eq 0 -and $c3 -eq 0) "main=$c1 test=$c2 obs=$c3"

Run-Step 'contract_check' 'scripts\contract-check.ps1'
Run-Step 'e2e_core_flow' 'scripts\e2e-core-flow.ps1'
Run-Step 'concurrency_verify' 'scripts\concurrency-verify.ps1'
Run-Step 'security_verify' 'scripts\security-verify.ps1'
Run-Step 'perf_run' 'scripts\perf-run.ps1'
Run-Step 'ai_regression' 'scripts\ai-regression.ps1'
Run-Step 'backup_restore_drill' 'scripts\backup-restore-drill.ps1'

try {
  docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --no-build prometheus blackbox grafana jaeger | Out-Null
  Start-Sleep -Seconds 5
  $prom = (Invoke-WebRequest 'http://127.0.0.1:9090/-/ready' -UseBasicParsing -TimeoutSec 5).StatusCode
  $graf = (Invoke-WebRequest 'http://127.0.0.1:3000/api/health' -UseBasicParsing -TimeoutSec 5).StatusCode
  Pass 'observability_stack' ($prom -eq 200 -and $graf -eq 200) "prometheus=$prom grafana=$graf"
} catch {
  Pass 'observability_stack' $false $_.Exception.Message
}

$down = @()
@(8080,9001,9002,9003,9004,9005,9008,9011) | ForEach-Object {
  try {
    $s = (Invoke-RestMethod "http://127.0.0.1:$_/actuator/health" -TimeoutSec 3).status
    if ($s -ne 'UP') { $down += "$_=$s" }
  } catch { $down += "$_=DOWN" }
}
Pass 'core_health' ($down.Count -eq 0) ($(if ($down.Count) { $down -join ',' } else { 'all UP' }))

$fail = @($results | Where-Object { -not $_.pass }).Count
$passN = @($results | Where-Object { $_.pass }).Count
Write-Host "PHASE7 SUMMARY pass=$passN fail=$fail total=$($results.Count)"
$results | Format-Table -AutoSize
New-Item -ItemType Directory -Force -Path (Join-Path $Root 'reports') | Out-Null
$results | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $Root 'reports\phase7-verify-result.json') -Encoding UTF8
if ($fail -gt 0) { exit 1 }
