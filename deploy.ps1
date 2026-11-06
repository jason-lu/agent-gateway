# Deploy agent-web to the Ubuntu server: build frontend + jar, upload, restart, health check.
# Usage: .\deploy.ps1
# Requires: Node.js + npm, JDK 25 (gradle toolchain), ssh/scp key access to $Server.

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$Server    = 'flink@kuanslab.com'
$RemoteDir = '/opt/personal-assistant'
$Service   = 'agent-web'
$HealthUrl = 'https://kuanslab.com/api/v1/health'

$Root = Split-Path -Parent $PSScriptRoot
$Jar  = Join-Path $PSScriptRoot 'web\build\libs\agent-web-1.0-SNAPSHOT.jar'

Write-Host '==> build frontend'
Push-Location (Join-Path $Root 'frontend')
try {
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "frontend build failed ($LASTEXITCODE)" }
} finally {
    Pop-Location
}

Write-Host '==> build jar'
& (Join-Path $PSScriptRoot 'gradlew.bat') :web:bootJar
if ($LASTEXITCODE -ne 0) { throw "bootJar failed ($LASTEXITCODE)" }

Write-Host '==> upload jar + .env + mcp.json'
scp -o BatchMode=yes $Jar (Join-Path $PSScriptRoot '.env') (Join-Path $PSScriptRoot 'mcp.json') "${Server}:${RemoteDir}/"
if ($LASTEXITCODE -ne 0) { throw "scp failed ($LASTEXITCODE)" }

Write-Host "==> restart $Service"
ssh -o BatchMode=yes $Server "sudo systemctl restart $Service"
if ($LASTEXITCODE -ne 0) { throw "restart failed ($LASTEXITCODE)" }

Write-Host '==> health check'
$up = $false
for ($i = 1; $i -le 30; $i++) {
    Start-Sleep -Seconds 2
    try {
        $health = Invoke-RestMethod -Uri $HealthUrl -TimeoutSec 5
        if ($health.status -eq 'UP') {
            Write-Host ("==> {0}" -f ($health | ConvertTo-Json -Compress))
            $up = $true
            break
        }
    } catch {
        # service still starting
    }
}
if (-not $up) { throw 'health check timed out after 60s' }

Write-Host '==> deployed'
