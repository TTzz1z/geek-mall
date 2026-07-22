# Isolated MySQL 8.4 clean-room Flyway verification for all 11 business databases.
# Uses Compose project geek-mall-flyway-cleanroom with dedicated volumes.
# Does NOT touch the demo geek-mall project data. Always tears down volumes on exit.
# Forbidden: flyway repair, baselineOnMigrate overrides, history table edits, DB dumps as proof.
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$Project = if ($env:FLYWAY_CLEANROOM_PROJECT) { $env:FLYWAY_CLEANROOM_PROJECT } else { 'geek-mall-flyway-cleanroom' }
$ComposeFile = Join-Path $Root 'docker-compose.yml'
$ReportDir = Join-Path $Root 'reports'
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
$ReportJson = Join-Path $ReportDir 'flyway-cleanroom-result.json'

$Databases = @(
  'geek_system',
  'geek_merchant_product',
  'geek_inventory',
  'geek_search',
  'geek_trade',
  'geek_member',
  'geek_marketing',
  'geek_aftersale',
  'geek_service',
  'geek_settlement',
  'geek_ai'
)

$ServiceDbMap = [ordered]@{
  'auth-system'           = @{ Db = 'geek_system'; Port = 9001 }
  'merchant-product'      = @{ Db = 'geek_merchant_product'; Port = 9002 }
  'inventory-fulfillment' = @{ Db = 'geek_inventory'; Port = 9003 }
  'search-recommend'      = @{ Db = 'geek_search'; Port = 9004 }
  'trade'                 = @{ Db = 'geek_trade'; Port = 9005 }
  'member'                = @{ Db = 'geek_member'; Port = 9006 }
  'marketing'             = @{ Db = 'geek_marketing'; Port = 9007 }
  'aftersale'             = @{ Db = 'geek_aftersale'; Port = 9008 }
  'service-center'        = @{ Db = 'geek_service'; Port = 9009 }
  'settlement'            = @{ Db = 'geek_settlement'; Port = 9010 }
  'ai'                    = @{ Db = 'geek_ai'; Port = 9011 }
}

$Batches = @(
  @('auth-system', 'merchant-product', 'inventory-fulfillment', 'trade'),
  @('member', 'marketing', 'aftersale', 'service-center'),
  @('settlement', 'search-recommend', 'ai')
)

$Infra = @('mysql', 'redis', 'rabbitmq', 'elasticsearch', 'minio', 'nacos')

$SeedMetrics = [ordered]@{
  accounts           = @{ Db = 'geek_system'; Sql = 'SELECT COUNT(*) FROM sys_user' }
  products           = @{ Db = 'geek_merchant_product'; Sql = 'SELECT COUNT(*) FROM product_spu' }
  skus               = @{ Db = 'geek_merchant_product'; Sql = 'SELECT COUNT(*) FROM product_sku' }
  stockRows          = @{ Db = 'geek_inventory'; Sql = 'SELECT COUNT(*) FROM inventory_sku_stock' }
  addresses          = @{ Db = 'geek_member'; Sql = 'SELECT COUNT(*) FROM member_user_address' }
  couponTemplates    = @{ Db = 'geek_marketing'; Sql = 'SELECT COUNT(*) FROM marketing_coupon_template' }
  knowledgeDocuments = @{ Db = 'geek_ai'; Sql = 'SELECT COUNT(*) FROM knowledge_document' }
  knowledgeChunks    = @{ Db = 'geek_ai'; Sql = 'SELECT COUNT(*) FROM knowledge_chunk' }
}

$result = [ordered]@{
  generatedAt    = (Get-Date).ToString('o')
  project        = $Project
  overall        = 'FAIL'
  rounds         = @()
  databases      = @()
  seedRound1     = $null
  seedRound2     = $null
  errors         = [System.Collections.Generic.List[string]]::new()
  cleanedUp      = $false
}

$script:cleaned = $false

function Write-Info([string]$Message) {
  Write-Host "[CLEANROOM] $Message"
  try { [Console]::Out.Flush() } catch {}
}

function Invoke-Compose {
  param([string[]]$Arguments)
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $raw = & docker compose -p $Project -f $ComposeFile @Arguments 2>&1
    $code = $LASTEXITCODE
    # IMPORTANT: never pipe a single string into ForEach-Object (it enumerates chars).
    $lines = [System.Collections.Generic.List[string]]::new()
    if ($null -ne $raw) {
      $items = if ($raw -is [System.Array]) { $raw } else { @($raw) }
      foreach ($item in $items) {
        foreach ($piece in (("$item" -split "`r?`n"))) {
          if ($null -ne $piece -and "$piece".Length -gt 0) { [void]$lines.Add("$piece") }
        }
      }
    }
    return [pscustomobject]@{
      Code   = $code
      Output = $lines.ToArray()
    }
  } finally {
    $ErrorActionPreference = $prev
  }
}

