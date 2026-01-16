# 连接诊断脚本

Write-Host "=== K8s 端口转发诊断工具 ===" -ForegroundColor Cyan
Write-Host ""

# 1. 检查本地端口监听
Write-Host "1. 检查本地端口 5556 监听状态..." -ForegroundColor Yellow
$port5556 = netstat -an | Select-String "5556" | Select-String "LISTENING"
if ($port5556) {
    Write-Host "   ✓ 本地端口 5556 正在监听" -ForegroundColor Green
    $port5556 | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }
} else {
    Write-Host "   ✗ 本地端口 5556 未监听" -ForegroundColor Red
}
Write-Host ""

# 2. 检查 SSH 进程
Write-Host "2. 检查 SSH 进程..." -ForegroundColor Yellow
$sshProcesses = Get-Process | Where-Object {$_.ProcessName -eq "ssh"}
if ($sshProcesses) {
    Write-Host "   ✓ 发现 $($sshProcesses.Count) 个 SSH 进程" -ForegroundColor Green
    $sshProcesses | ForEach-Object { 
        Write-Host "   - PID: $($_.Id), 启动时间: $($_.StartTime)" -ForegroundColor Gray 
    }
} else {
    Write-Host "   ✗ 未发现 SSH 进程" -ForegroundColor Red
}
Write-Host ""

# 3. 测试本地连接
Write-Host "3. 测试本地连接..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri http://localhost:5556 -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
    Write-Host "   ✓ 连接成功！状态码: $($response.StatusCode)" -ForegroundColor Green
} catch {
    Write-Host "   ✗ 连接失败: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.Exception.Message -like "*远程主机强迫关闭*") {
        Write-Host "   → 可能原因：第一层跳板机上的隧道未建立或已断开" -ForegroundColor Yellow
    } elseif ($_.Exception.Message -like "*连接被拒绝*") {
        Write-Host "   → 可能原因：本地端口未正确监听" -ForegroundColor Yellow
    } elseif ($_.Exception.Message -like "*超时*") {
        Write-Host "   → 可能原因：网络连接问题或服务未响应" -ForegroundColor Yellow
    }
}
Write-Host ""

# 4. 检查建议
Write-Host "4. 排查建议：" -ForegroundColor Yellow
Write-Host "   请在第一层跳板机(106.75.152.136)上检查：" -ForegroundColor White
Write-Host "   1. 是否运行了: ssh -N -L 5556:127.0.0.1:5556 ubuntu@103.149.27.5" -ForegroundColor Cyan
Write-Host "   2. 检查端口监听: netstat -tuln | grep 5556" -ForegroundColor Cyan
Write-Host "   3. 测试连接: curl http://127.0.0.1:5556" -ForegroundColor Cyan
Write-Host ""
Write-Host "   在第二层跳板机(103.149.27.5)上检查：" -ForegroundColor White
Write-Host "   1. kubectl port-forward 是否还在运行" -ForegroundColor Cyan
Write-Host "   2. 检查端口监听: netstat -tuln | grep 5556" -ForegroundColor Cyan
Write-Host ""

