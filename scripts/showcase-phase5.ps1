# Showcase Phase 5 lite: demo-hint scan + core trade smoke + login page checks
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$failed = $false

Write-Host '======== 1) scan-demo-hints ========'
python (Join-Path $Root 'scripts\scan-demo-hints.py')
if ($LASTEXITCODE -ne 0) { $failed = $true }

Write-Host ''
Write-Host '======== 2) e2e-core-flow (API) ========'
& powershell -NoProfile -File (Join-Path $Root 'scripts\e2e-core-flow.ps1')
if ($LASTEXITCODE -ne 0) { $failed = $true }

Write-Host ''
Write-Host '======== 3) login page surface ========'
function PageOk($name, $url, $mustNot) {
  try {
    $html = curl.exe -sL $url
    foreach ($m in $mustNot) {
      if ($html -match [regex]::Escape($m)) {
        Write-Host "[FAIL] $name contains: $m"
        $script:failed = $true
        return
      }
    }
    Write-Host "[PASS] $name :: $url"
  } catch {
    Write-Host "[FAIL] $name :: $($_.Exception.Message)"
    $script:failed = $true
  }
}

PageOk 'mall_auth_html' 'http://127.0.0.1:8088/auth/login' @('Geek@123')
PageOk 'admin_login_html' 'http://127.0.0.1:8089/login' @('Geek@123')

python (Join-Path $Root 'scripts\check-mall-surface.py')
if ($LASTEXITCODE -ne 0) { $failed = $true }

Write-Host ''
if ($failed) {
  Write-Host 'showcase-phase5: FAILED'
  exit 1
}
Write-Host 'showcase-phase5: OK'
exit 0