function Invoke-MySql {
  param(
    [string]$Database,
    [string]$Sql
  )
  if ($Sql.Contains("'")) {
    throw "Internal MySQL query must not contain single quotes: $Sql"
  }
  # Match demo-reset.ps1 quoting: whole -e payload wrapped in single quotes for sh.
  $command = 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -s -D ' + $Database + " -e '$Sql'"
  $r = Invoke-Compose -Arguments @('exec', '-T', 'mysql', 'sh', '-lc', $command)
  if ($r.Code -ne 0) {
    throw ("mysql failed: " + ($r.Output -join ' '))
  }
  $lines = [System.Collections.Generic.List[string]]::new()
  foreach ($item in @($r.Output)) {
    $text = "$item".Trim()
    if (-not $text) { continue }
    if ($text -match 'Using a password') { continue }
    $lines.Add($text)
  }
  [string[]]$out = $lines.ToArray()
  return $out
}

function Invoke-MySqlScalar {
  param([string]$Db, [string]$Sql)
  [string[]]$rows = @(Invoke-MySql -Database $Db -Sql $Sql)
  $number = $rows | Where-Object { $_ -match '^-?\d+$' } | Select-Object -Last 1
  if ($null -eq $number) { return $null }
  return [int64]$number
}

function Invoke-MySqlLines {
  param([string]$Db, [string]$Sql)
  [string[]]$rows = @(Invoke-MySql -Database $Db -Sql $Sql)
  return $rows
}

function Get-GeekDatabaseNames {
  [string[]]$names = @(Invoke-MySqlLines -Db 'mysql' -Sql 'SHOW DATABASES')
  return @($names | Where-Object { $_ -match '^geek_' })
}

function Get-FlywayReport {
  param([string]$Db)
  $failCount = Invoke-MySqlScalar $Db 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0'
  $vCount = Invoke-MySqlScalar $Db 'SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL AND LENGTH(version) > 0 AND success = 1'
  $rCount = Invoke-MySqlScalar $Db 'SELECT COUNT(*) FROM flyway_schema_history WHERE (version IS NULL OR LENGTH(version) = 0) AND success = 1'
  $total = Invoke-MySqlScalar $Db 'SELECT COUNT(*) FROM flyway_schema_history'
  $maxRows = Invoke-MySqlLines $Db 'SELECT COALESCE(MAX(CAST(version AS DECIMAL(10,2))),0) FROM flyway_schema_history WHERE version IS NOT NULL AND LENGTH(version) > 0'
  $maxVersion = if ($maxRows.Count -gt 0) { $maxRows[0] } else { '0' }
  $failedRows = @()
  if ($failCount -gt 0) {
    $failedRows = Invoke-MySqlLines $Db 'SELECT CONCAT(installed_rank, CHAR(58), IFNULL(version, CHAR(82)), CHAR(58), script, CHAR(58), success) FROM flyway_schema_history WHERE success = 0'
  }
  return [ordered]@{
    database          = $Db
    historyRows       = $total
    versionedSuccess  = $vCount
    repeatableSuccess = $rCount
    failedMigrations  = $failCount
    maxVersion        = "$maxVersion"
    failedDetails     = @($failedRows)
  }
}

function Get-SeedSnapshot {
  $snap = [ordered]@{}
  foreach ($key in $SeedMetrics.Keys) {
    $m = $SeedMetrics[$key]
    $snap[$key] = Invoke-MySqlScalar $m.Db $m.Sql
  }
  return $snap
}

function Test-ComposeServiceHealthy {
  param([string]$Service)
  $ps = Invoke-Compose -Arguments @('ps', '--format', 'json', $Service)
  if ($ps.Code -ne 0 -or $ps.Output.Count -eq 0) { return $false }
  foreach ($line in $ps.Output) {
    # Avoid ConvertFrom-Json: compose JSON may contain non-JSON ellipsis glyphs from truncation.
    if ($line -match '"Health"\s*:\s*"healthy"' -or $line -match '\(healthy\)') { return $true }
  }
  return $false
}

