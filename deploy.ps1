# 部署脚本 - 编译、传输和停止旧进程
# 用户需要手动执行启动步骤

$ErrorActionPreference = "Continue"
$privateKey = "$env:USERPROFILE\.ssh\id_rsa"
$jumpHost = "ubuntu@106.75.152.136"
$targetHost = "ubuntu@10.13.135.74"
$appDir = "/home/ubuntu/cpa-manage-api"

Write-Host "=== 开始部署流程 ===" -ForegroundColor Green

# 步骤1: 编译项目
Write-Host "`n[1/5] 编译项目..." -ForegroundColor Yellow
mvn clean package -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "编译失败！" -ForegroundColor Red
    exit 1
}
Write-Host "编译成功！" -ForegroundColor Green

# 步骤2: 传输jar文件到跳板机
Write-Host "`n[2/5] 传输jar文件到跳板机..." -ForegroundColor Yellow
scp -i $privateKey -o StrictHostKeyChecking=no target\cpa-manage-api-0.0.1-SNAPSHOT.jar ${jumpHost}:/tmp/cpa-manage-api-0.0.1-SNAPSHOT.jar
if ($LASTEXITCODE -ne 0) {
    Write-Host "传输到跳板机失败！" -ForegroundColor Red
    exit 1
}
Write-Host "传输到跳板机成功！" -ForegroundColor Green

# 步骤3: 从跳板机传输到目标服务器
Write-Host "`n[3/5] 从跳板机传输到目标服务器..." -ForegroundColor Yellow
ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost "scp -o StrictHostKeyChecking=no /tmp/cpa-manage-api-0.0.1-SNAPSHOT.jar ${targetHost}:/tmp/"
if ($LASTEXITCODE -ne 0) {
    Write-Host "传输到目标服务器失败！" -ForegroundColor Red
    exit 1
}
Write-Host "传输到目标服务器成功！" -ForegroundColor Green

# 步骤4: 停止旧进程
Write-Host "`n[4/5] 停止旧进程..." -ForegroundColor Yellow
ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost "ssh -o StrictHostKeyChecking=no $targetHost 'cd $appDir && ps aux | grep java | grep cpa-manage-api | grep -v grep | awk \"{print \$2}\" | xargs kill -9 2>/dev/null || sudo pkill -f cpa-manage-api || true'"
Write-Host "旧进程已停止" -ForegroundColor Green

# 步骤5: 复制jar文件到应用目录
Write-Host "`n[5/5] 复制jar文件到应用目录..." -ForegroundColor Yellow
ssh -i $privateKey -o StrictHostKeyChecking=no $jumpHost "ssh -o StrictHostKeyChecking=no $targetHost 'cp /tmp/cpa-manage-api-0.0.1-SNAPSHOT.jar $appDir/cpa-manage-api-0.0.1-SNAPSHOT.jar'"
if ($LASTEXITCODE -ne 0) {
    Write-Host "复制jar文件失败！" -ForegroundColor Red
    exit 1
}
Write-Host "复制jar文件成功！" -ForegroundColor Green

Write-Host "`n=== 部署准备完成 ===" -ForegroundColor Green
Write-Host "请手动执行以下命令启动应用：" -ForegroundColor Cyan
Write-Host "  ssh -i $privateKey $jumpHost" -ForegroundColor White
Write-Host "  ssh $targetHost" -ForegroundColor White
Write-Host "  cd $appDir" -ForegroundColor White
Write-Host "  nohup java -jar cpa-manage-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod > app.log 2>&1 &" -ForegroundColor White
Write-Host "  或者使用Java 17的完整路径：" -ForegroundColor Yellow
Write-Host "  nohup /usr/lib/jvm/java-17-openjdk-arm64/bin/java -jar cpa-manage-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod > app.log 2>&1 &" -ForegroundColor White



