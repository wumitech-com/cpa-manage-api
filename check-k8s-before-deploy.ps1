# 检查K8s部署前的冲突
$ErrorActionPreference = "Continue"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   K8s部署前检查" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 设置kubeconfig
$env:KUBECONFIG = (Resolve-Path "k8s/kubeconfig.yaml").Path
kubectl config use-context wumitech.hk 2>&1 | Out-Null

Write-Host "[1/4] 检查Namespace..." -ForegroundColor Yellow
$namespaceExists = kubectl get namespace cpa-manage-api 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host "  ⚠️  Namespace 'cpa-manage-api' 已存在" -ForegroundColor Yellow
    Write-Host "  这是正常的，kubectl apply 会更新现有资源" -ForegroundColor Gray
} else {
    Write-Host "  ✅ Namespace 'cpa-manage-api' 不存在，将创建" -ForegroundColor Green
}
Write-Host ""

Write-Host "[2/4] 检查Ingress域名冲突..." -ForegroundColor Yellow
$allIngresses = kubectl get ingress --all-namespaces -o json 2>&1 | ConvertFrom-Json
$domainConflict = $false
if ($allIngresses.items) {
    foreach ($ingress in $allIngresses.items) {
        if ($ingress.spec.rules) {
            foreach ($rule in $ingress.spec.rules) {
                if ($rule.host -eq "cpa-manage-api.wumitech.com") {
                    $namespace = $ingress.metadata.namespace
                    $name = $ingress.metadata.name
                    Write-Host "  ⚠️  域名 'cpa-manage-api.wumitech.com' 已被使用:" -ForegroundColor Yellow
                    Write-Host "     Namespace: $namespace" -ForegroundColor Gray
                    Write-Host "     Ingress: $name" -ForegroundColor Gray
                    $domainConflict = $true
                }
            }
        }
    }
}
if (-not $domainConflict) {
    Write-Host "  ✅ 域名 'cpa-manage-api.wumitech.com' 可用" -ForegroundColor Green
}
Write-Host ""

Write-Host "[3/4] 检查端口冲突..." -ForegroundColor Yellow
$allServices = kubectl get svc --all-namespaces -o json 2>&1 | ConvertFrom-Json
$portConflict = $false
if ($allServices.items) {
    foreach ($svc in $allServices.items) {
        if ($svc.spec.ports) {
            foreach ($port in $svc.spec.ports) {
                if ($port.port -eq 8081 -and $svc.metadata.namespace -eq "cpa-manage-api") {
                    Write-Host "  ⚠️  端口 8081 在 namespace 'cpa-manage-api' 中已被使用:" -ForegroundColor Yellow
                    Write-Host "     Service: $($svc.metadata.name)" -ForegroundColor Gray
                    $portConflict = $true
                }
            }
        }
    }
}
if (-not $portConflict) {
    Write-Host "  ✅ 端口 8081 在 namespace 'cpa-manage-api' 中可用" -ForegroundColor Green
}
Write-Host ""

Write-Host "[4/4] 检查资源配额..." -ForegroundColor Yellow
$namespaceQuota = kubectl get resourcequota -n cpa-manage-api 2>&1
if ($LASTEXITCODE -eq 0 -and $namespaceQuota) {
    Write-Host "  ℹ️  Namespace 有资源配额限制" -ForegroundColor Gray
    Write-Host $namespaceQuota
} else {
    Write-Host "  ✅ 无资源配额限制" -ForegroundColor Green
}
Write-Host ""

Write-Host "========================================" -ForegroundColor Cyan
if ($domainConflict) {
    Write-Host "  ⚠️  发现域名冲突，请检查！" -ForegroundColor Yellow
    Write-Host "  建议：修改 k8s/ingress.yaml 中的域名" -ForegroundColor Yellow
} else {
    Write-Host "  ✅ 检查通过，可以安全部署" -ForegroundColor Green
}
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""


