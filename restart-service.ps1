# CPA Manage API Restart Service Script
$ErrorActionPreference = "Continue"
$privateKey = "$env:USERPROFILE\.ssh\id_rsa"
$jumpHost = "ubuntu@106.75.152.136"
$targetHost = "ubuntu@10.13.135.74"
$appDir = "/home/ubuntu/cpa-manage-api"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   CPA Manage API Restart Service" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Step 1: Stop service
Write-Host "[1/3] Stopping service..." -ForegroundColor Yellow
$stopCmd = "ssh -o StrictHostKeyChecking=no $targetHost 'sudo pkill -f cpa-manage-api || true'"
ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $stopCmd
if ($LASTEXITCODE -ne 0) {
    Write-Host "Stop service failed, but continue..." -ForegroundColor Yellow
}
Start-Sleep -Seconds 3
Write-Host "Service stopped" -ForegroundColor Green
Write-Host ""

# Step 2: Wait for process to exit completely
Write-Host "[2/3] Waiting for process to exit..." -ForegroundColor Yellow
$maxWait = 10
$waited = 0
while ($waited -lt $maxWait) {
    $checkCmd = "ssh -o StrictHostKeyChecking=no $targetHost 'ps aux | grep cpa-manage-api | grep -v grep || echo not_running'"
    $processInfo = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $checkCmd
    if ($processInfo -match "not_running") {
        Write-Host "Process exited completely" -ForegroundColor Green
        break
    }
    Start-Sleep -Seconds 1
    $waited++
    Write-Host "Waiting... ($waited/$maxWait)" -ForegroundColor Gray
}
Write-Host ""

# Step 3: Start service
Write-Host "[3/3] Starting service..." -ForegroundColor Yellow
$startCmd = "ssh -o StrictHostKeyChecking=no $targetHost 'cd $appDir; nohup java -jar cpa-manage-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod > app.log 2>&1 &'"
ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $startCmd
if ($LASTEXITCODE -ne 0) {
    Write-Host "Start service failed!" -ForegroundColor Red
    exit 1
}
Write-Host "Service started" -ForegroundColor Green
Write-Host ""

# Wait and check
Write-Host "Waiting for service to start (5 seconds)..." -ForegroundColor Gray
Start-Sleep -Seconds 5

Write-Host "Checking service status..." -ForegroundColor Yellow
$checkCmd = "ssh -o StrictHostKeyChecking=no $targetHost 'ps aux | grep cpa-manage-api | grep -v grep'"
$processInfo = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $checkCmd
if ($processInfo) {
    Write-Host "Service is running" -ForegroundColor Green
    Write-Host "Process info: $processInfo" -ForegroundColor Gray
} else {
    Write-Host "Process not found, please check logs" -ForegroundColor Yellow
    Write-Host "Log file: $appDir/app.log" -ForegroundColor Gray
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   Restart Complete!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Server: $targetHost" -ForegroundColor White
Write-Host "App Dir: $appDir" -ForegroundColor White
Write-Host "Log File: $appDir/app.log" -ForegroundColor White
Write-Host ""
