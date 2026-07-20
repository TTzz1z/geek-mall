# Build geek-mall-ai:latest from a locally packaged jar (no Maven inside Docker).
# Usage:
#   powershell -File scripts\build-ai-image.ps1
#   powershell -File scripts\build-ai-image.ps1 -SkipPackage
#   powershell -File scripts\build-ai-image.ps1 -SkipPackage -StartComposeAi
param(
  [switch]$SkipPackage,
  [switch]$StartComposeAi
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Backend = Join-Path $Root 'backend'
$Jar = Join-Path $Backend 'geek-ai\target\geek-ai-0.1.0-SNAPSHOT.jar'
$StageDir = Join-Path $Backend '.docker-stage'
$StageJar = Join-Path $StageDir 'app.jar'

if (-not $SkipPackage) {
  Push-Location $Backend
  try {
    & mvn -B -ntp -pl geek-ai -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'Maven package geek-ai failed' }
  } finally {
    Pop-Location
  }
}
if (-not (Test-Path $Jar)) { throw "Jar not found: $Jar" }

New-Item -ItemType Directory -Force -Path $StageDir | Out-Null
Copy-Item $Jar $StageJar -Force

Write-Host '== docker build geek-mall-ai:latest (prebuilt jar) =='
$prev = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
Push-Location $Backend
try {
  & docker build -f Dockerfile.prebuilt -t geek-mall-ai:latest . 2>&1 | ForEach-Object { Write-Host $_ }
  if ($LASTEXITCODE -ne 0) { throw "docker build failed exit=$LASTEXITCODE" }
} finally {
  Pop-Location
  $ErrorActionPreference = $prev
}

Write-Host 'OK image geek-mall-ai:latest'

if ($StartComposeAi) {
  Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -and ($_.CommandLine -like '*geek-ai-0.1.0-SNAPSHOT.jar*') } |
    ForEach-Object {
      Write-Host "stop host ai pid=$($_.ProcessId)"
      Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
  Start-Sleep -Seconds 2

  $env:AI_SERVICE_URI = 'http://ai:9011'
  $env:AI_SERVICE_URL = 'http://ai:9011'
  $ErrorActionPreference = 'Continue'
  & docker compose -f (Join-Path $Root 'docker-compose.yml') up -d --no-build ai 2>&1 | ForEach-Object { Write-Host $_ }
  & docker compose -f (Join-Path $Root 'docker-compose.yml') up -d --no-deps --force-recreate --no-build gateway 2>&1 | Out-Null
  $ErrorActionPreference = 'Stop'
  Write-Host 'OK compose ai + gateway (AI_SERVICE_URI=http://ai:9011)'
  Write-Host 'Tip: keep AI_SERVICE_URI/URL=http://ai:9011 in .env'
}
