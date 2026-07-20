# 只启动中间件（不 build 镜像，不占满本机）
# 用法: powershell -File scripts\dev-up-infra.ps1

$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

Write-Host "== docker compose up infra (no build) =="
docker compose up -d --no-build mysql redis rabbitmq elasticsearch nacos minio
if ($LASTEXITCODE -ne 0) { throw "infra up failed" }

Write-Host "Infra ready:"
Write-Host "  MySQL 3306  Redis 6379  RabbitMQ 5672/15672"
Write-Host "  ES 9200  Nacos 8848  MinIO 9100/9101"
Write-Host ""
Write-Host "Next:"
Write-Host "  1) 本机: cd backend; mvn -pl geek-xxx -am package -DskipTests"
Write-Host "  2) 热替换: powershell -File scripts\hot-reload-service.ps1 -Module geek-gateway"
Write-Host "  3) 或本机跑 AI: powershell -File scripts\hot-reload-service.ps1 -Module geek-ai -LocalPort 9011"
