# ADB自动连接所有云手机脚本
# 功能：自动获取服务器上所有云手机的proxy端口，并在本地建立ADB连接
#
# 使用方法：
#   1. 确保已安装ADB并添加到PATH环境变量
#   2. 确保SSH密钥文件存在（默认使用项目中的ssh_key，或使用 ~/.ssh/id_rsa）
#   3. 运行脚本: .\adb-connect-all.ps1
#
# 说明：
#   - 脚本会自动连接到服务器，获取每个proxy容器的端口映射
#   - 然后在本地执行 adb connect 命令建立连接
#   - 如果端口发生变化，重新运行脚本即可更新连接
#   - 脚本会每隔6分钟自动执行一次
#   - 按 Ctrl+C 可以停止脚本

$ErrorActionPreference = "Continue"

# 执行间隔（分钟）
$intervalMinutes = 6

# 服务器配置：内网IP -> 公网IP
$servers = @{
    "10.7.136.129" = "107.150.119.254"
}

# SSH配置
$sshUsername = "ubuntu"
$sshKey = "$env:USERPROFILE\.ssh\id_rsa"
$jumpHost = "ubuntu@106.75.152.136"  # 跳板机

# 如果ssh_key文件存在，使用项目中的密钥
if (Test-Path "ssh_key") {
    $sshKey = Resolve-Path "ssh_key"
    Write-Host "使用项目中的SSH密钥: $sshKey" -ForegroundColor Gray
} else {
    Write-Host "使用默认SSH密钥: $sshKey" -ForegroundColor Gray
    if (-not (Test-Path $sshKey)) {
        Write-Host "警告: SSH密钥文件不存在，连接可能失败！" -ForegroundColor Yellow
    }
}
Write-Host "跳板机: $jumpHost" -ForegroundColor Gray
Write-Host ""

# 所有容器名称列表
$allContainers = @(
    "tt_farm_10_7_136_129_0001", "tt_farm_10_7_136_129_0002", "tt_farm_10_7_136_129_0003",
    "tt_farm_10_7_136_129_0004", "tt_farm_10_7_136_129_0005", "tt_farm_10_7_136_129_0006",
    "tt_farm_10_7_136_129_0007", "tt_farm_10_7_136_129_0008", "tt_farm_10_7_136_129_0009",
    "tt_farm_10_7_136_129_0010"
)

