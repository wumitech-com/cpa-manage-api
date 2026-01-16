# SSH连接诊断工具
Write-Host "=== SSH连接诊断 ===" -ForegroundColor Green
Write-Host ""

# 1. 检查SSH隧道状态
Write-Host "1. 检查SSH隧道状态..." -ForegroundColor Cyan
$tunnelStatus = netstat -ano | findstr :2222
if ($tunnelStatus) {
    Write-Host "✅ SSH隧道端口2222正在监听" -ForegroundColor Green
    Write-Host $tunnelStatus -ForegroundColor Gray
} else {
    Write-Host "❌ SSH隧道端口2222未监听" -ForegroundColor Red
    Write-Host "请确保SSH隧道正在运行: ssh -N -L 2222:10.7.107.224:22 ubuntu@106.75.152.136" -ForegroundColor Yellow
}

Write-Host ""

# 2. 检查应用配置
Write-Host "2. 检查应用SSH配置..." -ForegroundColor Cyan
$configFile = "src\main\resources\application-ssh.yml"
if (Test-Path $configFile) {
    Write-Host "✅ SSH配置文件存在" -ForegroundColor Green
    $config = Get-Content $configFile -Raw
    if ($config -match "ssh-direct-host: localhost" -and $config -match "ssh-direct-port: 2222") {
        Write-Host "✅ SSH隧道配置正确" -ForegroundColor Green
    } else {
        Write-Host "❌ SSH隧道配置可能有问题" -ForegroundColor Red
    }
} else {
    Write-Host "❌ SSH配置文件不存在" -ForegroundColor Red
}

Write-Host ""

# 3. 测试本地端口连接
Write-Host "3. 测试本地端口连接..." -ForegroundColor Cyan
try {
    $tcpClient = New-Object System.Net.Sockets.TcpClient
    $tcpClient.Connect("localhost", 2222)
    if ($tcpClient.Connected) {
        Write-Host "✅ 本地端口2222连接成功" -ForegroundColor Green
        $tcpClient.Close()
    } else {
        Write-Host "❌ 本地端口2222连接失败" -ForegroundColor Red
    }
} catch {
    Write-Host "❌ 本地端口2222连接异常: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""

# 4. 检查应用状态
Write-Host "4. 检查应用状态..." -ForegroundColor Cyan
$appPort = netstat -ano | findstr :8080
if ($appPort -match "LISTENING") {
    Write-Host "✅ 应用端口8080正在监听" -ForegroundColor Green
} else {
    Write-Host "❌ 应用端口8080未监听" -ForegroundColor Red
    Write-Host "请启动应用: mvn spring-boot:run -Dspring-boot.run.profiles=dev" -ForegroundColor Yellow
}

Write-Host ""

# 5. 提供解决方案
Write-Host "=== 解决方案 ===" -ForegroundColor Green
Write-Host ""
Write-Host "如果SSH连接有问题，请尝试以下步骤:" -ForegroundColor Yellow
Write-Host ""
Write-Host "1. 重新建立SSH隧道:" -ForegroundColor White
Write-Host "   ssh -N -L 2222:10.7.107.224:22 ubuntu@106.75.152.136" -ForegroundColor Gray
Write-Host ""
Write-Host "2. 测试SSH连接:" -ForegroundColor White
Write-Host "   ssh -p 2222 ubuntu@localhost" -ForegroundColor Gray
Write-Host ""
Write-Host "3. 启动应用:" -ForegroundColor White
Write-Host "   mvn spring-boot:run -Dspring-boot.run.profiles=dev" -ForegroundColor Gray
Write-Host ""
Write-Host "4. 测试API:" -ForegroundColor White
Write-Host "   curl 'http://localhost:8080/api/scripts/test-parse-log?host=localhost&logFile=/tmp/batch_create_log_tt_107_224_20251011&lines=0'" -ForegroundColor Gray

Write-Host ""
Write-Host "按任意键退出..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
