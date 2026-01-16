# 部署脚本 - 通过跳板机部署到目标服务器
# 跳板机: 106.75.152.136
# 目标服务器: 10.13.135.74

$JumpHost = "106.75.152.136"
$JumpUser = "ubuntu"
$TargetHost = "10.13.135.74"
$TargetUser = "ubuntu"
$PrivateKeyPath = "$env:USERPROFILE\.ssh\id_rsa"
$JarFile = "target\cpa-manage-api-0.0.1-SNAPSHOT.jar"
$RemoteDir = "/home/ubuntu/cpa-manage-api"
$AppName = "cpa-manage-api"

Write-Host "==================== 开始部署应用 ====================" -ForegroundColor Green
Write-Host "跳板机: $JumpUser@$JumpHost" -ForegroundColor Cyan
Write-Host "目标服务器: $TargetUser@$TargetHost" -ForegroundColor Cyan
Write-Host "JAR文件: $JarFile" -ForegroundColor Cyan
Write-Host "私钥路径: $PrivateKeyPath" -ForegroundColor Cyan
Write-Host ""

# 检查私钥文件是否存在
if (-not (Test-Path $PrivateKeyPath)) {
    Write-Host "错误: 私钥文件不存在: $PrivateKeyPath" -ForegroundColor Red
    exit 1
}

# 检查JAR文件是否存在
if (-not (Test-Path $JarFile)) {
    Write-Host "错误: JAR文件不存在: $JarFile" -ForegroundColor Red
    Write-Host "请先运行: mvn clean package -DskipTests" -ForegroundColor Yellow
    exit 1
}

Write-Host "1. 检查JAR文件..." -ForegroundColor Cyan
$jarInfo = Get-Item $JarFile
Write-Host "   JAR文件大小: $([math]::Round($jarInfo.Length / 1MB, 2)) MB" -ForegroundColor Green
Write-Host ""

# 2. 通过跳板机创建远程目录
Write-Host "2. 在目标服务器创建目录..." -ForegroundColor Cyan
$createDirCmd = "ssh -i `"$PrivateKeyPath`" -o StrictHostKeyChecking=no $JumpUser@$JumpHost `"ssh -o StrictHostKeyChecking=no $TargetUser@$TargetHost 'mkdir -p $RemoteDir'`""
Write-Host "   执行命令: ssh ... mkdir -p $RemoteDir" -ForegroundColor Gray

try {
    $output = Invoke-Expression $createDirCmd 2>&1
    Write-Host "   ✓ 目录创建成功" -ForegroundColor Green
} catch {
    Write-Host "   ✗ 目录创建失败: $_" -ForegroundColor Red
    exit 1
}
Write-Host ""

# 3. 先上传到跳板机，再从跳板机传到目标服务器
Write-Host "3. 上传JAR文件到目标服务器（分两步：先到跳板机，再到目标服务器）..." -ForegroundColor Cyan
Write-Host "   这可能需要一些时间，请耐心等待..." -ForegroundColor Yellow

# 步骤3.1: 上传到跳板机临时目录
$tempDir = "/tmp"
$tempJarFile = "$tempDir/cpa-manage-api-0.0.1-SNAPSHOT.jar"