# 定义执行函数
function Connect-AllDevices {
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "   ADB自动连接云手机脚本" -ForegroundColor Cyan
    Write-Host "   执行时间: $timestamp" -ForegroundColor Gray
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host ""

    # 检查ADB是否可用
    Write-Host "检查ADB工具..." -ForegroundColor Yellow
    try {
        $null = adb version 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0) {
            Write-Host "✓ ADB已安装" -ForegroundColor Green
        } else {
            Write-Host "✗ ADB未安装或未添加到PATH" -ForegroundColor Red
            Write-Host "请先安装Android SDK Platform Tools并添加到PATH环境变量" -ForegroundColor Yellow
            return
        }
    } catch {
        Write-Host "✗ ADB未安装或未添加到PATH" -ForegroundColor Red
        Write-Host "请先安装Android SDK Platform Tools并添加到PATH环境变量" -ForegroundColor Yellow
        return
    }
    Write-Host ""

    # 存储所有连接信息：容器名 -> (公网IP, 端口)
    $connectionMap = @{}

    # 遍历每台服务器
    foreach ($server in $servers.Keys) {
        $publicIP = $servers[$server]
        
        # 如果公网IP为空，跳过ADB连接，但仍尝试获取端口信息
        if ([string]::IsNullOrWhiteSpace($publicIP)) {
            Write-Host "正在连接服务器: $server (公网IP未配置，跳过ADB连接)..." -ForegroundColor Yellow
        } else {
            Write-Host "正在连接服务器: $server (公网: $publicIP)..." -ForegroundColor Yellow
        }
        
        # 构建SSH命令获取docker ps输出
        $dockerCmd = 'docker ps --format "{{.Names}}\t{{.Ports}}" | grep -E "tt_farm.*-proxy"'
        
        # 通过跳板机执行SSH命令
        $targetSshCmd = "ssh -o StrictHostKeyChecking=no $sshUsername@$server '$dockerCmd'"
        
        try {
            # 执行SSH命令并捕获输出
            $sshArgs = @(
                "-i", $sshKey,
                "-o", "StrictHostKeyChecking=no",
                "-o", "ConnectTimeout=10",
                $jumpHost,
                $targetSshCmd
            )
            
            # 使用Start-Process来更好地捕获输出和错误
            $processInfo = New-Object System.Diagnostics.ProcessStartInfo
            $processInfo.FileName = "ssh.exe"
            $processInfo.Arguments = ($sshArgs -join " ")
            $processInfo.RedirectStandardOutput = $true
            $processInfo.RedirectStandardError = $true
            $processInfo.UseShellExecute = $false
            $processInfo.CreateNoWindow = $true
            
            $process = New-Object System.Diagnostics.Process
            $process.StartInfo = $processInfo
            $process.Start() | Out-Null
            $output = $process.StandardOutput.ReadToEnd()
            $errorOutput = $process.StandardError.ReadToEnd()
            $process.WaitForExit()
            
            # 合并输出
            if ($errorOutput -and $errorOutput -notmatch "Warning: Permanently added") {
                $output = $output + "`n" + $errorOutput
            }
            
            # 检查是否有错误（但忽略SSH的警告信息）
            if ($process.ExitCode -ne 0 -and $output -notmatch "tt_farm") {
                Write-Host "  ⚠️  连接失败或命令执行失败 (退出码: $($process.ExitCode))" -ForegroundColor Red
                if ($output -and $output.Length -lt 500) {
                    Write-Host "  输出: $output" -ForegroundColor Gray
                }
                continue
            }
            
            if ([string]::IsNullOrWhiteSpace($output)) {
                Write-Host "  ⚠️  未找到proxy容器" -ForegroundColor Yellow
                continue
            }
            
            # 解析输出，每行格式：容器名    端口映射
            $lines = $output -split "`r?`n" | Where-Object { $_ -match "-proxy" }
            
            $foundCount = 0
            foreach ($line in $lines) {
                if ([string]::IsNullOrWhiteSpace($line)) { continue }
                
                # 解析容器名和端口
                if ($line -match "(tt_farm_\d+_\d+_\d+_\d+_\d+)-proxy.*?(0\.0\.0\.0:\d+->5555/tcp)") {
                    $containerName = $matches[1]
                    $portsInfo = $matches[2]
                    
                    # 从端口信息中提取映射端口
                    if ($portsInfo -match "0\.0\.0\.0:(\d+)->5555/tcp") {
                        $port = $matches[1]
                        
                        # 只有配置了公网IP的服务器才添加到连接映射
                        if (-not [string]::IsNullOrWhiteSpace($publicIP)) {
                            $connectionMap[$containerName] = @{
                                PublicIP = $publicIP
                                Port = $port
                            }
                            Write-Host "  ✓ 找到: $containerName -> $publicIP`:$port" -ForegroundColor Green
                        } else {
                            Write-Host "  ✓ 找到: $containerName -> 端口:$port (公网IP未配置)" -ForegroundColor Gray
                        }
                        $foundCount++
                    } else {
                        Write-Host "  ⚠️  无法解析端口: $containerName ($portsInfo)" -ForegroundColor Yellow
                    }
                }
            }
            
            if ($foundCount -eq 0) {
                Write-Host "  ⚠️  未找到任何proxy容器" -ForegroundColor Yellow
            }
            
        } catch {
            Write-Host "  ❌ 连接异常: $($_.Exception.Message)" -ForegroundColor Red
        }
        
        Write-Host ""
    }

    # 如果没有找到任何连接信息，直接返回
    if ($connectionMap.Count -eq 0) {
        Write-Host "未找到任何可连接的设备（可能公网IP未配置）" -ForegroundColor Yellow
        Write-Host ""
        return
    }

    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "   开始建立ADB连接" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host ""

    # 统计信息
    $successCount = 0
    $failCount = 0
    $skipCount = 0

    # 按容器名称排序后连接
    $sortedContainers = $allContainers | Sort-Object

    foreach ($containerName in $sortedContainers) {
        if ($connectionMap.ContainsKey($containerName)) {
            $info = $connectionMap[$containerName]
            $publicIP = $info.PublicIP
            $port = $info.Port
            $address = "$publicIP`:$port"
            
            Write-Host "连接: $containerName -> $address" -ForegroundColor Cyan -NoNewline
            
            # 先断开可能存在的旧连接
            $disconnectCmd = "adb disconnect $address"
            $null = Invoke-Expression $disconnectCmd 2>&1
            
            # 建立新连接
            $connectCmd = "adb connect $address"
            $result = Invoke-Expression $connectCmd 2>&1
            
            if ($LASTEXITCODE -eq 0 -and $result -match "connected") {
                Write-Host " ✓ 成功" -ForegroundColor Green
                $successCount++
            } else {
                Write-Host " ✗ 失败: $result" -ForegroundColor Red
                $failCount++
            }
        } else {
            Write-Host "跳过: $containerName (未找到端口信息)" -ForegroundColor Yellow
            $skipCount++
        }
    }

    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "   连接完成" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "成功: $successCount" -ForegroundColor Green
    Write-Host "失败: $failCount" -ForegroundColor Red
    Write-Host "跳过: $skipCount" -ForegroundColor Yellow
    Write-Host ""

    # 显示当前连接的设备
    Write-Host "当前ADB连接的设备:" -ForegroundColor Cyan
    $devices = adb devices
    Write-Host $devices -ForegroundColor White
    Write-Host ""

    Write-Host "本次执行完成！" -ForegroundColor Green
    Write-Host ""
}

