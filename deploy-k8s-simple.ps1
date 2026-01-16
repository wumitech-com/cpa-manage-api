# CPA Manage API K8s 简化部署脚本
# 使用方式：先配置香港跳板机IP，然后执行此脚本

$ErrorActionPreference = "Continue"

# ========== 配置区域 ==========
# 请填写香港跳板机的IP或主机名
$hkJumpHost = ""  # 例如: "10.x.x.x" 或 "hk-jump-server"
$hkJumpUser = "ubuntu"
$firstJumpHost = "106.75.152.136"
$firstJumpUser = "ubuntu"
$sshKeyPath = "$env:USERPROFILE\.ssh\id_rsa"
# ==============================

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   CPA Manage API K8s 部署" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 检查配置
if ([string]::IsNullOrEmpty($hkJumpHost)) {
    Write-Host "❌ 请先配置香港跳板机IP！" -ForegroundColor Red
    Write-Host ""
    Write-Host "编辑此脚本，设置 `$hkJumpHost 变量" -ForegroundColor Yellow
    Write-Host "例如: `$hkJumpHost = `"10.x.x.x`"" -ForegroundColor Gray
    exit 1
}

# 检查文件
if (-not (Test-Path $sshKeyPath)) {
    Write-Host "❌ SSH密钥不存在: $sshKeyPath" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path "k8s")) {
    Write-Host "❌ k8s 目录不存在" -ForegroundColor Red
    exit 1
}

Write-Host "✅ 配置检查通过" -ForegroundColor Green
Write-Host "  第一层跳板机: $firstJumpUser@$firstJumpHost" -ForegroundColor Gray
Write-Host "  香港跳板机: $hkJumpUser@$hkJumpHost" -ForegroundColor Gray
Write-Host ""

# 步骤1: 上传文件（不需要上传kubeconfig，使用现有的）
Write-Host "[1/3] 上传K8s配置文件到香港跳板机..." -ForegroundColor Yellow

# 创建临时目录
$tempDir = "/tmp/cpa-k8s-$(Get-Date -Format 'yyyyMMddHHmmss')"
ssh -i $sshKeyPath -o StrictHostKeyChecking=no "$firstJumpUser@$firstJumpHost" "mkdir -p $tempDir" | Out-Null

# 上传到第一层跳板机（使用合并文件，更简单）
Write-Host "  上传到第一层跳板机..." -ForegroundColor Gray
if (Test-Path "k8s\all.yaml") {
    # 使用合并文件
    scp -i $sshKeyPath -o StrictHostKeyChecking=no "k8s\all.yaml" "${firstJumpUser}@${firstJumpHost}:${tempDir}/" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ❌ 上传 all.yaml 失败" -ForegroundColor Red
        exit 1
    }
} else {
    # 分别上传各个文件
    $files = @("namespace.yaml", "configmap.yaml", "deployment.yaml", "service.yaml", "ingress.yaml")
    foreach ($file in $files) {
        scp -i $sshKeyPath -o StrictHostKeyChecking=no "k8s\$file" "${firstJumpUser}@${firstJumpHost}:${tempDir}/" 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  ❌ 上传 $file 失败" -ForegroundColor Red
            exit 1
        }
    }
}

# 从第一层跳板机上传到香港跳板机
Write-Host "  上传到香港跳板机..." -ForegroundColor Gray
ssh -i $sshKeyPath -o StrictHostKeyChecking=no "$firstJumpUser@$firstJumpHost" "scp -r $tempDir $hkJumpUser@$hkJumpHost:~/cpa-k8s" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "  ❌ 上传到香港跳板机失败" -ForegroundColor Red
    Write-Host "  请检查香港跳板机IP和SSH连接" -ForegroundColor Yellow
    exit 1
}

Write-Host "  ✅ 文件上传成功（使用现有的kubeconfig）" -ForegroundColor Green
Write-Host ""

# 步骤2: 执行部署命令
Write-Host "[2/3] 在香港跳板机上执行部署..." -ForegroundColor Yellow

$deployScript = @"
# 使用现有的kubeconfig（context: kubernetes）
kubectl config use-context kubernetes

echo "验证K8s连接..."
kubectl cluster-info

echo "部署应用..."
if [ -f ~/cpa-k8s/all.yaml ]; then
    # 使用合并文件，一次性部署
    kubectl apply -f ~/cpa-k8s/all.yaml
else
    # 分别部署各个文件
    kubectl apply -f ~/cpa-k8s/namespace.yaml
    kubectl apply -f ~/cpa-k8s/configmap.yaml
    kubectl apply -f ~/cpa-k8s/deployment.yaml
    kubectl apply -f ~/cpa-k8s/service.yaml
    kubectl apply -f ~/cpa-k8s/ingress.yaml
fi

echo "等待Pod启动..."
sleep 5

echo "检查部署状态..."
kubectl get pods -n cpa-manage-api
kubectl get svc -n cpa-manage-api
kubectl get ingress -n cpa-manage-api
"@

# 通过两层跳板机执行
$deployScript | ssh -i $sshKeyPath -o StrictHostKeyChecking=no "$firstJumpUser@$firstJumpHost" "ssh -i ~/.ssh/id_rsa -o StrictHostKeyChecking=no $hkJumpUser@$hkJumpHost bash"

if ($LASTEXITCODE -ne 0) {
    Write-Host "  ⚠️  部署过程中可能有错误，请检查输出" -ForegroundColor Yellow
} else {
    Write-Host "  ✅ 部署完成" -ForegroundColor Green
}
Write-Host ""

# 步骤3: 显示访问信息
Write-Host "[3/3] 部署信息" -ForegroundColor Yellow
Write-Host ""
Write-Host "访问地址: https://cpa-manage-api.wumitech.com" -ForegroundColor White
Write-Host ""
Write-Host "常用命令（在香港跳板机上执行）:" -ForegroundColor White
Write-Host "  kubectl config use-context kubernetes" -ForegroundColor Gray
Write-Host "  kubectl get pods -n cpa-manage-api" -ForegroundColor Gray
Write-Host "  kubectl logs -f deployment/cpa-manage-api -n cpa-manage-api" -ForegroundColor Gray
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan

