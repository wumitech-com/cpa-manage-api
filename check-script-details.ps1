# Check detailed information about registration script processes
$ErrorActionPreference = "Continue"
$privateKey = "$env:USERPROFILE\.ssh\id_rsa"
$jumpHost = "ubuntu@106.75.152.136"
$scriptServer = "root@10.13.55.85"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Registration script processes" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Get all processes with full details
$cmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'ps aux | grep python3 | grep tiktok_register_us_test_account.py | grep -v grep'"

try {
    $result = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $cmd
    Write-Host $result
    
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "Process runtime (ELAPSED TIME)" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    
    # Get runtime using ps -eo
    $runtimeCmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'ps -eo pid,etime,pcpu,pmem,cmd | grep python3 | grep tiktok_register_us_test_account.py | grep -v grep'"
    $runtimeResult = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $runtimeCmd
    Write-Host $runtimeResult
    
    # Count total processes
    $countCmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'ps aux | grep python3 | grep tiktok_register_us_test_account.py | grep -v grep | wc -l'"
    $countResult = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $countCmd
    Write-Host ""
    Write-Host "Total processes found: $countResult" -ForegroundColor Yellow
    
    # Check for processes running longer than 1 hour (look for HH:MM:SS format)
    Write-Host ""
    Write-Host "Checking for processes running longer than 1 hour..." -ForegroundColor Yellow
    $longCmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'ps -eo pid,etime,cmd | grep python3 | grep tiktok_register_us_test_account.py | grep -v grep'"
    $longResult = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $longCmd
    Write-Host $longResult
    
} catch {
    Write-Host "Failed: $_" -ForegroundColor Red
}

Write-Host ""
