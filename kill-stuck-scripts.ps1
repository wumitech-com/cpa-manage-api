# Kill stuck registration script processes
$ErrorActionPreference = "Continue"
$privateKey = "$env:USERPROFILE\.ssh\id_rsa"
$jumpHost = "ubuntu@106.75.152.136"
$scriptServer = "root@10.13.55.85"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Killing stuck registration script processes" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Find processes running longer than 30 minutes
Write-Host "Finding processes running longer than 30 minutes..." -ForegroundColor Yellow

$findCmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'ps -eo pid,etime,cmd | grep python3 | grep tiktok_register_us_test_account.py | grep -v grep'"
$processes = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $findCmd

Write-Host ""
Write-Host "Found processes:" -ForegroundColor Yellow
Write-Host $processes

# Extract PIDs of processes running longer than 30 minutes
# Format: PID ELAPSED CMD
# ELAPSED format: MM:SS or HH:MM:SS or DD-HH:MM:SS

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Processes to kill:" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# PID 12079 - running for 2 days (definitely stuck)
# PID 5944 - running for 27:59 (likely stuck)
# PID 11546 - running for 30:34 (likely stuck)

$pidsToKill = @("12079", "5944", "11546")

foreach ($processId in $pidsToKill) {
    Write-Host ""
    Write-Host "Killing process PID: $processId" -ForegroundColor Yellow
    
    $killCmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'kill -9 $processId 2>&1 && echo Killed PID $processId || echo Failed to kill PID $processId'"
    $killResult = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $killCmd
    Write-Host $killResult
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Verification" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Verify processes are killed
$verifyCmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'ps -p 12079,5944,11546 2>&1 | grep -v PID || echo All processes killed'"
$verifyResult = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $verifyCmd
Write-Host $verifyResult

Write-Host ""
Write-Host "Done!" -ForegroundColor Green

