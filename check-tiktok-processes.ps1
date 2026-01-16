# Check TikTok processes
$ErrorActionPreference = "Continue"
$privateKey = "$env:USERPROFILE\.ssh\id_rsa"
$jumpHost = "ubuntu@106.75.152.136"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "检查脚本服务器 (10.13.55.85)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

$scriptServer = "root@10.13.55.85"
$cmd = "ssh -o StrictHostKeyChecking=no $scriptServer 'ps aux | grep python3 | grep tiktok_register_us_test_account.py | grep -v grep || echo no_registration_scripts'"
$result = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $cmd
if ($result -match "no_registration_scripts") {
    Write-Host "✓ 没有注册脚本进程" -ForegroundColor Green
} else {
    Write-Host "✗ 发现注册脚本进程:" -ForegroundColor Red
    Write-Host $result
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "检查云手机服务器上的TikTok应用进程" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

$servers = @('10.7.136.129', '10.7.184.117', '10.7.59.169', '10.7.30.99', '10.7.81.210')

foreach ($server in $servers) {
    Write-Host ""
    Write-Host "服务器: $server" -ForegroundColor Yellow
    
    # Check TikTok app processes (aoapp.mus+)
    $cmd = "ssh -o StrictHostKeyChecking=no ubuntu@$server 'ps aux | grep aoapp.mus | grep -v grep | head -3 || echo no_tiktok_app'"
    $result = ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $cmd
    
    if ($result -match "no_tiktok_app") {
        Write-Host "  没有TikTok应用进程" -ForegroundColor Gray
    } else {
        Write-Host "  TikTok应用进程 (正常运行的云手机应用):" -ForegroundColor Cyan
        $lines = $result -split "`n"
        foreach ($line in $lines) {
            if ($line -match "PID") {
                Write-Host "  $line" -ForegroundColor Gray
            } elseif ($line.Trim() -ne "") {
                # Extract PID, CPU%, and command
                if ($line -match "(\d+)\s+\S+\s+(\S+)\s+(\S+).*aoapp\.mus") {
                    $processId = $matches[1]
                    $cpu = $matches[2]
                    Write-Host "    PID: $processId, CPU: $cpu%" -ForegroundColor White
                }
            }
        }
    }
}

Write-Host ""

