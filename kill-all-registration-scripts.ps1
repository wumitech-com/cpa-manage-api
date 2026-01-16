# Kill all registration script processes
$ErrorActionPreference = "Continue"
$privateKey = "$env:USERPROFILE\.ssh\id_rsa"
$jumpHost = "ubuntu@106.75.152.136"
$scriptServer = "root@10.13.55.85"
$scriptPath = "tiktok_register_us_test_account.py"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Killing all registration script processes" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# First, check how many processes exist
$checkCmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'ps aux | grep python3 | grep $scriptPath | grep -v grep | wc -l'"
$countResult = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $checkCmd
$count = [int]$countResult.Trim()

if ($count -eq 0) {
    Write-Host "No registration script processes found" -ForegroundColor Green
    exit 0
}

Write-Host "Found $count processes to kill" -ForegroundColor Yellow

# Kill all processes directly
Write-Host ""
Write-Host "Killing processes..." -ForegroundColor Yellow
$killCmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'pkill -9 -f $scriptPath || true'"
$killResult = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $killCmd

Start-Sleep -Seconds 2

# Verify processes are killed
Write-Host ""
Write-Host "Verifying processes are killed..." -ForegroundColor Yellow
$verifyCmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'ps aux | grep python3 | grep $scriptPath | grep -v grep | wc -l'"
$verifyResult = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $verifyCmd
$remainingCount = [int]$verifyResult.Trim()

if ($remainingCount -eq 0) {
    Write-Host "All processes killed successfully!" -ForegroundColor Green
} else {
    Write-Host "Warning: $remainingCount processes still running" -ForegroundColor Yellow
    
    # Try to kill remaining processes again
    Write-Host "Attempting to kill remaining processes..." -ForegroundColor Yellow
    $retryCmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'pkill -9 -f $scriptPath || true; sleep 1; ps aux | grep python3 | grep $scriptPath | grep -v grep | wc -l'"
    $retryResult = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $retryCmd
    $finalCount = [int]$retryResult.Trim()
    
    if ($finalCount -eq 0) {
        Write-Host "All processes killed successfully!" -ForegroundColor Green
    } else {
        Write-Host "Error: $finalCount processes still running" -ForegroundColor Red
    }
}

Write-Host ""