Write-Host "   3.1 上传到跳板机临时目录..." -ForegroundColor Gray
try {
    $scpToJumpArgs = @(
        "-i", "`"$PrivateKeyPath`"",
        "-o", "StrictHostKeyChecking=no",
        $JarFile,
        "${JumpUser}@${JumpHost}:${tempJarFile}"
    )
    $process1 = Start-Process -FilePath "scp" -ArgumentList $scpToJumpArgs -NoNewWindow -Wait -PassThru
    
    if ($process1.ExitCode -eq 0) {
        Write-Host "   ✓ 文件已上传到跳板机" -ForegroundColor Green
    } else {
        Write-Host "   ✗ 上传到跳板机失败，退出码: $($process1.ExitCode)" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "   ✗ 上传到跳板机异常: $_" -ForegroundColor Red
    exit 1
}

# 步骤3.2: 从跳板机传到目标服务器
Write-Host "   3.2 从跳板机传到目标服务器..." -ForegroundColor Gray
$scpFromJumpCmd = "ssh -i `"$PrivateKeyPath`" -o StrictHostKeyChecking=no $JumpUser@$JumpHost `"scp -o StrictHostKeyChecking=no $tempJarFile ${TargetUser}@${TargetHost}:${RemoteDir}/`""
try {
    $output = Invoke-Expression $scpFromJumpCmd 2>&1
    Write-Host "   ✓ 文件已上传到目标服务器" -ForegroundColor Green
} catch {
    Write-Host "   ✗ 从跳板机传到目标服务器失败: $_" -ForegroundColor Red
    Write-Host "   错误输出: $output" -ForegroundColor Yellow
    exit 1
}
Write-Host ""

# 4. 停止旧的应用（如果存在）
Write-Host "4. 检查并停止旧的应用..." -ForegroundColor Cyan
$stopAppCmd = "ssh -i `"$PrivateKeyPath`" -o StrictHostKeyChecking=no $JumpUser@$JumpHost `"ssh -o StrictHostKeyChecking=no $TargetUser@$TargetHost 'pkill -f $AppName || true'`""
Write-Host "   执行命令: pkill -f $AppName" -ForegroundColor Gray

try {
    $output = Invoke-Expression $stopAppCmd 2>&1
    Write-Host "   ✓ 旧应用已停止（如果存在）" -ForegroundColor Green
    Start-Sleep -Seconds 2
} catch {
    Write-Host "   ⚠ 停止旧应用时出现异常（可能应用未运行）: $_" -ForegroundColor Yellow
}
Write-Host ""

# 5. 启动新应用
Write-Host "5. 启动新应用..." -ForegroundColor Cyan
$startAppCmd = "ssh -i `"$PrivateKeyPath`" -o StrictHostKeyChecking=no $JumpUser@$JumpHost `"ssh -o StrictHostKeyChecking=no $TargetUser@$TargetHost 'cd $RemoteDir && nohup java -jar -Dspring.profiles.active=dev $AppName-0.0.1-SNAPSHOT.jar > app.log 2>&1 &'`""
Write-Host "   执行命令: nohup java -jar ... &" -ForegroundColor Gray

try {
    $output = Invoke-Expression $startAppCmd 2>&1
    Write-Host "   ✓ 应用启动命令已执行" -ForegroundColor Green
    if ($output) {
        Write-Host "   输出: $output" -ForegroundColor Gray
    }
} catch {
    Write-Host "   ✗ 应用启动失败: $_" -ForegroundColor Red
    exit 1
}
Write-Host ""

# 6. 等待几秒后检查应用状态
Write-Host "6. 等待应用启动..." -ForegroundColor Cyan
Start-Sleep -Seconds 5

$checkAppCmd = "ssh -i `"$PrivateKeyPath`" -o StrictHostKeyChecking=no $JumpUser@$JumpHost `"ssh -o StrictHostKeyChecking=no $TargetUser@$TargetHost 'ps aux | grep $AppName | grep -v grep'`""
Write-Host "   检查应用进程..." -ForegroundColor Gray

try {
    $processInfo = Invoke-Expression $checkAppCmd 2>&1
    if ($processInfo -and $processInfo -notmatch "error|Error|ERROR") {
        Write-Host "   ✓ 应用正在运行" -ForegroundColor Green
        Write-Host "   进程信息: $processInfo" -ForegroundColor Gray
    } else {
        Write-Host "   ⚠ 未找到应用进程，请检查日志" -ForegroundColor Yellow
        Write-Host "   尝试查看日志..." -ForegroundColor Yellow
        $logCmd = "ssh -i `"$PrivateKeyPath`" -o StrictHostKeyChecking=no $JumpUser@$JumpHost `"ssh -o StrictHostKeyChecking=no $TargetUser@$TargetHost 'tail -20 $RemoteDir/app.log'`""
        $logOutput = Invoke-Expression $logCmd 2>&1
        Write-Host "   日志: $logOutput" -ForegroundColor Gray
    }
} catch {
    Write-Host "   ⚠ 检查应用状态时出现异常: $_" -ForegroundColor Yellow
}
Write-Host ""

Write-Host "==================== 部署完成 ====================" -ForegroundColor Green
Write-Host ""
Write-Host "应用已部署到: $TargetUser@$TargetHost" -ForegroundColor Cyan
Write-Host "应用目录: $RemoteDir" -ForegroundColor Cyan
Write-Host ""
Write-Host "查看日志命令:" -ForegroundColor Yellow
Write-Host "ssh -i `"$PrivateKeyPath`" -o StrictHostKeyChecking=no $JumpUser@$JumpHost `"ssh -o StrictHostKeyChecking=no $TargetUser@$TargetHost 'tail -f ${RemoteDir}/app.log'`"" -ForegroundColor Gray
Write-Host ""
Write-Host "测试接口命令:" -ForegroundColor Yellow
Write-Host "curl http://${TargetHost}:8081/api/tt-register/repeat" -ForegroundColor Gray
Write-Host ""
