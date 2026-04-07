# CPA Manage API Docker Image Build and Push Script
$ErrorActionPreference = "Continue"
param(
    [switch]$ForceFrontendInstall
)

# Always run from script directory to avoid picking wrong Dockerfile/context.
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not [string]::IsNullOrWhiteSpace($scriptDir)) {
    Set-Location $scriptDir
}

$imageRegistry = "uhub.service.ucloud.cn/wumitech.public"
$imageName = "cpa-manage-api"

# 生成时间戳标签（格式：YYYYMMDD-HHMMSS）
# 使用多种方式尝试生成时间戳
$timestampTag = $null
try {
    $timestampTag = Get-Date -Format "yyyyMMdd-HHmmss"
} catch {
    try {
        $timestampTag = [DateTime]::Now.ToString("yyyyMMdd-HHmmss")
    } catch {
        # 如果都失败，使用当前日期时间的手动格式化
        $now = Get-Date
        $timestampTag = "{0:yyyy}{0:MM}{0:dd}-{0:HH}{0:mm}{0:ss}" -f $now
    }
}

# 如果仍然为空，使用固定值（不推荐，但至少可以继续）
if ([string]::IsNullOrWhiteSpace($timestampTag)) {
    $timestampTag = "manual-$(Get-Random)"
    Write-Host "Warning: Using random tag instead of timestamp: $timestampTag" -ForegroundColor Yellow
}

$latestTag = "latest"

$fullImageNameTimestamp = "${imageRegistry}/${imageName}:${timestampTag}"
$fullImageNameLatest = "${imageRegistry}/${imageName}:${latestTag}"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   CPA Manage API Image Build and Push" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "时间戳标签: $timestampTag" -ForegroundColor Yellow
Write-Host "Latest标签: $latestTag" -ForegroundColor Yellow
Write-Host ""

# Step 1: Build Vue frontend (production assets)
Write-Host "[1/7] Building Vue frontend..." -ForegroundColor Yellow
$frontendDir = "src\main\resources\static\console-vue"
$frontendPackageJson = Join-Path $frontendDir "package.json"
$frontendIndex = Join-Path $frontendDir "index.html"
$frontendIndexTemplate = Join-Path $frontendDir "index.template.html"
if (-not (Test-Path $frontendPackageJson)) {
    Write-Host "Frontend package.json not found: $frontendPackageJson" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $frontendIndexTemplate)) {
    Write-Host "Frontend index template missing: $frontendIndexTemplate" -ForegroundColor Red
    exit 1
}

# Ensure source index is always in Vite entry form before running frontend build.
Copy-Item $frontendIndexTemplate $frontendIndex -Force

npm -v 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "npm is not available. Please install Node.js/npm first." -ForegroundColor Red
    exit 1
}

Push-Location $frontendDir
try {
    $nodeModulesDir = Join-Path (Get-Location) "node_modules"
    $needInstall = $ForceFrontendInstall -or (-not (Test-Path $nodeModulesDir))
    $vueTscCmd = Get-Command vue-tsc -ErrorAction SilentlyContinue
    if (-not $vueTscCmd) {
        $binVueTsc = Join-Path (Get-Location) "node_modules\.bin\vue-tsc.cmd"
        if (-not (Test-Path $binVueTsc)) {
            $needInstall = $true
        }
    }

    if ($needInstall) {
        Write-Host "Installing frontend dependencies..." -ForegroundColor Gray
        npm install --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) {
            Write-Host "Frontend dependency install failed!" -ForegroundColor Red
            Write-Host "Hint: close Vite/node processes, then retry with admin if needed." -ForegroundColor Yellow
            exit 1
        }
    } else {
        Write-Host "Using existing node_modules (skip install)." -ForegroundColor Gray
    }

    npm run build
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Frontend build failed!" -ForegroundColor Red
        exit 1
    }

    $distDir = Join-Path (Get-Location) "dist"
    $distIndex = Join-Path $distDir "index.html"
    if (-not (Test-Path $distIndex)) {
        Write-Host "Frontend dist output missing: $distIndex" -ForegroundColor Red
        exit 1
    }

    Copy-Item (Join-Path $distDir "*") (Get-Location) -Recurse -Force
} finally {
    Pop-Location
}
Write-Host "Vue frontend build success!" -ForegroundColor Green
Write-Host ""

# Step 2: Check Docker
Write-Host "[2/7] Checking Docker..." -ForegroundColor Yellow
docker info 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker is not running, please start Docker Desktop" -ForegroundColor Red
    exit 1
}
Write-Host "Docker is running" -ForegroundColor Green
Write-Host ""

