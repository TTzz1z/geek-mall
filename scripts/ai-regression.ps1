# AI 回归：复用 phase6-verify
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
& powershell -File (Join-Path $Root 'scripts\phase6-verify.ps1')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host 'AI regression PASS'
