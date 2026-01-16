# CPA Manage API Deployment Script - Mainboard Server
# Jump Host: 106.75.152.136 (ubuntu)
# Target Server: 206.119.108.2 (ubuntu)

$ErrorActionPreference = "Continue"

$JumpHost = "106.75.152.136"
$JumpUser = "ubuntu"
$TargetHost = "206.119.108.2"
$TargetUser = "ubuntu"
$PrivateKeyPath = "$env:USERPROFILE\.ssh\id_rsa"
$ImageName = "uhub.service.ucloud.cn/wumitech.public/cpa-manage-api:latest"
$ContainerName = "cpa-manage-api"
$Port = 8081
$RemoteDir = "/home/ubuntu/cpa-manage-api"
$ImageTarFile = "cpa-manage-api-image.tar"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   CPA Manage API Deployment" -ForegroundColor Cyan
Write-Host "   Jump Host: $JumpUser@$JumpHost" -ForegroundColor Cyan
Write-Host "   Target Server: $TargetUser@$TargetHost" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

if (-not (Test-Path $PrivateKeyPath)) {
    Write-Host "Error: Private key file not found: $PrivateKeyPath" -ForegroundColor Red
    exit 1
}

function Invoke-RemoteCommand {
    param([string]$Command, [string]$Description)
    Write-Host "Executing: $Description" -ForegroundColor Yellow
    $sshCmd = "ssh -i `"$PrivateKeyPath`" -o StrictHostKeyChecking=no ${JumpUser}@${JumpHost} `"ssh -o StrictHostKeyChecking=no ${TargetUser}@${TargetHost} '$Command'`""
    try {
        $output = Invoke-Expression $sshCmd 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  Execution failed" -ForegroundColor Red
            return $false
        }
        Write-Host "  Success" -ForegroundColor Green
        return $true
    } catch {
        Write-Host "  Exception: $_" -ForegroundColor Red
        return $false
    }
}

function Copy-FileViaJump {
    param([string]$LocalPath, [string]$RemotePath, [string]$Description)
    Write-Host "Uploading: $Description" -ForegroundColor Yellow
    $tempPath = "/tmp/$(Split-Path -Leaf $LocalPath)"
    $scpTarget1 = "${JumpUser}@${JumpHost}:$tempPath"
    
    $scpArgs = @("-i", "`"$PrivateKeyPath`"", "-o", "StrictHostKeyChecking=no", $LocalPath, $scpTarget1)
    $proc1 = Start-Process -FilePath "scp" -ArgumentList $scpArgs -NoNewWindow -Wait -PassThru
    if ($proc1.ExitCode -ne 0) {
        Write-Host "  Failed to upload to jump host" -ForegroundColor Red
        return $false
    }
    
    $scpCmd2 = "ssh -i `"$PrivateKeyPath`" -o StrictHostKeyChecking=no $JumpUser@$JumpHost `"scp -o StrictHostKeyChecking=no $tempPath ${TargetUser}@${TargetHost}:${RemotePath}`""
    Invoke-Expression $scpCmd2 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  Failed to transfer from jump host to target server" -ForegroundColor Red
        return $false
    }
    
    $cleanup = "ssh -i `"$PrivateKeyPath`" -o StrictHostKeyChecking=no $JumpUser@$JumpHost `"rm -f $tempPath`""
    Invoke-Expression $cleanup 2>&1 | Out-Null
    
    Write-Host "  File uploaded successfully" -ForegroundColor Green
    return $true
}

Write-Host "[1/8] Building Maven project..." -ForegroundColor Yellow
mvn clean package -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "  Maven build failed" -ForegroundColor Red
    exit 1
}
Write-Host "  Maven build successful" -ForegroundColor Green
Write-Host ""

Write-Host "[2/8] Building Docker image..." -ForegroundColor Yellow
docker build -t $ImageName .
if ($LASTEXITCODE -ne 0) {
    Write-Host "  Docker image build failed" -ForegroundColor Red
    exit 1
}
Write-Host "  Docker image build successful" -ForegroundColor Green
Write-Host ""

Write-Host "[3/8] Saving Docker image as tar file..." -ForegroundColor Yellow
if (Test-Path $ImageTarFile) {
    Remove-Item $ImageTarFile -Force
}
docker save -o $ImageTarFile $ImageName
if ($LASTEXITCODE -ne 0) {
    Write-Host "  Failed to save image" -ForegroundColor Red
    exit 1
}
$imageSize = (Get-Item $ImageTarFile).Length / 1MB
Write-Host "  Image file size: $([math]::Round($imageSize, 2)) MB" -ForegroundColor Green
Write-Host ""

Write-Host "[4/8] Uploading image file to target server..." -ForegroundColor Yellow
$remoteImageFile = "/tmp/$ImageTarFile"
if (-not (Copy-FileViaJump $ImageTarFile $remoteImageFile "Docker image file")) {
    Write-Host "  Failed to upload image file" -ForegroundColor Red
    Remove-Item $ImageTarFile -ErrorAction SilentlyContinue
    exit 1
}
Write-Host ""

Write-Host "[5/8] Loading Docker image on target server..." -ForegroundColor Yellow
$loadCmd = "docker load -i $remoteImageFile; rm -f $remoteImageFile"
if (-not (Invoke-RemoteCommand $loadCmd "Load Docker image")) {
    Write-Host "  Failed to load image" -ForegroundColor Red
    exit 1
}
Write-Host ""

Write-Host "[6/8] Stopping and removing old container..." -ForegroundColor Yellow
Invoke-RemoteCommand "docker stop $ContainerName 2>&1 | Out-Null; echo done" "Stop old container" | Out-Null
Invoke-RemoteCommand "docker rm $ContainerName 2>&1 | Out-Null; echo done" "Remove old container" | Out-Null
Write-Host ""

Write-Host "[7/8] Starting new container..." -ForegroundColor Yellow
$startCmd = "docker run -d --name $ContainerName --restart unless-stopped --network host -v ${RemoteDir}/logs:/app/logs -e TZ=Asia/Shanghai -e JAVA_OPTS='-Xms512m -Xmx2048m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Duser.timezone=Asia/Shanghai' -e SPRING_PROFILES_ACTIVE=prod -e MAINBOARD_PUBLIC_IP=206.119.108.2 -e MAINBOARD_APPIUM_SERVER=10.7.124.25 $ImageName"
if (-not (Invoke-RemoteCommand $startCmd "Start Docker container")) {
    Write-Host "  Failed to start container" -ForegroundColor Red
    exit 1
}
Write-Host ""

Write-Host "[8/8] Waiting for container to start and checking status..." -ForegroundColor Yellow
Start-Sleep -Seconds 10
Invoke-RemoteCommand "docker ps -a | grep $ContainerName" "Check container status"
Write-Host ""

Write-Host "Viewing container logs (last 30 lines)..." -ForegroundColor Yellow
Invoke-RemoteCommand "docker logs --tail 30 $ContainerName" "View container logs"
Write-Host ""

Remove-Item $ImageTarFile -ErrorAction SilentlyContinue
Write-Host "  Temporary files cleaned" -ForegroundColor Green
Write-Host ""

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   Deployment Complete!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Deployment Info:" -ForegroundColor White
Write-Host "  Server: $TargetUser@$TargetHost" -ForegroundColor Gray
Write-Host "  Container: $ContainerName" -ForegroundColor Gray
Write-Host "  Port: $Port" -ForegroundColor Gray
Write-Host ""
