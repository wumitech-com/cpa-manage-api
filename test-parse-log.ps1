# 测试读取日志并解析云手机名称
# 使用方法: .\test-parse-log.ps1

Write-Host "=== 测试读取日志并解析云手机名称 ===" -ForegroundColor Green

# 参数配置
$apiUrl = "http://localhost:8080/api/scripts/test-parse-log"
$host = "10.7.107.224"
$logFile = "/tmp/batch_create_log_tt_107_224_20251011"
$lines = 0  # 0=读取全部，或指定行数如50

# 构建请求URL
$url = "${apiUrl}?host=${host}&logFile=${logFile}&lines=${lines}"

Write-Host "请求URL: $url" -ForegroundColor Cyan
Write-Host ""

try {
    # 发送请求
    Write-Host "正在读取日志文件..." -ForegroundColor Yellow
    $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 60
    
    # 解析JSON
    $json = $response.Content | ConvertFrom-Json
    
    if ($json.success) {
        Write-Host "✅ 读取成功！" -ForegroundColor Green
        Write-Host ""
        
        # 显示统计信息
        Write-Host "📊 统计信息:" -ForegroundColor Cyan
        Write-Host "  总行数: $($json.statistics.totalLines)"
        Write-Host "  成功行数: $($json.statistics.successLines)"
        Write-Host "  失败行数: $($json.statistics.failedLines)"
        Write-Host "  解析到的云手机数量: $($json.parsedCount)"
        Write-Host ""
        
        # 显示前10个云手机名称
        Write-Host "📱 解析到的云手机名称（前10个）:" -ForegroundColor Cyan
        $json.phoneNames | Select-Object -First 10 | ForEach-Object {
            Write-Host "  - $_" -ForegroundColor White
        }
        
        if ($json.phoneNames.Count -gt 10) {
            Write-Host "  ... 还有 $($json.phoneNames.Count - 10) 个" -ForegroundColor Gray
        }
        
        Write-Host ""
        Write-Host "📝 日志内容（前300字符）:" -ForegroundColor Cyan
        $logPreview = $json.logContent.Substring(0, [Math]::Min(300, $json.logContent.Length))
        Write-Host $logPreview -ForegroundColor Gray
        Write-Host "..." -ForegroundColor Gray
        
        Write-Host ""
        Write-Host "✅ 测试成功完成！" -ForegroundColor Green
        
    } else {
        Write-Host "❌ 测试失败: $($json.message)" -ForegroundColor Red
    }
    
} catch {
    Write-Host "❌ 请求失败: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "请确保应用已启动: mvn spring-boot:run -Dspring-boot.run.profiles=dev" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "按任意键退出..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
