# Check stuck registration script processes on script server
$ErrorActionPreference = "Continue"
$privateKey = "$env:USERPROFILE\.ssh\id_rsa"
$jumpHost = "ubuntu@106.75.152.136"
$scriptServer = "root@10.13.55.85"
$scriptPath = "tiktok_register_us_test_account.py"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Checking script server: 10.13.55.85" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Check for registration script processes
$cmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'ps aux | grep python3 | grep $scriptPath | grep -v grep || echo no_process'"

try {
    $result = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $cmd
    if ($result -match "no_process") {
        Write-Host "OK: No stuck registration script processes found" -ForegroundColor Green
    } else {
        Write-Host "WARNING: Found registration script processes:" -ForegroundColor Yellow
        Write-Host $result
        
        # Get detailed process information
        $detailCmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'ps aux | grep python3 | grep $scriptPath | grep -v grep | head -10'"
        $detailResult = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $detailCmd
        Write-Host ""
        Write-Host "Process details (PID, CPU%, MEM%, TIME, CMD):" -ForegroundColor Yellow
        Write-Host $detailResult
        
        # Count processes
        $countCmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'ps aux | grep python3 | grep $scriptPath | grep -v grep | wc -l'"
        $countResult = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $countCmd
        Write-Host ""
        Write-Host "Total processes found: $countResult" -ForegroundColor Yellow
    }
} catch {
    Write-Host "Failed to check script server: $_" -ForegroundColor Red
}

Write-Host ""