# Step 3: Build Maven project
Write-Host "[3/7] Building Maven project..." -ForegroundColor Yellow
mvn package -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "Maven build failed!" -ForegroundColor Red
    Write-Host "Hint: if target is locked, stop running Java process and retry." -ForegroundColor Yellow
    exit 1
}
Write-Host "Maven build success!" -ForegroundColor Green
Write-Host ""

# Restore source index to template form to avoid polluting repo with built index.html.
Copy-Item $frontendIndexTemplate $frontendIndex -Force

# Check JAR file
$jarFile = "target\cpa-manage-api-0.0.1-SNAPSHOT.jar"
if (-not (Test-Path $jarFile)) {
    Write-Host "JAR file not found: $jarFile" -ForegroundColor Red
    exit 1
}
$jarInfo = Get-Item $jarFile
Write-Host "JAR file size: $([math]::Round($jarInfo.Length / 1MB, 2)) MB" -ForegroundColor Gray
Write-Host ""

# Step 4: Build Docker image
Write-Host "[4/7] Building Docker image..." -ForegroundColor Yellow
Write-Host "Building with tags:" -ForegroundColor Gray
Write-Host "  - $fullImageNameTimestamp" -ForegroundColor Gray
Write-Host "  - $fullImageNameLatest" -ForegroundColor Gray
$dockerfilePath = Join-Path (Get-Location) "Dockerfile"
if (-not (Test-Path $dockerfilePath)) {
    Write-Host "Dockerfile not found: $dockerfilePath" -ForegroundColor Red
    exit 1
}
$dockerfileFirstLine = (Get-Content $dockerfilePath -TotalCount 1)
Write-Host "Using Dockerfile: $dockerfilePath" -ForegroundColor Gray
Write-Host "Dockerfile base line: $dockerfileFirstLine" -ForegroundColor Gray

# Force classic builder and forbid pulling remote base image metadata.
# This makes docker build use local pulled base image first.
$env:DOCKER_BUILDKIT = "0"
docker build --pull=false -f $dockerfilePath -t ${fullImageNameTimestamp} -t ${fullImageNameLatest} .
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker image build failed!" -ForegroundColor Red
    exit 1
}
Write-Host "Docker image build success!" -ForegroundColor Green
Write-Host ""

# Step 5: Check registry login
Write-Host "[5/7] Checking registry login..." -ForegroundColor Yellow
$loginRequired = $false
$dockerConfig = "$env:USERPROFILE\.docker\config.json"
if (Test-Path $dockerConfig) {
    $configContent = Get-Content $dockerConfig -Raw
    if ($configContent -notmatch "uhub\.service\.ucloud\.cn") {
        $loginRequired = $true
    }
} else {
    $loginRequired = $true
}

if ($loginRequired) {
    Write-Host "May need to login to registry" -ForegroundColor Yellow
    Write-Host "If needed, run: docker login $imageRegistry" -ForegroundColor Gray
} else {
    Write-Host "Registry login info exists" -ForegroundColor Green
}
Write-Host ""

# Step 6: Push timestamp tag to registry
Write-Host "[6/7] Pushing timestamp tag to registry..." -ForegroundColor Yellow
Write-Host "Pushing image: $fullImageNameTimestamp" -ForegroundColor Gray
docker push ${fullImageNameTimestamp}
if ($LASTEXITCODE -ne 0) {
    Write-Host "Timestamp tag push failed!" -ForegroundColor Red
    if ($loginRequired) {
        Write-Host "Hint: Please login first: docker login $imageRegistry" -ForegroundColor Yellow
    }
    exit 1
}
Write-Host "Timestamp tag push success!" -ForegroundColor Green
Write-Host ""

# Step 7: Push latest tag to registry
Write-Host "[7/7] Pushing latest tag to registry..." -ForegroundColor Yellow
Write-Host "Pushing image: $fullImageNameLatest" -ForegroundColor Gray
docker push ${fullImageNameLatest}
if ($LASTEXITCODE -ne 0) {
    Write-Host "Latest tag push failed!" -ForegroundColor Red
    if ($loginRequired) {
        Write-Host "Hint: Please login first: docker login $imageRegistry" -ForegroundColor Yellow
    }
    exit 1
}
Write-Host "Latest tag push success!" -ForegroundColor Green
Write-Host ""

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   Image Build and Push Complete!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Image info:" -ForegroundColor White
Write-Host "  时间戳版本: $fullImageNameTimestamp" -ForegroundColor Gray
Write-Host "  Latest版本: $fullImageNameLatest" -ForegroundColor Gray
Write-Host ""
Write-Host "回滚到时间戳版本:" -ForegroundColor White
Write-Host "  修改 deployment.yaml 中的 image 为: ${imageRegistry}/${imageName}:${timestampTag}" -ForegroundColor Yellow
Write-Host ""
Write-Host "View images:" -ForegroundColor White
Write-Host "  docker images | grep ${imageName}" -ForegroundColor Gray
Write-Host ""

