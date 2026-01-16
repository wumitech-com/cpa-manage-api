# CPA Manage API Deployment Script
$ErrorActionPreference = "Continue"
$privateKey = "$env:USERPROFILE\.ssh\id_rsa"
$jumpHost = "ubuntu@106.75.152.136"
$targetHost = "ubuntu@10.13.135.74"
$appDir = "/home/ubuntu/cpa-manage-api"
$jarFile = "target\cpa-manage-api-0.0.1-SNAPSHOT.jar"
$remoteJarFile = "/tmp/cpa-manage-api-0.0.1-SNAPSHOT.jar"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   CPA Manage API Deployment" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Step 1: Build
Write-Host "[1/6] Building project..." -ForegroundColor Yellow
mvn clean package -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}
Write-Host "Build success!" -ForegroundColor Green
Write-Host ""

# Check jar file
if (-not (Test-Path $jarFile)) {
    Write-Host "JAR file not found: $jarFile" -ForegroundColor Red
    exit 1
}
$jarInfo = Get-Item $jarFile
Write-Host "JAR size: $([math]::Round($jarInfo.Length / 1MB, 2)) MB" -ForegroundColor Gray
Write-Host ""

# Step 2: Upload to jump host
Write-Host "[2/6] Uploading to jump host..." -ForegroundColor Yellow
scp -i $privateKey -o StrictHostKeyChecking=no $jarFile "${jumpHost}:${remoteJarFile}"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Upload to jump host failed!" -ForegroundColor Red
    exit 1
}
Write-Host "Upload to jump host success!" -ForegroundColor Green
Write-Host ""

# Step 3: Upload to target server
Write-Host "[3/6] Uploading to target server..." -ForegroundColor Yellow
$scpCmd = "scp -o StrictHostKeyChecking=no $remoteJarFile ${targetHost}:/tmp/"
ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $scpCmd
if ($LASTEXITCODE -ne 0) {
    Write-Host "Upload to target server failed!" -ForegroundColor Red
    exit 1
}
Write-Host "Upload to target server success!" -ForegroundColor Green
Write-Host ""

# Step 4: Stop old process
Write-Host "[4/6] Stopping old process..." -ForegroundColor Yellow
$stopCmd = "ssh -o StrictHostKeyChecking=no $targetHost 'sudo pkill -f cpa-manage-api || true'"
ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $stopCmd
Start-Sleep -Seconds 3

# Wait for process to exit completely
Write-Host "Waiting for process to exit..." -ForegroundColor Gray
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
Write-Host "Old process stopped" -ForegroundColor Green
Write-Host ""

# Step 5: Copy file to app directory
Write-Host "[5/6] Copying file to app directory..." -ForegroundColor Yellow
$copyCmd = "ssh -o StrictHostKeyChecking=no $targetHost 'cp /tmp/cpa-manage-api-0.0.1-SNAPSHOT.jar $appDir/cpa-manage-api-0.0.1-SNAPSHOT.jar'"
ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $copyCmd
if ($LASTEXITCODE -ne 0) {
    Write-Host "Copy failed!" -ForegroundColor Red
    exit 1
}
Write-Host "Copy success!" -ForegroundColor Green
Write-Host ""

# Step 6: Start application
Write-Host "[6/6] Starting application..." -ForegroundColor Yellow
$startCmd = "ssh -o StrictHostKeyChecking=no $targetHost 'cd $appDir; nohup java -jar cpa-manage-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod > app.log 2>&1 &'"
ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $startCmd
if ($LASTEXITCODE -ne 0) {
    Write-Host "Start failed!" -ForegroundColor Red
    exit 1
}
Write-Host "Application started" -ForegroundColor Green
Write-Host ""

# Wait and check
Write-Host "Waiting for application to start (5 seconds)..." -ForegroundColor Gray
Start-Sleep -Seconds 5

Write-Host "Checking application status..." -ForegroundColor Yellow
$checkCmd = "ssh -o StrictHostKeyChecking=no $targetHost 'ps aux | grep cpa-manage-api | grep -v grep'"
$processInfo = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $checkCmd
if ($processInfo) {
    Write-Host "Application is running" -ForegroundColor Green
    Write-Host "Process info: $processInfo" -ForegroundColor Gray
} else {
    Write-Host "Process not found, please check logs" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   Deployment Complete!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Server: $targetHost" -ForegroundColor White
Write-Host "App Dir: $appDir" -ForegroundColor White
Write-Host "Log File: $appDir/logs/cpa-manage-api.log" -ForegroundColor White
Write-Host ""

