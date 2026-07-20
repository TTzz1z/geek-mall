#!/usr/bin/env sh
set -eu
file=${1:-}
db=${2:-}
[ -n "$file" ] && [ -f "$file" ] || { echo "Usage: scripts/restore.sh backups/file.sql [database]"; exit 2; }
if [ -f "$file.sha256" ]; then
  sha256sum -c "$file.sha256"
fi
if [ -z "$db" ]; then
  base=$(basename "$file")
  db=$(echo "$base" | sed -E 's/^(geek_[a-z_]+)-.*/\1/')
fi
root_pwd=${MYSQL_ROOT_PASSWORD:-change-root-password}
docker compose exec -T mysql sh -c "exec mysql -uroot -p\"$root_pwd\" $db" < "$file"
echo "Restored $file -> $db"
