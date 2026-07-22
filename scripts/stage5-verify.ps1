# Stage 5 (tier A): compose/LLM/payment switches + optional runtime DeepSeek smoke
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$results = @()
function Pass($name, $ok, $detail) {
  $script:results += [pscustomobject]@{ item = $name; pass = [bool]$ok; detail = $detail }
  Write-Host ("[{0}] {1} :: {2}" -f ($(if ($ok) { 'PASS' } else { 'FAIL' })), $name, $detail)
}

$compose = Get-Content "$Root\docker-compose.yml" -Raw
Pass 'compose_ai_uri_default' ($compose -match 'AI_SERVICE_URI: \$\{AI_SERVICE_URI:-http://ai:9011\}') 'gateway AI_SERVICE_URI'
Pass 'compose_ai_depends' ($compose -match '(?s)gateway:.*?depends_on:.*?ai:') 'gateway depends_on ai'
Pass 'compose_rabbit_env' ($compose -match 'RABBITMQ_DEFAULT_USER: \$\{RABBITMQ_USER') 'rabbit env'
Pass 'compose_llm_env' ($compose -match 'LLM_API_KEY: \$\{LLM_API_KEY') 'ai LLM env'
Pass 'compose_payment_flags' ($compose -match 'PAYMENT_WECHAT_ENABLED' -and $compose -match 'PAYMENT_ALIPAY_ENABLED') 'trade payment flags'

$ex = Get-Content "$Root\.env.example" -Raw
Pass 'env_example_llm' ($ex -match 'LLM_MODEL=deepseek-v4-flash' -and $ex -match 'MILVUS_ENABLED=false') '.env.example'
Pass 'env_example_no_secret_key' ($ex -notmatch 'sk-[a-zA-Z0-9]{20,}') 'example has no real API key'

$pay = Get-Content "$Root\backend\geek-trade\src\main\java\com\geekmall\trade\PaymentChannelAdapter.java" -Raw
Pass 'payment_wechat_stub' ($pay -match 'class WechatPaymentChannelAdapter' -and $pay -match 'PAYMENT_WECHAT|wechat.enabled') 'WECHAT adapter'
Pass 'payment_alipay_stub' ($pay -match 'class AlipayPaymentChannelAdapter') 'ALIPAY adapter'

$aiYml = Get-Content "$Root\backend\geek-ai\src\main\resources\application.yml" -Raw
Pass 'ai_yml_llm' ($aiYml -match 'base-url: \$\{LLM_BASE_URL' -and $aiYml -match 'deepseek-v4-flash') 'geek-ai yml'

Pass 'local_covers' (Test-Path "$Root\frontend\apps\mall-web\public\covers\geek-spu-10001.jpg") 'mall-web jpeg covers'
Pass 'stage5_docs' (
  (Test-Path "$Root\ENGINEERING.md") -and
  (Test-Path "$Root\docs\ACCEPTANCE.md")
) 'ENGINEERING + ACCEPTANCE'

# Runtime (stack must be up)
$Base = 'http://127.0.0.1:8080'
try {
  $login = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/auth/login/password" `
    -ContentType 'application/json' -Body '{"username":"shopper","password":"Geek@123"}' -TimeoutSec 20
  $token = [string]$login.data.accessToken
  Pass 'shopper_login' ([bool]$token) 'login'
  if ($token) {
    $h = @{ Authorization = "Bearer $token"; 'Content-Type' = 'application/json' }
    $conv = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/ai/conversations" -Headers $h `
      -Body '{"scene":"GUIDE"}' -TimeoutSec 30
    $no = [string]$conv.data.conversationNo
    Pass 'ai_conversation' ([bool]$no) "no=$no"
    if ($no) {
      $msg = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/ai/conversations/$no/messages" -Headers $h `
        -Body '{"message":"用一句话介绍极客商城","scene":"GUIDE"}' -TimeoutSec 90
      $reply = [string]$msg.data.content
      if (-not $reply) { $reply = [string]$msg.data.reply }
      $degraded = $false
      if ($null -ne $msg.data.degraded) { $degraded = [bool]$msg.data.degraded }
      if ($reply -match '已降级|本地降级') { $degraded = $true }
      $model = ''
      if ($msg.data.model -and $msg.data.model.modelName) { $model = [string]$msg.data.model.modelName }
      Pass 'ai_message' ([bool]$reply) ("model=$model degraded=$degraded len=$($reply.Length)")
      $hasKey = $false
      $llmEnabled = $true
      if (Test-Path "$Root\.env") {
        $envRaw = Get-Content "$Root\.env" -Raw
        $keyMatch = [regex]::Match($envRaw, '(?m)^\s*LLM_API_KEY\s*=\s*([^\r\n#]+)')
        if ($keyMatch.Success) {
          $keyValue = $keyMatch.Groups[1].Value.Trim().Trim('"').Trim("'")
          $hasKey = (-not [string]::IsNullOrWhiteSpace($keyValue) -and $keyValue -notmatch 'CHANGE_ME|YOUR_|example|placeholder')
        }
        $enabledMatch = [regex]::Match($envRaw, '(?m)^\s*LLM_ENABLED\s*=\s*([^\r\n#]+)')
        if ($enabledMatch.Success) { $llmEnabled = $enabledMatch.Groups[1].Value.Trim() -ne 'false' }
      }
      if ($hasKey -and $llmEnabled) {
        $remoteOk = (-not $degraded -and $msg.data.model.degraded -eq $false -and [string]$msg.data.plannerMode -eq 'LLM')
        Pass 'llm_remote_model' $remoteOk ("model=$model degraded=$degraded planner=$($msg.data.plannerMode)")
      } else {
        $fallbackOk = ($degraded -and $msg.data.model.degraded -eq $true -and [string]$msg.data.plannerMode -eq 'RULE' -and [string]$msg.data.fallbackReason -eq 'LLM_DISABLED')
        Pass 'llm_local_fallback' $fallbackOk ("model=$model degraded=$degraded planner=$($msg.data.plannerMode) reason=$($msg.data.fallbackReason)")
      }
    }
  }
} catch {
  Pass 'runtime_ai' $false $_.Exception.Message
}

$fail = @($results | Where-Object { -not $_.pass }).Count
Write-Host ("STAGE5 SUMMARY pass={0} fail={1}" -f (@($results | Where-Object pass).Count), $fail)
if ($fail -gt 0) { exit 1 }
