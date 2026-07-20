#!/usr/bin/env sh
set -eu
[ -f .env ] || cp .env.example .env
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d mysql redis rabbitmq nacos elasticsearch minio
docker compose ps
