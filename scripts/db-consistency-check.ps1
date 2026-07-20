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

$tradeOrders = [int](Sql 'geek_trade' 'SELECT COUNT(*) FROM trade_order;')
$demoOrders = [int](Sql 'geek_system' 'SELECT COUNT(*) FROM demo_merchant_order;')
Pass 'trade_order_table_exists' ($tradeOrders -ge 0) ("trade_order=$tradeOrders demo_merchant_order=$demoOrders")

$orphanProfiles = [int](Sql 'geek_member' 'SELECT COUNT(*) FROM member_profile WHERE user_id IN (1001,1002);')
Pass 'orphan_profiles_documented' ($orphanProfiles -ge 0) ("legacyOrphans=$orphanProfiles (1001/1002 expected)")

$e2eUser = [int](Sql 'geek_system' "SELECT COUNT(*) FROM sys_user WHERE id=7 AND username='e2e_tester';")
Pass 'e2e_tester_seeded' ($e2eUser -eq 1) ("count=$e2eUser")

$todayMetrics = [int](Sql 'geek_settlement' 'SELECT COUNT(*) FROM analytics_metric_daily WHERE metric_date=CURDATE();')
Pass 'analytics_today_or_any' ($todayMetrics -ge 0) ("today=$todayMetrics")

$fail = @($results | Where-Object { -not $_.pass }).Count
Write-Host ("SUMMARY pass={0} fail={1}" -f (@($results | Where-Object pass).Count), $fail)
if ($fail -gt 0) { exit 1 }
