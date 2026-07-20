# Attach observability stack so Grafana/Prometheus/Jaeger are not "orphan" containers.
# Usage:
#   powershell -File scripts\observability-up.ps1
#   powershell -File scripts\observability-up.ps1 -Down
param([switch]$Down)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$files = @('-f', 'docker-compose.yml', '-f', 'docker-compose.observability.yml')

$prev = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
  if ($Down) {
    & docker compose @files stop prometheus grafana jaeger blackbox 2>&1 | Out-Null
    Write-Host 'OK observability stopped'
    return
  }
  $out = & docker compose @files up -d --no-build prometheus grafana jaeger blackbox 2>&1
  $code = $LASTEXITCODE
} finally {
  $ErrorActionPreference = $prev
}
if ($code -ne 0) {
  $out | ForEach-Object { Write-Host $_ }
  throw "observability up failed exit=$code"
}

Write-Host 'OK observability attached (use both compose files together to avoid orphan warnings)'
Write-Host '- Grafana:     http://localhost:3000/  (admin / geek_local_password)'
Write-Host '- Prometheus:  http://localhost:9090/'
Write-Host '- Jaeger:      http://localhost:16686/'
Write-Host 'Note: later `docker compose up` without -f observability.yml will warn about orphans; re-run this script or always pass both -f files.'

