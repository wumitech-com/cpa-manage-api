# K8s 端口转发脚本
# 使用方法：在 PowerShell 中执行 .\port-forward.ps1

param(
    [string]$Mode = "simple"
)

$K8sClusterIP = "152.32.212.192"
$JumpServer1 = "106.75.152.136"
$JumpServer2 = "103.149.27.5"
$LocalPort = 5556
$PodPort = 8081
$Namespace = "wumitech-cpa"
$PodName = "cpa-manage-api-5954dbc5b5-kjhld"

Write-Host "=== K8s 端口转发工具 ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "重要提示：此脚本只建立本地到第一层跳板机的隧道" -ForegroundColor Yellow
Write-Host "请先在第一层跳板机($JumpServer1)上执行以下命令：" -ForegroundColor Yellow
Write-Host "  ssh -N -L 5556:127.0.0.1:5556 ubuntu@$JumpServer2" -ForegroundColor White
Write-Host ""
Write-Host "然后确保第二层跳板机($JumpServer2)上已运行:" -ForegroundColor Yellow
Write-Host "  kubectl port-forward -n $Namespace pod/$PodName $LocalPort:$PodPort" -ForegroundColor White
Write-Host ""
Write-Host "按任意键继续建立本地隧道..." -ForegroundColor Gray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
Write-Host ""

if ($Mode -eq "direct") {
    Write-Host "模式: 直接连接到 K8s 集群公网地址" -ForegroundColor Green
    Write-Host "K8s 集群公网地址: $K8sClusterIP" -ForegroundColor Yellow
    Write-Host "本地端口: $LocalPort -> 集群端口: $PodPort" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "注意: 此方式需要 K8s API Server 或服务直接暴露在公网" -ForegroundColor Yellow
    Write-Host "按 Ctrl+C 停止端口转发" -ForegroundColor Gray
    Write-Host ""
    
    # 直接连接到 K8s 集群（如果服务暴露在公网）
    # 使用密钥认证，禁用密码认证
    ssh -i ~/.ssh/id_rsa `
        -o PreferredAuthentications=publickey `
        -o PasswordAuthentication=no `
        -o StrictHostKeyChecking=accept-new `
        -L ${LocalPort}:127.0.0.1:${PodPort} `
        ubuntu@$K8sClusterIP -N
}
else {
    Write-Host "模式: 通过两层跳板机连接（分步方式）" -ForegroundColor Green
    Write-Host "本地端口: $LocalPort -> 第一层跳板机: $JumpServer1:5556" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "建立本地到第一层跳板机的 SSH 隧道..." -ForegroundColor Cyan
    Write-Host "按 Ctrl+C 停止端口转发" -ForegroundColor Gray
    Write-Host ""
    
    # 建立本地到第一层跳板机的 SSH 隧道
    # 第一层跳板机需要先建立到第二层的隧道
    ssh -i ~/.ssh/id_rsa `
        -o PreferredAuthentications=publickey `
        -o PasswordAuthentication=no `
        -o StrictHostKeyChecking=no `
        -L ${LocalPort}:127.0.0.1:${LocalPort} `
        ubuntu@$JumpServer1 -N
}

Write-Host ""
Write-Host "隧道已关闭" -ForegroundColor Red

