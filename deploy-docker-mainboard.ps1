# CPA Manage API Docker部署脚本 - 主板机服务器
# 跳板机: 106.75.152.136 (ubuntu)
# 目标服务器: 206.119.108.2 (ubuntu)
# 通过跳板机连接目标服务器

$ErrorActionPreference = "Continue"

$JumpHost = "106.75.152.136"
$JumpUser = "ubuntu"
$TargetHost = "206.119.108.2"
$TargetUser = "ubuntu"
$PrivateKeyPath = "$env:USERPROFILE\.ssh\id_rsa"
$AppName = "cpa-manage-api"
$ImageName = "uhub.service.ucloud.cn/wumitech.public/cpa-manage-api:latest"
$ContainerName = "cpa-manage-api"
$Port = 8081
$RemoteDir = "/home/ubuntu/cpa-manage-api"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   CPA Manage API Docker 部署" -ForegroundColor Cyan
Write-Host "   跳板机: $JumpUser@$JumpHost" -ForegroundColor Cyan
Write-Host "   目标服务器: $TargetUser@$TargetHost" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 检查私钥文件
if (-not (Test-Path $PrivateKeyPath)) {
    Write-Host "错误: 私钥文件不存在: $PrivateKeyPath" -ForegroundColor Red
    Write-Host "提示: 请确保SSH私钥文件存在" -ForegroundColor Yellow
    exit 1
}

# 函数：通过跳板机执行远程命令
function Invoke-RemoteCommand {
    param(
        [string]$Command,
        [string]$Description
    )
    
    Write-Host "执行: $Description" -ForegroundColor Yellow
    Write-Host "  命令: $Command" -ForegroundColor Gray
    
    # 通过跳板机执行命令
    $sshCommand = "ssh -i `"$PrivateKeyPath`" -o StrictHostKeyChecking=no ${JumpUser}@${JumpHost} `"ssh -o StrictHostKeyChecking=no ${TargetUser}@${TargetHost} '$Command'`""
    
    try {
        $output = Invoke-Expression $sshCommand 2>&1
        $exitCode = $LASTEXITCODE
        
        if ($exitCode -ne 0) {
            Write-Host "  执行失败 (退出码: $exitCode)" -ForegroundColor Red
            if ($output) {
                Write-Host "  错误输出: $output" -ForegroundColor Red
            }
            return $false
        }
        
        if ($output) {
            Write-Host "  输出: $output" -ForegroundColor Green
        } else {
            Write-Host "  ✓ 执行成功" -ForegroundColor Green
        }
        return $true
    } catch {
        Write-Host "  执行异常: $_" -ForegroundColor Red
        return $false
    }
}

