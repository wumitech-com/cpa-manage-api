# 简单的 K8s 部署脚本（通过跳板机）
$ErrorActionPreference = "Continue"

$firstJumpHost = "106.75.152.136"
$firstJumpUser = "ubuntu"
$secondJumpHost = "103.149.27.5"
$secondJumpUser = "ubuntu"
$sshKeyPath = "$env:USERPROFILE\.ssh\id_rsa"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   CPA Manage API K8s 部署" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 检查文件是否存在
if (-not (Test-Path "k8s/deployment.yaml")) {
    Write-Host "deployment.yaml 不存在" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path "k8s/service.yaml")) {
    Write-Host "service.yaml 不存在" -ForegroundColor Red
    exit 1
}

# 函数：通过跳板机执行命令
function Invoke-RemoteCommand {
    param(
        [string]$Command,
        [string]$Description
    )
    
    Write-Host "执行: $Description" -ForegroundColor Yellow
    
    # 构建SSH命令：通过两层跳板机执行
    $sshCommand = "ssh -i $sshKeyPath -o StrictHostKeyChecking=no ${firstJumpUser}@${firstJumpHost} `"ssh -o StrictHostKeyChecking=no ${secondJumpUser}@${secondJumpHost} '$Command'`""
    
    $output = Invoke-Expression $sshCommand 2>&1
    $exitCode = $LASTEXITCODE
    
    if ($exitCode -ne 0) {
        Write-Host "执行失败 (退出码: $exitCode)" -ForegroundColor Red
        Write-Host $output -ForegroundColor Red
        return $false
    }
    
    Write-Host $output
    return $true
}

# 函数：通过跳板机上传文件
function Copy-FileViaJump {
    param(
        [string]$LocalPath,
        [string]$RemotePath,
        [string]$Description
    )
    
    Write-Host "上传: $Description" -ForegroundColor Yellow
    
    # 先上传到第一层跳板机
    $tempPath1 = "/tmp/$(Split-Path -Leaf $LocalPath)"
    $scpTarget1 = "${firstJumpUser}@${firstJumpHost}:$tempPath1"
    scp -i $sshKeyPath -o StrictHostKeyChecking=no $LocalPath $scpTarget1 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "上传到第一层跳板机失败" -ForegroundColor Red
        return $false
    }
    
    # 从第一层跳板机上传到第二层跳板机
    $sshCommand = "ssh -i $sshKeyPath -o StrictHostKeyChecking=no $firstJumpUser@$firstJumpHost `"scp -o StrictHostKeyChecking=no $tempPath1 ${secondJumpUser}@${secondJumpHost}:$RemotePath`""
    Invoke-Expression $sshCommand 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "上传到第二层跳板机失败" -ForegroundColor Red
        return $false
    }
    
    Write-Host "上传成功" -ForegroundColor Green
    return $true
}

# 步骤1: 上传 deployment.yaml
Write-Host "[1/4] 上传 deployment.yaml..." -ForegroundColor Yellow
$remoteDeploymentPath = "~/deployment.yaml"
if (-not (Copy-FileViaJump "k8s/deployment.yaml" $remoteDeploymentPath "deployment.yaml")) {
    exit 1
}
Write-Host ""

# 步骤2: 上传 service.yaml
Write-Host "[2/4] 上传 service.yaml..." -ForegroundColor Yellow
$remoteServicePath = "~/service.yaml"
if (-not (Copy-FileViaJump "k8s/service.yaml" $remoteServicePath "service.yaml")) {
    exit 1
}
Write-Host ""

# 步骤3: 部署 deployment
Write-Host "[3/4] 部署 Deployment..." -ForegroundColor Yellow
$deployDeployment = "kubectl apply -f ~/deployment.yaml"
if (-not (Invoke-RemoteCommand $deployDeployment "部署 Deployment")) {
    Write-Host "部署 Deployment 失败" -ForegroundColor Red
    exit 1
}
Write-Host ""

# 步骤4: 部署 service
Write-Host "[4/4] 部署 Service..." -ForegroundColor Yellow
$deployService = "kubectl apply -f ~/service.yaml"
if (-not (Invoke-RemoteCommand $deployService "部署 Service")) {
    Write-Host "部署 Service 失败" -ForegroundColor Red
    exit 1
}
Write-Host ""

# 等待 Pod 启动
Write-Host "等待 Pod 启动..." -ForegroundColor Yellow
Start-Sleep -Seconds 5

# 检查 Pod 状态
Write-Host "检查 Pod 状态..." -ForegroundColor Yellow
$checkPods = "kubectl get pods -n wumitech-cpa -l app=cpa-manage-api"
Invoke-RemoteCommand $checkPods "检查 Pod 状态"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   部署完成!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "查看日志命令（在第二层跳板机上执行）:" -ForegroundColor White
Write-Host "  kubectl logs -f -n wumitech-cpa -l app=cpa-manage-api" -ForegroundColor Gray
Write-Host ""

