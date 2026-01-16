# 部署脚本使用说明

## 快速部署

### Windows PowerShell

直接运行完整部署脚本（包含编译、传输、停止、启动）：

```powershell
.\deploy-full.ps1
```

或者使用原来的脚本（需要手动启动）：

```powershell
.\deploy.ps1
```

## 脚本说明

### deploy-full.ps1（推荐）

**功能：**
- ✅ 自动编译项目
- ✅ 传输jar文件到跳板机
- ✅ 从跳板机传输到目标服务器
- ✅ 停止旧进程
- ✅ 复制jar文件到应用目录
- ✅ 启动新应用
- ✅ 检查应用状态

**使用方法：**
```powershell
.\deploy-full.ps1
```

### deploy.ps1

**功能：**
- ✅ 自动编译项目
- ✅ 传输jar文件
- ✅ 停止旧进程
- ✅ 复制jar文件
- ❌ 需要手动启动应用

**使用方法：**
```powershell
.\deploy.ps1
# 然后手动执行启动命令
```

## 手动部署步骤

如果脚本执行失败，可以手动执行以下步骤：

### 1. 编译项目
```powershell
mvn clean package -DskipTests
```

### 2. 传输到跳板机
```powershell
scp -i $env:USERPROFILE\.ssh\id_rsa target\cpa-manage-api-0.0.1-SNAPSHOT.jar ubuntu@106.75.152.136:/tmp/
```

### 3. 传输到目标服务器
```powershell
ssh -i $env:USERPROFILE\.ssh\id_rsa ubuntu@106.75.152.136 "scp /tmp/cpa-manage-api-0.0.1-SNAPSHOT.jar ubuntu@10.13.135.74:/tmp/"
```

### 4. 停止旧进程
```powershell
ssh -i $env:USERPROFILE\.ssh\id_rsa ubuntu@106.75.152.136 "ssh ubuntu@10.13.135.74 'sudo pkill -f cpa-manage-api'"
```

### 5. 复制文件
```powershell
ssh -i $env:USERPROFILE\.ssh\id_rsa ubuntu@106.75.152.136 "ssh ubuntu@10.13.135.74 'cp /tmp/cpa-manage-api-0.0.1-SNAPSHOT.jar /home/ubuntu/cpa-manage-api/'"
```

### 6. 启动应用
```powershell
ssh -i $env:USERPROFILE\.ssh\id_rsa ubuntu@106.75.152.136 "ssh ubuntu@10.13.135.74 'cd /home/ubuntu/cpa-manage-api && nohup java -jar cpa-manage-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod > app.log 2>&1 &'"
```

## 常用命令

### 查看日志
```powershell
ssh -i $env:USERPROFILE\.ssh\id_rsa ubuntu@106.75.152.136 "ssh ubuntu@10.13.135.74 'tail -f /home/ubuntu/cpa-manage-api/logs/cpa-manage-api.log'"
```

### 查看进程
```powershell
ssh -i $env:USERPROFILE\.ssh\id_rsa ubuntu@106.75.152.136 "ssh ubuntu@10.13.135.74 'ps aux | grep cpa-manage-api'"
```

### 停止应用
```powershell
ssh -i $env:USERPROFILE\.ssh\id_rsa ubuntu@106.75.152.136 "ssh ubuntu@10.13.135.74 'sudo pkill -f cpa-manage-api'"
```

### 查看端口占用
```powershell
ssh -i $env:USERPROFILE\.ssh\id_rsa ubuntu@106.75.152.136 "ssh ubuntu@10.13.135.74 'netstat -tlnp | grep 8081'"
```

## 配置说明

脚本中使用的配置：
- **跳板机**: ubuntu@106.75.152.136
- **目标服务器**: ubuntu@10.13.135.74
- **应用目录**: /home/ubuntu/cpa-manage-api
- **SSH私钥**: $env:USERPROFILE\.ssh\id_rsa

如需修改配置，请编辑脚本文件中的变量。

