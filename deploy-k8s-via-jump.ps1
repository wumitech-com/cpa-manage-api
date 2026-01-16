# CPA Manage API K8s部署脚本（通过跳板机）
$ErrorActionPreference = "Continue"

# 跳板机配置
$firstJumpHost = "106.75.152.136"
$firstJumpUser = "ubuntu"
$secondJumpHost = ""  # 请填写香港跳板机IP或主机名
$secondJumpUser = "ubuntu"
$sshKeyPath = "$env:USERPROFILE\.ssh\id_rsa"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   CPA Manage API K8s部署（跳板机）" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 检查香港跳板机配置
if ([string]::IsNullOrEmpty($secondJumpHost)) {
    Write-Host "请先配置香港跳板机信息！" -ForegroundColor Red
    Write-Host "编辑此脚本，设置 `$secondJumpHost 变量" -ForegroundColor Yellow
    exit 1
}

# 检查SSH密钥
if (-not (Test-Path $sshKeyPath)) {
    Write-Host "SSH密钥不存在: $sshKeyPath" -ForegroundColor Red
    exit 1
}

# 检查k8s配置文件
if (-not (Test-Path "k8s/kubeconfig.yaml")) {
    Write-Host "kubeconfig.yaml不存在" -ForegroundColor Red
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
    $sshCommand = "ssh -i $sshKeyPath -o StrictHostKeyChecking=no $firstJumpUser@$firstJumpHost `"ssh -i ~/.ssh/id_rsa -o StrictHostKeyChecking=no $secondJumpUser@$secondJumpHost '$Command'`""
    
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
    scp -i $sshKeyPath -o StrictHostKeyChecking=no $LocalPath "${firstJumpUser}@${firstJumpHost}:$tempPath1"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "上传到第一层跳板机失败" -ForegroundColor Red
        return $false
    }
    
    # 从第一层跳板机上传到第二层跳板机
    $sshCommand = "ssh -i $sshKeyPath -o StrictHostKeyChecking=no $firstJumpUser@$firstJumpHost `"scp -i ~/.ssh/id_rsa -o StrictHostKeyChecking=no $tempPath1 $secondJumpUser@$secondJumpHost:$RemotePath`""
    Invoke-Expression $sshCommand
    if ($LASTEXITCODE -ne 0) {
        Write-Host "上传到第二层跳板机失败" -ForegroundColor Red
        return $false
    }
    
    Write-Host "上传成功" -ForegroundColor Green
    return $true
}

# 步骤1: 上传kubeconfig
Write-Host "[1/7] 上传kubeconfig..." -ForegroundColor Yellow
$kubeconfigRemotePath = "~/kubeconfig.yaml"
if (-not (Copy-FileViaJump "k8s/kubeconfig.yaml" $kubeconfigRemotePath "kubeconfig.yaml")) {
    exit 1
}
Write-Host ""

# 步骤2: 上传所有K8s配置文件
Write-Host "[2/7] 上传K8s配置文件..." -ForegroundColor Yellow
$k8sFiles = @(
    "namespace.yaml",
    "configmap.yaml",
    "deployment.yaml",
    "service.yaml",
    "ingress.yaml"
)

$remoteK8sDir = "~/cpa-manage-api-k8s"
Invoke-RemoteCommand "mkdir -p $remoteK8sDir" "创建远程目录"

foreach ($file in $k8sFiles) {
    $localPath = "k8s/$file"
    $remotePath = "$remoteK8sDir/$file"
    if (-not (Copy-FileViaJump $localPath $remotePath $file)) {
        exit 1
    }
}
Write-Host ""

# 步骤3: 检查kubectl
Write-Host "[3/7] 检查kubectl..." -ForegroundColor Yellow
$kubectlCheck = Invoke-RemoteCommand "which kubectl" "检查kubectl"
if (-not $kubectlCheck) {
    Write-Host "kubectl未安装，请在香港跳板机上安装kubectl" -ForegroundColor Red
    exit 1
}
Write-Host ""

# 步骤4: 设置kubeconfig并验证连接
Write-Host "[4/7] 设置kubeconfig并验证连接..." -ForegroundColor Yellow
$setKubeconfig = "export KUBECONFIG=~/kubeconfig.yaml && kubectl config use-context wumitech.hk"
if (-not (Invoke-RemoteCommand $setKubeconfig "设置kubeconfig")) {
    exit 1
}

$verifyConnection = "export KUBECONFIG=~/kubeconfig.yaml && kubectl cluster-info"
if (-not (Invoke-RemoteCommand $verifyConnection "验证K8s连接")) {
    exit 1
}
Write-Host ""

# 步骤5: 部署应用
Write-Host "[5/7] 部署应用..." -ForegroundColor Yellow
$deployCommands = @(
    "kubectl apply -f $remoteK8sDir/namespace.yaml",
    "kubectl apply -f $remoteK8sDir/configmap.yaml",
    "kubectl apply -f $remoteK8sDir/deployment.yaml",
    "kubectl apply -f $remoteK8sDir/service.yaml",
    "kubectl apply -f $remoteK8sDir/ingress.yaml"
)

foreach ($cmd in $deployCommands) {
    $fullCmd = "export KUBECONFIG=~/kubeconfig.yaml && $cmd"
    if (-not (Invoke-RemoteCommand $fullCmd "部署: $cmd")) {
        Write-Host "部署失败，但继续执行..." -ForegroundColor Yellow
    }
}
Write-Host ""

# 步骤6: 等待Pod启动
Write-Host "[6/7] 等待Pod启动..." -ForegroundColor Yellow
Start-Sleep -Seconds 5

$checkPods = "export KUBECONFIG=~/kubeconfig.yaml && kubectl get pods -n cpa-manage-api"
Invoke-RemoteCommand $checkPods "检查Pod状态"
Write-Host ""

# 步骤7: 检查Service和Ingress
Write-Host "[7/7] 检查Service和Ingress..." -ForegroundColor Yellow
$checkSvc = "export KUBECONFIG=~/kubeconfig.yaml && kubectl get svc -n cpa-manage-api"
Invoke-RemoteCommand $checkSvc "检查Service"

$checkIngress = "export KUBECONFIG=~/kubeconfig.yaml && kubectl get ingress -n cpa-manage-api"
Invoke-RemoteCommand $checkIngress "检查Ingress"
Write-Host ""

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   K8s部署完成!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "访问地址: https://cpa-manage-api.wumitech.com" -ForegroundColor White
Write-Host ""
Write-Host "常用命令（在香港跳板机上执行）:" -ForegroundColor White
Write-Host "  export KUBECONFIG=~/kubeconfig.yaml" -ForegroundColor Gray
Write-Host "  kubectl get pods -n cpa-manage-api" -ForegroundColor Gray
Write-Host "  kubectl logs -f deployment/cpa-manage-api -n cpa-manage-api" -ForegroundColor Gray
Write-Host ""