# 函数：通过跳板机上传文件
function Copy-FileViaJump {
    param(
        [string]$LocalPath,
        [string]$RemotePath,
        [string]$Description
    )
    
    Write-Host "上传: $Description" -ForegroundColor Yellow
    
    # 步骤1: 先上传到跳板机临时目录
    $tempPath = "/tmp/$(Split-Path -Leaf $LocalPath)"
    $scpTarget1 = "${JumpUser}@${JumpHost}:$tempPath"
    
    Write-Host "  步骤1: 上传到跳板机..." -ForegroundColor Gray
    try {
        $scpToJumpArgs = @(
            "-i", "`"$PrivateKeyPath`"",
            "-o", "StrictHostKeyChecking=no",
            $LocalPath,
            $scpTarget1
        )
        $process1 = Start-Process -FilePath "scp" -ArgumentList $scpToJumpArgs -NoNewWindow -Wait -PassThru
        
        if ($process1.ExitCode -ne 0) {
            Write-Host "  上传到跳板机失败，退出码: $($process1.ExitCode)" -ForegroundColor Red
            return $false
        }
    } catch {
        Write-Host "  上传到跳板机异常: $_" -ForegroundColor Red
        return $false
    }
    
    # 步骤2: 从跳板机传到目标服务器
    Write-Host "  步骤2: 从跳板机传到目标服务器..." -ForegroundColor Gray
    $scpFromJumpCmd = "ssh -i `"$PrivateKeyPath`" -o StrictHostKeyChecking=no $JumpUser@$JumpHost `"scp -o StrictHostKeyChecking=no $tempPath ${TargetUser}@${TargetHost}:${RemotePath}`""
    try {
        $output = Invoke-Expression $scpFromJumpCmd 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  从跳板机传到目标服务器失败" -ForegroundColor Red
            Write-Host "  错误输出: $output" -ForegroundColor Red
            return $false
        }
    } catch {
        Write-Host "  从跳板机传到目标服务器异常: $_" -ForegroundColor Red
        return $false
    }
    
    # 清理跳板机临时文件
    $cleanupCmd = "ssh -i `"$PrivateKeyPath`" -o StrictHostKeyChecking=no $JumpUser@$JumpHost `"rm -f $tempPath`""
    Invoke-Expression $cleanupCmd 2>&1 | Out-Null
    
    Write-Host "  ✓ 文件上传成功" -ForegroundColor Green
    return $true
}

# 步骤1: 检查Docker镜像是否存在
Write-Host "[1/8] 检查Docker镜像..." -ForegroundColor Yellow
$imageExists = docker images $ImageName --format "{{.Repository}}:{{.Tag}}" 2>&1
if ($LASTEXITCODE -ne 0 -or -not $imageExists) {
    Write-Host "  镜像不存在，需要先构建镜像" -ForegroundColor Yellow
    Write-Host "  是否现在构建并推送镜像? (y/n)" -ForegroundColor Yellow
    $buildImage = Read-Host
    if ($buildImage -eq "y" -or $buildImage -eq "Y") {
        Write-Host "  执行镜像构建脚本..." -ForegroundColor Gray
        .\build-and-push-image.ps1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  镜像构建失败，部署中止!" -ForegroundColor Red
            exit 1
        }
    } else {
        Write-Host "  请先运行 .\build-and-push-image.ps1 构建镜像" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "  ✓ 镜像已存在: $ImageName" -ForegroundColor Green
}
Write-Host ""

# 步骤2: 检查远程服务器Docker是否安装
Write-Host "[2/8] 检查远程服务器Docker..." -ForegroundColor Yellow
$checkDocker = "docker --version"
if (-not (Invoke-RemoteCommand $checkDocker "检查Docker版本")) {
    Write-Host "  错误: 远程服务器未安装Docker或Docker未运行" -ForegroundColor Red
    Write-Host "  提示: 请在远程服务器上安装Docker" -ForegroundColor Yellow
    exit 1
}
Write-Host ""

# 步骤3: 检查远程服务器Docker是否运行
Write-Host "[3/8] 检查远程服务器Docker服务状态..." -ForegroundColor Yellow
$checkDockerService = 'systemctl is-active docker 2>/dev/null || service docker status 2>/dev/null || (docker info > /dev/null 2>&1 && echo running)'
if (-not (Invoke-RemoteCommand $checkDockerService "检查Docker服务")) {
    Write-Host "  警告: Docker服务可能未运行，尝试启动..." -ForegroundColor Yellow
    $startDocker = 'systemctl start docker 2>/dev/null || service docker start 2>/dev/null || echo "docker may already be running"'
    Invoke-RemoteCommand $startDocker "启动Docker服务"
}
Write-Host ""

# 步骤4: 创建远程目录
Write-Host "[4/8] 创建远程目录..." -ForegroundColor Yellow
$createDir = "mkdir -p $RemoteDir/logs"
if (-not (Invoke-RemoteCommand $createDir "创建应用目录")) {
    Write-Host "  警告: 目录创建可能失败，继续执行..." -ForegroundColor Yellow
}
Write-Host ""

# 步骤5: 停止并删除旧容器（如果存在）
Write-Host "[5/8] 停止并删除旧容器..." -ForegroundColor Yellow
$stopContainer = "docker stop $ContainerName 2>&1 | Out-Null; echo 'done'"
Invoke-RemoteCommand $stopContainer "停止旧容器" | Out-Null

$removeContainer = "docker rm $ContainerName 2>&1 | Out-Null; echo 'done'"
Invoke-RemoteCommand $removeContainer "删除旧容器" | Out-Null
Write-Host ""

# 步骤6: 拉取最新镜像（如果需要）
Write-Host "[6/8] 拉取最新Docker镜像..." -ForegroundColor Yellow
Write-Host "  注意: 如果镜像在远程仓库，需要先登录并拉取" -ForegroundColor Gray
Write-Host "  如果镜像已通过其他方式传输，可以跳过此步骤" -ForegroundColor Gray
Write-Host "  是否拉取镜像? (y/n，默认n)" -ForegroundColor Yellow
$pullImage = Read-Host
if ($pullImage -eq "y" -or $pullImage -eq "Y") {
    $pullCmd = "docker pull $ImageName"
    if (-not (Invoke-RemoteCommand $pullCmd "拉取Docker镜像")) {
        Write-Host "  警告: 镜像拉取失败，将使用本地镜像（如果存在）" -ForegroundColor Yellow
    }
} else {
    Write-Host "  跳过镜像拉取，使用本地镜像" -ForegroundColor Gray
}
Write-Host ""

# 步骤7: 保存镜像并传输到远程服务器
Write-Host "[7/8] 保存并传输Docker镜像..." -ForegroundColor Yellow
$localImageFile = "cpa-manage-api-image.tar"
Write-Host "  保存本地镜像为tar文件..." -ForegroundColor Gray
docker save -o $localImageFile $ImageName
if ($LASTEXITCODE -ne 0) {
    Write-Host "  错误: 保存镜像失败" -ForegroundColor Red
    exit 1
}

$imageSize = (Get-Item $localImageFile).Length / 1MB
Write-Host "  镜像文件大小: $([math]::Round($imageSize, 2)) MB" -ForegroundColor Gray
Write-Host "  上传镜像文件到远程服务器（通过跳板机，这可能需要一些时间）..." -ForegroundColor Gray

# 通过跳板机上传到目标服务器临时目录
$remoteImageFile = "/tmp/$localImageFile"
if (-not (Copy-FileViaJump $localImageFile $remoteImageFile "Docker镜像文件")) {
    Write-Host "  错误: 上传镜像文件失败" -ForegroundColor Red
    Remove-Item $localImageFile -ErrorAction SilentlyContinue
    exit 1
}

# 在远程服务器加载镜像
Write-Host "  在远程服务器加载镜像..." -ForegroundColor Gray
$loadImage = "docker load -i $remoteImageFile && rm -f $remoteImageFile"
if (-not (Invoke-RemoteCommand $loadImage "加载Docker镜像")) {
    Write-Host "  错误: 加载镜像失败" -ForegroundColor Red
    exit 1
}

# 清理本地临时文件
Remove-Item $localImageFile -ErrorAction SilentlyContinue
Write-Host ""

# 步骤8: 启动Docker容器
Write-Host "[8/8] 启动Docker容器..." -ForegroundColor Yellow
Write-Host "  选择部署方式:" -ForegroundColor Gray
Write-Host "    1) 使用 docker run 命令" -ForegroundColor Gray
Write-Host "    2) 使用 docker-compose (需要先上传 docker-compose.yml)" -ForegroundColor Gray
$deployMethod = Read-Host "  请选择 (1/2，默认1)"

if ($deployMethod -eq "2") {
    # 使用docker-compose部署
    Write-Host "  使用 docker-compose 部署..." -ForegroundColor Gray
    
    # 上传docker-compose.yml
    Write-Host "  上传 docker-compose.yml..." -ForegroundColor Gray
    $remoteComposeFile = "$RemoteDir/docker-compose.yml"
    if (-not (Copy-FileViaJump "docker-compose.yml" $remoteComposeFile "docker-compose.yml")) {
        Write-Host "  警告: 上传 docker-compose.yml 失败，将使用 docker run 方式" -ForegroundColor Yellow
        $deployMethod = "1"
    }
    
    if ($deployMethod -eq "2") {
        # 使用docker-compose启动
        $composeCmd = "cd $RemoteDir && docker-compose up -d"
        if (-not (Invoke-RemoteCommand $composeCmd "使用docker-compose启动容器")) {
            Write-Host "  错误: docker-compose 启动失败" -ForegroundColor Red
            exit 1
        }
    }
}

if ($deployMethod -eq "1" -or $deployMethod -eq "") {
    # 使用docker run启动
    $dockerRunCmd = @"
docker run -d \
  --name $ContainerName \
  --restart unless-stopped \
  -p $Port`:8081 \
  -v $RemoteDir/logs:/app/logs \
  -e TZ=Asia/Shanghai \
  -e JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Duser.timezone=Asia/Shanghai" \
  -e SPRING_PROFILES_ACTIVE=prod \
  $ImageName
"@

    # 将多行命令转换为单行
    $dockerRunCmd = $dockerRunCmd -replace "`r`n", " " -replace "`n", " " -replace "  +", " "

    if (-not (Invoke-RemoteCommand $dockerRunCmd "启动Docker容器")) {
        Write-Host "  错误: 启动容器失败" -ForegroundColor Red
        exit 1
    }
}
Write-Host ""

# 等待容器启动
Write-Host "等待容器启动..." -ForegroundColor Yellow
Start-Sleep -Seconds 5

# 检查容器状态
Write-Host "检查容器状态..." -ForegroundColor Yellow
$checkStatus = "docker ps -a --filter name=$ContainerName --format '{{.Names}}\t{{.Status}}\t{{.Ports}}'"
Invoke-RemoteCommand $checkStatus "检查容器状态"
Write-Host ""

# 查看容器日志（最后20行）
Write-Host "查看容器日志（最后20行）..." -ForegroundColor Yellow
$viewLogs = "docker logs --tail 20 $ContainerName"
Invoke-RemoteCommand $viewLogs "查看容器日志"
Write-Host ""

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   部署完成!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "部署信息:" -ForegroundColor White
Write-Host "  服务器: $TargetUser@$TargetHost" -ForegroundColor Gray
Write-Host "  容器名: $ContainerName" -ForegroundColor Gray
Write-Host "  端口: $Port" -ForegroundColor Gray
Write-Host "  应用目录: $RemoteDir" -ForegroundColor Gray
Write-Host ""
Write-Host "常用命令:" -ForegroundColor White
Write-Host "  查看容器状态:" -ForegroundColor Yellow
Write-Host "    ssh -i `"$PrivateKeyPath`" $JumpUser@$JumpHost `"ssh $TargetUser@$TargetHost 'docker ps -a | grep $ContainerName'`"" -ForegroundColor Gray
Write-Host ""
Write-Host "  查看容器日志:" -ForegroundColor Yellow
Write-Host "    ssh -i `"$PrivateKeyPath`" $JumpUser@$JumpHost `"ssh $TargetUser@$TargetHost 'docker logs -f $ContainerName'`"" -ForegroundColor Gray
Write-Host ""
Write-Host "  停止容器:" -ForegroundColor Yellow
Write-Host "    ssh -i `"$PrivateKeyPath`" $JumpUser@$JumpHost `"ssh $TargetUser@$TargetHost 'docker stop $ContainerName'`"" -ForegroundColor Gray
Write-Host ""
Write-Host "  重启容器:" -ForegroundColor Yellow
Write-Host "    ssh -i `"$PrivateKeyPath`" $JumpUser@$JumpHost `"ssh $TargetUser@$TargetHost 'docker restart $ContainerName'`"" -ForegroundColor Gray
Write-Host ""
Write-Host "  使用docker-compose管理 (如果使用compose部署):" -ForegroundColor Yellow
Write-Host "    ssh -i `"$PrivateKeyPath`" $JumpUser@$JumpHost `"ssh $TargetUser@$TargetHost 'cd $RemoteDir && docker-compose logs -f'`"" -ForegroundColor Gray
Write-Host "    ssh -i `"$PrivateKeyPath`" $JumpUser@$JumpHost `"ssh $TargetUser@$TargetHost 'cd $RemoteDir && docker-compose restart'`"" -ForegroundColor Gray
Write-Host ""
Write-Host "  测试接口:" -ForegroundColor Yellow
$testUrl = "http://$TargetHost`:$Port/actuator/health"
Write-Host ('    curl ' + $testUrl) -ForegroundColor Gray
Write-Host ""

