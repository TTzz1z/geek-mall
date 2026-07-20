#!/usr/bin/env sh
set -eu
mkdir -p backups
stamp=$(date +%Y%m%d-%H%M%S)
root_pwd=${MYSQL_ROOT_PASSWORD:-change-root-password}
dbs="geek_system geek_merchant_product geek_inventory geek_search geek_trade geek_member geek_marketing geek_aftersale geek_service geek_settlement geek_ai"
for db in $dbs; do
  file="backups/${db}-${stamp}.sql"
  docker compose exec -T mysql sh -c "exec mysqldump -uroot -p\"$root_pwd\" --single-transaction --routines --triggers $db" > "$file"
  sha256sum "$file" > "$file.sha256"
  echo "Created $file"
done
echo "stamp=$stamp"
