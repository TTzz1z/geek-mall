#!/usr/bin/env sh
set -eu
if [ "${CONFIRM_RESET:-}" != "YES" ]; then
  echo "Set CONFIRM_RESET=YES to remove local Geek Mall volumes and rebuild the demo."
  exit 2
fi
docker compose down --volumes --remove-orphans
docker compose up -d mysql redis rabbitmq nacos elasticsearch minio
echo "Demo infrastructure reset. Start the application services to re-run Flyway, repeatable seed and search reconciliation."
