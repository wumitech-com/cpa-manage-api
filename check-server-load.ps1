# 检查服务器负载
$ErrorActionPreference = "Continue"
$privateKey = "$env:USERPROFILE\.ssh\id_rsa"
$jumpHost = "ubuntu@106.75.152.136"
$servers = @('10.7.136.129', '10.7.184.117', '10.7.59.169', '10.7.30.99', '10.7.81.210')

foreach ($server in $servers) {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "检查服务器: $server" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    
    $cmd = "ssh -o StrictHostKeyChecking=no ubuntu@$server 'uptime && echo --- && free -h && echo --- && df -h / && echo --- && top -bn1 | head -20'"
    
    try {
        ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost $cmd
    } catch {
        Write-Host "检查服务器 $server 失败: $_" -ForegroundColor Red
    }
    
    Write-Host ""
}

