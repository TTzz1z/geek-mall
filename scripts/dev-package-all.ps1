# 本机一次打包全部后端模块（不触发 Docker 构建）
$ErrorActionPreference = "Stop"
$Backend = Join-Path (Split-Path -Parent $PSScriptRoot) "backend"
Set-Location $Backend
Write-Host "== mvn package all modules =="
& mvn -B -ntp package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }
Write-Host "OK jars under backend/*/target/"
