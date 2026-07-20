# 性能冒烟：测量搜索/健康/AI 延迟；若本机有 jmeter 则额外跑 JMX
$ErrorActionPreference = 'Stop'
$Base = 'http://127.0.0.1:8080'
$outDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'perf\reports'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$report = Join-Path $outDir "perf-$stamp.json"

function Measure-Endpoint($name, $url, $headers, $n) {
  $samples = @()
  for ($i = 0; $i -lt $n; $i++) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
      $r = Invoke-WebRequest -Uri $url -Headers $headers -TimeoutSec 30 -UseBasicParsing
      $sw.Stop()
      $samples += [pscustomobject]@{ ms = $sw.ElapsedMilliseconds; code = [int]$r.StatusCode; ok = ($r.StatusCode -ge 200 -and $r.StatusCode -lt 400) }
    } catch {
      $sw.Stop()
      $samples += [pscustomobject]@{ ms = $sw.ElapsedMilliseconds; code = 0; ok = $false }
    }
  }
  $ok = @($samples | Where-Object ok)
  $sorted = @($ok | Sort-Object ms | ForEach-Object ms)
  $p95 = if ($sorted.Count -gt 0) { $sorted[[Math]::Min($sorted.Count - 1, [int][Math]::Ceiling($sorted.Count * 0.95) - 1)] } else { -1 }
  $err = $samples.Count - $ok.Count
  return [pscustomobject]@{
    name = $name; n = $n; success = $ok.Count; errors = $err
    avgMs = if ($ok.Count) { [int](($ok | Measure-Object ms -Average).Average) } else { -1 }
    p95Ms = $p95
  }
}

$loginBody = '{"username":"shopper","password":"Geek@123"}'
$login = Invoke-RestMethod -Method POST -Uri "$Base/api/v1/auth/login/password" -ContentType 'application/json' -Body $loginBody
$token = [string]$login.data.accessToken
$headers = @{ Authorization = "Bearer $token"; 'X-Client-Type' = 'WEB' }

$rows = @(
  (Measure-Endpoint 'health_gateway' "$Base/actuator/health" @{} 20),
  (Measure-Endpoint 'search' "$Base/api/v1/search/products?keyword=Air&page=1&pageSize=10" $headers 20)
)

# AI 首包（同步消息近似）
$conv = Invoke-RestMethod -Method POST -Uri "$Base/api/v1/ai/conversations" -Headers $headers -ContentType 'application/json' -Body '{"scene":"GUIDE"}'
$aiUrl = "$Base/api/v1/ai/conversations/$($conv.data.conversationNo)/messages"
$aiSamples = @()
for ($i = 0; $i -lt 5; $i++) {
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  try {
    Invoke-RestMethod -Method POST -Uri $aiUrl -Headers $headers -ContentType 'application/json' -Body '{"message":"hello","scene":"GUIDE"}' | Out-Null
    $sw.Stop(); $aiSamples += $sw.ElapsedMilliseconds
  } catch { $sw.Stop(); $aiSamples += -1 }
}
$aiOk = @($aiSamples | Where-Object { $_ -ge 0 }) | Sort-Object
$aiP95 = if ($aiOk.Count) { $aiOk[[Math]::Min($aiOk.Count - 1, [int][Math]::Ceiling($aiOk.Count * 0.95) - 1)] } else { -1 }
$rows += [pscustomobject]@{ name = 'ai_message'; n = 5; success = $aiOk.Count; errors = (5 - $aiOk.Count); avgMs = $(if ($aiOk.Count) { [int](($aiOk | Measure-Object -Average).Average) } else { -1 }); p95Ms = $aiP95 }

$targets = @{
  health_gateway = 500
  search = 800
  ai_message = 3000
}
$fail = 0
foreach ($r in $rows) {
  $limit = $targets[$r.name]
  $ok = ($r.errors -eq 0 -and $r.p95Ms -ge 0 -and $r.p95Ms -le $limit)
  if (-not $ok) { $fail++ }
  Write-Host ("[{0}] {1} p95={2}ms limit={3}ms errors={4}" -f ($(if ($ok) { 'PASS' } else { 'FAIL' })), $r.name, $r.p95Ms, $limit, $r.errors)
}

$jmeter = Get-Command jmeter -ErrorAction SilentlyContinue
$jmeterNote = 'jmeter not installed; used PowerShell sampler'
if ($jmeter) {
  $jmx = Join-Path (Split-Path -Parent $PSScriptRoot) 'perf\jmeter\geek-mall-smoke.jmx'
  $jtl = Join-Path $outDir "jmeter-$stamp.jtl"
  & jmeter -n -t $jmx -l $jtl -JBASE_URL=$Base | Out-Host
  $jmeterNote = "jmeter ran -> $jtl"
}

$payload = [ordered]@{
  generatedAt = (Get-Date).ToString('o')
  hardware = "$env:COMPUTERNAME / $([Environment]::ProcessorCount) CPU"
  rows = $rows
  jmeter = $jmeterNote
  fail = $fail
}
$payload | ConvertTo-Json -Depth 6 | Set-Content -Path $report -Encoding UTF8
Write-Host "REPORT $report"
Write-Host "SUMMARY fail=$fail"
if ($fail -gt 0) { exit 1 }