function Wait-ServiceHealthy {
  param([string]$Service, [int]$TimeoutSec = 300)
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  $port = $ServiceDbMap[$Service].Port
  while ((Get-Date) -lt $deadline) {
    if (Test-ComposeServiceHealthy -Service $Service) { return $true }
    $healthCurl = Invoke-Compose -Arguments @(
      'exec', '-T', $Service, 'sh', '-lc',
      "curl -fsS --max-time 3 http://127.0.0.1:${port}/actuator/health"
    )
    if ($healthCurl.Code -eq 0 -and (($healthCurl.Output -join '') -match '"status"\s*:\s*"UP"')) {
      return $true
    }
    Start-Sleep -Seconds 5
  }
  return $false
}

function Wait-MysqlReady {
  param([int]$TimeoutSec = 360)
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    if (Test-ComposeServiceHealthy -Service 'mysql') {
      $r = Invoke-Compose -Arguments @(
        'exec', '-T', 'mysql', 'sh', '-lc',
        'mysqladmin ping -h 127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" --silent'
      )
      if ($r.Code -eq 0) {
        try {
          $geek = @(Get-GeekDatabaseNames)
          if ($geek.Count -ge 11) { return $true }
          Write-Info ("MySQL healthy but geek_* databases={0} names=[{1}] (waiting for init)" -f $geek.Count, ($geek -join ','))
        } catch {
          Write-Info "MySQL ping ok, schema probe pending: $_"
        }
      }
    } else {
      Write-Info 'waiting for mysql container healthy...'
    }
    Start-Sleep -Seconds 5
  }
  return $false
}

function Assert-DatabasesExist {
  $names = @(Get-GeekDatabaseNames)
  foreach ($db in $Databases) {
    if ($names -notcontains $db) {
      throw "database missing after init: $db (have: $($names -join ','))"
    }
  }
}

function Start-Batch {
  param([string[]]$Services)
  Write-Info ("starting batch: " + ($Services -join ', '))
  # Prefer already-built demo images retagged for this project; do not rebuild unless missing.
  $r = Invoke-Compose -Arguments (@('up', '-d', '--no-deps', '--no-build') + $Services)
  if ($r.Code -ne 0) {
    Write-Info 'prebuilt images missing; building batch images'
    $b = Invoke-Compose -Arguments (@('build') + $Services)
    if ($b.Code -ne 0) { throw ("compose build failed: " + ($b.Output -join "`n")) }
    $r = Invoke-Compose -Arguments (@('up', '-d', '--no-deps', '--no-build') + $Services)
  }
  if ($r.Code -ne 0) {
    throw ("compose up batch failed: " + ($r.Output -join "`n"))
  }
  foreach ($svc in $Services) {
    Write-Info "waiting healthy: $svc"
    if (-not (Wait-ServiceHealthy -Service $svc -TimeoutSec 420)) {
      $logs = Invoke-Compose -Arguments @('logs', '--tail', '80', $svc)
      throw ("service not healthy: $svc`n" + ($logs.Output -join "`n"))
    }
    Write-Info "healthy: $svc"
  }
}

