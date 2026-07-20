param([switch]$ConfirmReset)
if (-not $ConfirmReset) { throw 'Use -ConfirmReset to remove local Geek Mall volumes.' }
docker compose down --volumes --remove-orphans
docker compose up -d mysql redis rabbitmq nacos elasticsearch minio
Write-Host 'Demo infrastructure reset. Start the application services to re-run Flyway, repeatable seed and search reconciliation.'
