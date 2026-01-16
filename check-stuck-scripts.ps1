# Check stuck registration script processes
$ErrorActionPreference = "Continue"
$privateKey = "$env:USERPROFILE\.ssh\id_rsa"
$jumpHost = "ubuntu@106.75.152.136"
$servers = @('10.7.136.129', '10.7.184.117', '10.7.59.169', '10.7.30.99', '10.7.81.210')
$scriptPath = "tiktok_register_us_test_account.py"

foreach ($server in $servers) {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "Checking server: $server" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    
    # Check for registration script processes
    $cmd = "ssh -o StrictHostKeyChecking=no ubuntu@$server 'ps aux | grep python3 | grep $scriptPath | grep -v grep || echo no_process'"
    
    try {
        $result = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $cmd
        if ($result -match "no_process") {
            Write-Host "OK: No stuck registration script processes found" -ForegroundColor Green
        } else {
            Write-Host "WARNING: Found registration script processes:" -ForegroundColor Yellow
            Write-Host $result
            
            # Get process details (runtime, CPU, memory)
            $detailCmd = "ssh -o StrictHostKeyChecking=no ubuntu@$server 'ps aux | grep python3 | grep $scriptPath | grep -v grep | head -5'"
            $detailResult = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $detailCmd
            Write-Host "Process details:" -ForegroundColor Yellow
            Write-Host $detailResult
        }
    } catch {
        Write-Host "Failed to check server $server : $_" -ForegroundColor Red
    }
    
    Write-Host ""
}