function Invoke-Round {
  param([string]$RoundName, [switch]$RestartApps)

  Write-Info "=== $RoundName ==="
  $round = [ordered]@{
    name       = $RoundName
    startedAt  = (Get-Date).ToString('o')
    batches    = @()
    databases  = @()
    seed       = $null
    success    = $false
  }

  if ($RestartApps) {
    $appNames = @($ServiceDbMap.Keys)
    Write-Info 'restarting all app services for second-pass validate'
    $null = Invoke-Compose -Arguments (@('up', '-d', '--no-deps', '--no-build', '--force-recreate') + $appNames)
    foreach ($svc in $appNames) {
      if (-not (Wait-ServiceHealthy -Service $svc -TimeoutSec 420)) {
        throw "second round service not healthy: $svc"
      }
    }
  } else {
    foreach ($batch in $Batches) {
      $batchInfo = [ordered]@{ services = @($batch); ok = $false }
      Start-Batch -Services $batch
      $batchInfo.ok = $true
      $round.batches += $batchInfo
    }
  }

  $dbReports = @()
  foreach ($db in $Databases) {
    # Ensure history table exists
    $tables = @(Invoke-MySqlLines $db 'SHOW TABLES')
    if ($tables -notcontains 'flyway_schema_history') {
      throw "flyway_schema_history missing in $db"
    }
    $rep = Get-FlywayReport -Db $db
    if ($rep.failedMigrations -gt 0) {
      throw ("failed migrations in ${db}: " + ($rep.failedDetails -join '; '))
    }
    if ($rep.versionedSuccess -lt 1 -and $db -ne '') {
      # every business DB must have at least one versioned migration
      throw "no successful versioned migrations in $db"
    }
    $dbReports += $rep
    Write-Info ("DB {0}: versioned={1} repeatable={2} failed={3} maxV={4}" -f $db, $rep.versionedSuccess, $rep.repeatableSuccess, $rep.failedMigrations, $rep.maxVersion)
  }

  $seed = Get-SeedSnapshot
  Write-Info ("seed snapshot: " + ($seed.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ', ')

  $round.databases = $dbReports
  $round.seed = $seed
  $round.finishedAt = (Get-Date).ToString('o')
  $round.success = $true
  return $round
}

function Invoke-Cleanup {
  param([switch]$Preparatory)
  Write-Info "cleanup: docker compose -p $Project down -v"
  $r = Invoke-Compose -Arguments @('down', '-v', '--remove-orphans')
  if (-not $Preparatory) {
    $script:cleaned = $true
    $result.cleanedUp = ($r.Code -eq 0)
  }
  if ($r.Code -ne 0) {
    $msg = 'cleanup failed: ' + ($r.Output -join ' ')
    if (-not $Preparatory) { $result.errors.Add($msg) }
    Write-Info $msg
  }
}

try {
  if ($Project -eq 'geek-mall') {
    throw 'Refusing to run clean-room against the demo project name geek-mall'
  }

  # Ensure we do not accidentally operate on demo containers by project name isolation.
  Write-Info "using isolated Compose project: $Project"
  Invoke-Cleanup -Preparatory

  # Retag demo images so cleanroom project can start without a full rebuild.
  foreach ($svc in @($ServiceDbMap.Keys)) {
    $src = "geek-mall-${svc}:latest"
    $dst = "${Project}-${svc}:latest"
    $null = & docker image inspect $src 2>&1
    if ($LASTEXITCODE -eq 0) {
      & docker tag $src $dst 2>&1 | Out-Null
    }
  }

  Write-Info 'starting infrastructure (fresh volumes)'
  $upInfra = Invoke-Compose -Arguments (@('up', '-d') + $Infra)
  if ($upInfra.Code -ne 0) {
    throw ("infra up failed: " + ($upInfra.Output -join "`n"))
  }
  if (-not (Wait-MysqlReady)) { throw 'MySQL 8.4 did not become ready with 11 geek_* databases' }
  Assert-DatabasesExist
  Write-Info 'all 11 databases created from init-databases.sql'

  $round1 = Invoke-Round -RoundName 'round1-cold-start'
  $result.rounds += $round1
  $result.seedRound1 = $round1.seed
  $result.databases = $round1.databases

  $round2 = Invoke-Round -RoundName 'round2-restart-validate' -RestartApps
  $result.rounds += $round2
  $result.seedRound2 = $round2.seed

  # Compare: versioned counts stable; seed counts identical; no failures
  foreach ($db in $Databases) {
    $a = @($round1.databases | Where-Object { $_.database -eq $db })[0]
    $b = @($round2.databases | Where-Object { $_.database -eq $db })[0]
    if ($a.versionedSuccess -ne $b.versionedSuccess) {
      throw "versioned migration count changed on restart for ${db}: $($a.versionedSuccess) -> $($b.versionedSuccess)"
    }
    if ($b.failedMigrations -gt 0) {
      throw "round2 failures in $db"
    }
    if ($b.historyRows -lt $a.historyRows) {
      throw "history rows shrank for $db"
    }
  }

  foreach ($key in $SeedMetrics.Keys) {
    if ($result.seedRound1[$key] -ne $result.seedRound2[$key]) {
      throw "seed metric $key changed: $($result.seedRound1[$key]) -> $($result.seedRound2[$key]) (repeatable seed not idempotent)"
    }
    if ($null -eq $result.seedRound1[$key] -or $result.seedRound1[$key] -lt 0) {
      throw "seed metric $key invalid"
    }
  }

  $result.overall = 'PASS'
  Write-Info 'OVERALL PASS'
}
catch {
  $msg = "$_"
  $result.errors.Add($msg)
  $result.overall = 'FAIL'
  Write-Host "[CLEANROOM][FAIL] $msg"
}
finally {
  try {
    if (-not $script:cleaned) { Invoke-Cleanup }
  } catch {
    $result.errors.Add("cleanup exception: $_")
    $result.cleanedUp = $false
  }
  $result.finishedAt = (Get-Date).ToString('o')
  $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportJson -Encoding UTF8
  Write-Host ''
  Write-Host "SUMMARY result=$($result.overall) project=$Project cleanedUp=$($result.cleanedUp)"
  Write-Host "JSON=$ReportJson"
}

if ($result.overall -ne 'PASS') { exit 1 }
exit 0