# 检查ADB是否可用（只在开始时检查一次）
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   ADB自动连接云手机脚本（循环模式）" -ForegroundColor Cyan
Write-Host "   执行间隔: $intervalMinutes 分钟" -ForegroundColor Yellow
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

Write-Host "检查ADB工具..." -ForegroundColor Yellow
try {
    $null = adb version 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✓ ADB已安装" -ForegroundColor Green
    } else {
        Write-Host "✗ ADB未安装或未添加到PATH" -ForegroundColor Red
        Write-Host "请先安装Android SDK Platform Tools并添加到PATH环境变量" -ForegroundColor Yellow
        exit 1
    }
} catch {
    Write-Host "✗ ADB未安装或未添加到PATH" -ForegroundColor Red
    Write-Host "请先安装Android SDK Platform Tools并添加到PATH环境变量" -ForegroundColor Yellow
    exit 1
}
Write-Host ""

# 循环执行
$runCount = 0
try {
    while ($true) {
        $runCount++
        Write-Host "========================================" -ForegroundColor Magenta
        Write-Host "   第 $runCount 次执行" -ForegroundColor Magenta
        Write-Host "========================================" -ForegroundColor Magenta
        
        # 执行连接
        Connect-AllDevices
        
        # 如果不是最后一次执行，等待指定时间
        Write-Host "等待 $intervalMinutes 分钟后执行下一次..." -ForegroundColor Yellow
        Write-Host "按 Ctrl+C 可以停止脚本" -ForegroundColor Gray
        Write-Host ""
        
        # 等待指定分钟数（转换为秒）
        Start-Sleep -Seconds ($intervalMinutes * 60)
    }
} catch {
    Write-Host ""
    Write-Host "脚本已停止" -ForegroundColor Yellow
    Write-Host "总共执行了 $runCount 次" -ForegroundColor Gray
}

