# CPA Manage API K8s部署脚本
$ErrorActionPreference = "Continue"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   CPA Manage API K8s部署" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 检查镜像是否存在
Write-Host "[0/6] 检查Docker镜像..." -ForegroundColor Yellow
$imageName = "uhub.service.ucloud.cn/wumitech.public/cpa-manage-api:latest"
$imageExists = docker images $imageName --format "{{.Repository}}:{{.Tag}}" 2>&1
if ($LASTEXITCODE -ne 0 -or -not $imageExists) {
    Write-Host "镜像不存在，需要先构建镜像" -ForegroundColor Yellow
    Write-Host "是否现在构建并推送镜像? (y/n)" -ForegroundColor Yellow
    $buildImage = Read-Host
    if ($buildImage -eq "y" -or $buildImage -eq "Y") {
        Write-Host "执行镜像构建脚本..." -ForegroundColor Gray
        .\build-and-push-image.ps1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "镜像构建失败，部署中止!" -ForegroundColor Red
            exit 1
        }
    } else {
        Write-Host "请先运行 .\build-and-push-image.ps1 构建镜像" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "镜像已存在: $imageName" -ForegroundColor Green
}
Write-Host ""

# 检查kubectl是否安装
Write-Host "[1/6] 检查kubectl..." -ForegroundColor Yellow
$kubectlPath = Get-Command kubectl -ErrorAction SilentlyContinue
if (-not $kubectlPath) {
    Write-Host "kubectl未安装，请先安装kubectl" -ForegroundColor Red
    exit 1
}
Write-Host "kubectl已安装: $($kubectlPath.Source)" -ForegroundColor Green
Write-Host ""

# 检查kubeconfig
Write-Host "[2/6] 检查kubeconfig..." -ForegroundColor Yellow
if (-not (Test-Path "k8s/kubeconfig.yaml")) {
    Write-Host "kubeconfig.yaml不存在，请先创建" -ForegroundColor Red
    exit 1
}
Write-Host "kubeconfig.yaml存在" -ForegroundColor Green
Write-Host ""

# 设置kubeconfig
Write-Host "[3/6] 设置kubeconfig..." -ForegroundColor Yellow
$env:KUBECONFIG = (Resolve-Path "k8s/kubeconfig.yaml").Path
kubectl config use-context wumitech.hk
if ($LASTEXITCODE -ne 0) {
    Write-Host "设置kubeconfig失败!" -ForegroundColor Red
    exit 1
}
Write-Host "kubeconfig设置成功" -ForegroundColor Green
Write-Host ""

# 验证连接
Write-Host "[4/6] 验证K8s连接..." -ForegroundColor Yellow
kubectl cluster-info
if ($LASTEXITCODE -ne 0) {
    Write-Host "无法连接到K8s集群!" -ForegroundColor Red
    exit 1
}
Write-Host "K8s连接成功" -ForegroundColor Green
Write-Host ""

# 部署应用
Write-Host "[5/6] 部署应用..." -ForegroundColor Yellow
Write-Host "创建命名空间..." -ForegroundColor Gray
kubectl apply -f k8s/namespace.yaml
if ($LASTEXITCODE -ne 0) {
    Write-Host "创建命名空间失败!" -ForegroundColor Red
    exit 1
}

Write-Host "创建ConfigMap..." -ForegroundColor Gray
kubectl apply -f k8s/configmap.yaml
if ($LASTEXITCODE -ne 0) {
    Write-Host "创建ConfigMap失败!" -ForegroundColor Red
    exit 1
}

Write-Host "创建Deployment..." -ForegroundColor Gray
kubectl apply -f k8s/deployment.yaml
if ($LASTEXITCODE -ne 0) {
    Write-Host "创建Deployment失败!" -ForegroundColor Red
    exit 1
}

Write-Host "创建Service..." -ForegroundColor Gray
kubectl apply -f k8s/service.yaml
if ($LASTEXITCODE -ne 0) {
    Write-Host "创建Service失败!" -ForegroundColor Red
    exit 1
}

Write-Host "应用部署成功!" -ForegroundColor Green
Write-Host ""

# 可选：部署Ingress
Write-Host "[6/6] 部署Ingress（可选）..." -ForegroundColor Yellow
$deployIngress = Read-Host "是否部署Ingress? (y/n)"
if ($deployIngress -eq "y" -or $deployIngress -eq "Y") {
    kubectl apply -f k8s/ingress.yaml
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Ingress部署失败!" -ForegroundColor Red
    } else {
        Write-Host "Ingress部署成功!" -ForegroundColor Green
    }
} else {
    Write-Host "跳过Ingress部署" -ForegroundColor Gray
}
Write-Host ""

# 等待Pod就绪
Write-Host "等待Pod启动..." -ForegroundColor Yellow
Start-Sleep -Seconds 5

Write-Host "检查Pod状态..." -ForegroundColor Yellow
kubectl get pods -n cpa-manage-api
Write-Host ""

Write-Host "检查Service..." -ForegroundColor Yellow
kubectl get svc -n cpa-manage-api
Write-Host ""

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   K8s部署完成!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "常用命令:" -ForegroundColor White
Write-Host "  查看Pod: kubectl get pods -n cpa-manage-api" -ForegroundColor Gray
Write-Host "  查看日志: kubectl logs -f deployment/cpa-manage-api -n cpa-manage-api" -ForegroundColor Gray
Write-Host "  查看Service: kubectl get svc -n cpa-manage-api" -ForegroundColor Gray
Write-Host "  删除部署: kubectl delete namespace cpa-manage-api" -ForegroundColor Gray
Write-Host ""

