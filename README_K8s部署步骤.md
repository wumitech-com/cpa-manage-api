# K8s 部署步骤

## 前置条件

1. ✅ Docker 镜像已构建并推送到仓库
2. ✅ K8s 配置文件已准备好
3. ✅ 可以访问香港跳板机（能连接到 K8s 集群）

## 方式1：手动部署（推荐）

### 步骤1：上传文件到香港跳板机

在本地执行：

```powershell
# 通过第一层跳板机上传到香港跳板机
# 假设香港跳板机IP为 HK_JUMP_IP（需要替换）

# 方式A：上传合并文件（推荐，只需上传一个文件）
scp -i $env:USERPROFILE\.ssh\id_rsa k8s\all.yaml ubuntu@106.75.152.136:/tmp/
ssh -i $env:USERPROFILE\.ssh\id_rsa ubuntu@106.75.152.136 "scp /tmp/all.yaml ubuntu@HK_JUMP_IP:~/cpa-k8s/"

# 方式B：上传所有文件（如果需要分别管理）
# scp -i $env:USERPROFILE\.ssh\id_rsa k8s\namespace.yaml ubuntu@106.75.152.136:/tmp/k8s/
# scp -i $env:USERPROFILE\.ssh\id_rsa k8s\configmap.yaml ubuntu@106.75.152.136:/tmp/k8s/
# scp -i $env:USERPROFILE\.ssh\id_rsa k8s\deployment.yaml ubuntu@106.75.152.136:/tmp/k8s/
# scp -i $env:USERPROFILE\.ssh\id_rsa k8s\service.yaml ubuntu@106.75.152.136:/tmp/k8s/
# scp -i $env:USERPROFILE\.ssh\id_rsa k8s\ingress.yaml ubuntu@106.75.152.136:/tmp/k8s/
# ssh -i $env:USERPROFILE\.ssh\id_rsa ubuntu@106.75.152.136 "scp -r /tmp/k8s ubuntu@HK_JUMP_IP:~/"
```

### 步骤2：在香港跳板机上执行部署

SSH 到香港跳板机后执行：

```bash
# 1. 使用现有的kubeconfig（context: kubernetes）
kubectl config use-context kubernetes

# 2. 验证连接
kubectl cluster-info

# 3. 部署应用
kubectl apply -f ~/k8s/namespace.yaml
kubectl apply -f ~/k8s/configmap.yaml
kubectl apply -f ~/k8s/deployment.yaml
kubectl apply -f ~/k8s/service.yaml
kubectl apply -f ~/k8s/ingress.yaml

# 4. 检查部署状态
kubectl get pods -n cpa-manage-api
kubectl get svc -n cpa-manage-api
kubectl get ingress -n cpa-manage-api

# 5. 查看日志
kubectl logs -f deployment/cpa-manage-api -n cpa-manage-api
```

## 方式2：使用脚本部署

### 配置跳板机信息

编辑 `deploy-k8s-via-jump.ps1`，设置香港跳板机IP：

```powershell
$secondJumpHost = "香港跳板机IP"  # 替换为实际IP
```

### 执行部署脚本

```powershell
.\deploy-k8s-via-jump.ps1
```

## 验证部署

### 检查 Pod 状态

```bash
kubectl get pods -n cpa-manage-api
```

期望看到 Pod 状态为 `Running`。

### 检查服务

```bash
kubectl get svc -n cpa-manage-api
kubectl get ingress -n cpa-manage-api
```

### 访问应用

- 通过 Ingress: `https://cpa-manage-api.wumitech.com`
- 通过 Port Forward（测试用）:
  ```bash
  kubectl port-forward svc/cpa-manage-api 8081:8081 -n cpa-manage-api
  ```
  然后访问: `http://localhost:8081`

## 常用命令

```bash
# 查看 Pod 日志
kubectl logs -f deployment/cpa-manage-api -n cpa-manage-api

# 查看 Pod 详细信息
kubectl describe pod <pod-name> -n cpa-manage-api

# 重启 Deployment
kubectl rollout restart deployment/cpa-manage-api -n cpa-manage-api

# 查看 Deployment 状态
kubectl rollout status deployment/cpa-manage-api -n cpa-manage-api

# 删除部署（如果需要）
kubectl delete namespace cpa-manage-api
```

## 故障排查

### Pod 无法启动

```bash
# 查看 Pod 事件
kubectl describe pod <pod-name> -n cpa-manage-api

# 查看 Pod 日志
kubectl logs <pod-name> -n cpa-manage-api
```

### 镜像拉取失败

检查：
1. 镜像是否已推送到仓库
2. K8s 节点是否能访问镜像仓库
3. 镜像仓库认证是否正确

### Ingress 无法访问

检查：
1. Ingress Controller 是否运行
2. 域名 DNS 是否解析到 Ingress Controller
3. Ingress 规则是否正确

