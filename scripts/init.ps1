if (-not (Test-Path -LiteralPath '.env')) { Copy-Item -LiteralPath '.env.example' -Destination '.env' }
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d mysql redis rabbitmq nacos elasticsearch minio
docker compose ps
