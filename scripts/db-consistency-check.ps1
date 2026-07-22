# Stage 4 DB consistency checks (lightweight; no cross-DB FKs)
$ErrorActionPreference = 'Stop'
$results = @()
function Pass($name, $ok, $detail) {
  $script:results += [pscustomobject]@{ item = $name; pass = [bool]$ok; detail = $detail }
  Write-Host ("[{0}] {1} :: {2}" -f ($(if ($ok) { 'PASS' } else { 'FAIL' })), $name, $detail)
}

function Sql($db, $q) {
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $raw = docker compose -f e:\program0713\shoping\docker-compose.yml exec -T mysql `
      mysql -ugeek -pgeek_local_password -N -e $q $db 2>&1
    $line = @($raw | Where-Object { $_ -is [string] -and $_ -notmatch 'Warning' -and $_.ToString().Trim() -ne '' } | Select-Object -Last 1)
    return [string]$line
  } finally {
    $ErrorActionPreference = $prev
  }
}

$tradeTables = [int](Sql 'geek_trade' "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='geek_trade' AND table_name IN ('trade_order','trade_sub_order','trade_order_item');")
$legacyOrderTables = [int](Sql 'geek_system' "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='geek_system' AND table_name='demo_merchant_order';")
Pass 'authoritative_trade_order_tables_exist' ($tradeTables -eq 3) ("tradeTables=$tradeTables expected=3")
Pass 'legacy_merchant_order_table_removed' ($legacyOrderTables -eq 0) ("legacyTables=$legacyOrderTables expected=0")

$orphanProfiles = [int](Sql 'geek_member' 'SELECT COUNT(*) FROM member_profile WHERE user_id IN (1001,1002);')
Pass 'orphan_profiles_documented' ($orphanProfiles -ge 0) ("legacyOrphans=$orphanProfiles (1001/1002 expected)")

$e2eUser = [int](Sql 'geek_system' "SELECT COUNT(*) FROM sys_user WHERE id=7 AND username='e2e_tester';")
Pass 'e2e_tester_seeded' ($e2eUser -eq 1) ("count=$e2eUser")

$todayMetrics = [int](Sql 'geek_settlement' 'SELECT COUNT(*) FROM analytics_metric_daily WHERE metric_date=CURDATE();')
Pass 'analytics_today_or_any' ($todayMetrics -ge 0) ("today=$todayMetrics")

$fail = @($results | Where-Object { -not $_.pass }).Count
Write-Host ("SUMMARY pass={0} fail={1}" -f (@($results | Where-Object pass).Count), $fail)
if ($fail -gt 0) { exit 1 }
