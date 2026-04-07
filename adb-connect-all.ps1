param(
    [Parameter(Mandatory = $true)]
    [string]$ServerIp,
    [int]$Limit = 10,
    [switch]$UseTunnel,
    [int]$LocalPortStart = 3334,
    [string]$PublicIp = "",
    [string]$SshUser = "ubuntu",
    [string]$JumpHost = "106.75.152.136",
    [string]$JumpUser = "ubuntu",
    [string]$SshKey = "$env:USERPROFILE\.ssh\id_rsa"
)

$ErrorActionPreference = "Stop"

if (Test-Path "ssh_key") {
    $SshKey = (Resolve-Path "ssh_key").Path
}

function Test-CommandExists {
    param([string]$CommandName)
    $cmd = Get-Command $CommandName -ErrorAction SilentlyContinue
    return $null -ne $cmd
}

function Invoke-SshViaJump {
    param([string]$RemoteCommand)
    $targetSsh = "ssh -o StrictHostKeyChecking=no $SshUser@$ServerIp '$RemoteCommand'"
    $args = @(
        "-i", $SshKey,
        "-o", "StrictHostKeyChecking=no",
        "-o", "ConnectTimeout=10",
        "$JumpUser@$JumpHost",
        $targetSsh
    )

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "ssh.exe"
    $psi.Arguments = ($args -join " ")
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $psi
    $p.Start() | Out-Null
    $stdout = $p.StandardOutput.ReadToEnd()
    $stderr = $p.StandardError.ReadToEnd()
    $p.WaitForExit()

    return @{
        ExitCode = $p.ExitCode
        Stdout = $stdout
        Stderr = $stderr
    }
}

function Start-Tunnel {
    param(
        [int]$LocalPort,
        [int]$RemotePort
    )
    $forward = "$LocalPort`:$ServerIp`:$RemotePort"
    $args = @(
        "-i", $SshKey,
        "-o", "StrictHostKeyChecking=no",
        "-o", "ExitOnForwardFailure=yes",
        "-N",
        "-L", $forward,
        "$JumpUser@$JumpHost"
    )
    Start-Process -FilePath "ssh.exe" -ArgumentList $args -WindowStyle Hidden | Out-Null
}

function Stop-ExistingTunnels {
    $tunnelProcesses = Get-CimInstance Win32_Process | Where-Object {
        $_.Name -eq "ssh.exe" -and
        $_.CommandLine -match "-N" -and
        $_.CommandLine -match "-L"
    }

    if (-not $tunnelProcesses) {
        Write-Host "No existing SSH tunnel process found." -ForegroundColor Gray
        return
    }

    Write-Host "Stopping $($tunnelProcesses.Count) existing SSH tunnel process(es)..." -ForegroundColor Yellow
    foreach ($proc in $tunnelProcesses) {
        try {
            Stop-Process -Id $proc.ProcessId -Force -ErrorAction Stop
        } catch {
            Write-Host "  WARN: failed to stop PID $($proc.ProcessId): $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
}

function Wait-LocalPortReady {
    param(
        [int]$Port,
        [int]$TimeoutMs = 10000
    )
    $deadline = (Get-Date).AddMilliseconds($TimeoutMs)
    while ((Get-Date) -lt $deadline) {
        try {
            $client = New-Object System.Net.Sockets.TcpClient
            $ar = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
            if ($ar.AsyncWaitHandle.WaitOne(300) -and $client.Connected) {
                $client.EndConnect($ar) | Out-Null
                $client.Close()
                return $true
            }
            $client.Close()
        } catch {
            # tunnel not ready yet, keep waiting
        }
        Start-Sleep -Milliseconds 200
    }
    return $false
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   ADB Param Connection Script" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "ServerIp       : $ServerIp" -ForegroundColor Gray
Write-Host "Limit          : $Limit" -ForegroundColor Gray
Write-Host "UseTunnel      : $UseTunnel" -ForegroundColor Gray
Write-Host "JumpHost       : $JumpUser@$JumpHost" -ForegroundColor Gray
Write-Host "SSH Key        : $SshKey" -ForegroundColor Gray
Write-Host ""

if ($UseTunnel) {
    Stop-ExistingTunnels
}

if (-not (Test-Path $SshKey)) {
    throw "SSH key not found: $SshKey"
}
if (-not (Test-CommandExists "adb")) {
    throw "ADB is not available. Please install and add to PATH."
}
if (-not (Test-CommandExists "ssh")) {
    throw "ssh is not available. Please ensure OpenSSH is installed."
}
if ($Limit -le 0) {
    throw "Limit must be greater than 0."
}

$dockerCmd = 'docker ps --format "{{.Names}}\t{{.Ports}}" | grep -E "tt_farm.*-proxy"'
$sshResult = Invoke-SshViaJump -RemoteCommand $dockerCmd

$rawOutput = $sshResult.Stdout
if ($sshResult.Stderr -and $sshResult.Stderr -notmatch "Permanently added") {
    $rawOutput += "`n$($sshResult.Stderr)"
}

if ($sshResult.ExitCode -ne 0 -and [string]::IsNullOrWhiteSpace($sshResult.Stdout)) {
    throw "SSH command failed. ExitCode: $($sshResult.ExitCode)`n$rawOutput"
}

$entries = @()
($rawOutput -split "`r?`n") | ForEach-Object {
    $line = $_.Trim()
    if (-not $line) { return }
    if ($line -match "(tt_farm_\d+_\d+_\d+_\d+_\d+)-proxy.*0\.0\.0\.0:(\d+)->5555/tcp") {
        $entries += [PSCustomObject]@{
            Container = $matches[1]
            RemotePort = [int]$matches[2]
        }
    }
}

if ($entries.Count -eq 0) {
    throw "No proxy ports found on $ServerIp."
}

$targets = $entries | Sort-Object Container | Select-Object -First $Limit
Write-Host "Found $($entries.Count) devices, connect first $($targets.Count)..." -ForegroundColor Yellow

# Only clean ADB connections once before batch connect.
Write-Host "Disconnecting all existing ADB connections (once)..." -ForegroundColor Yellow
adb disconnect | Out-Null

$success = 0
$fail = 0
$idx = 0

foreach ($t in $targets) {
    $idx++
    if ($UseTunnel) {
        $localPort = $LocalPortStart + $idx - 1
        Start-Tunnel -LocalPort $localPort -RemotePort $t.RemotePort
        $address = "127.0.0.1:$localPort"
        Write-Host "[$idx/$($targets.Count)] $($t.Container) => tunnel 127.0.0.1:$localPort -> ${ServerIp}:$($t.RemotePort)" -ForegroundColor Cyan
        if (-not (Wait-LocalPortReady -Port $localPort -TimeoutMs 10000)) {
            Write-Host "  FAIL: tunnel not ready on 127.0.0.1:$localPort within 10s" -ForegroundColor Red
            $fail++
            continue
        }
    } else {
        $targetHost = $PublicIp
        if ([string]::IsNullOrWhiteSpace($targetHost)) {
            $targetHost = $ServerIp
        }
        $address = "${targetHost}:$($t.RemotePort)"
        Write-Host "[$idx/$($targets.Count)] $($t.Container) => $address" -ForegroundColor Cyan
    }

    $result = adb connect $address 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0 -and $result -match "connected|already connected") {
        Write-Host "  OK: $address" -ForegroundColor Green
        $success++
    } else {
        Write-Host "  FAIL: $address -> $($result.Trim())" -ForegroundColor Red
        $fail++
    }
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Done: success $success / fail $fail / total $($targets.Count)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
adb devices

