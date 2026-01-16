# Kill remaining stuck processes
$ErrorActionPreference = "Continue"
$privateKey = "$env:USERPROFILE\.ssh\id_rsa"
$jumpHost = "ubuntu@106.75.152.136"
$scriptServer = "root@10.13.55.85"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Killing remaining stuck processes" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Kill bash parent process and the long-running python process
$pidsToKill = @("12072", "5939")

foreach ($processId in $pidsToKill) {
    Write-Host ""
    Write-Host "Killing process PID: $processId" -ForegroundColor Yellow
    
    $killCmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'kill -9 $processId 2>&1 && echo Killed PID $processId || echo Failed to kill PID $processId'"
    $killResult = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $killCmd
    Write-Host $killResult
}

Write-Host ""
Write-Host "Done!" -ForegroundColor Green

