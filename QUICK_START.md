# 快速开始指南

## 1. 启动应用

```bash
# 开发环境启动
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"

# 或者打包后运行
mvn clean package -DskipTests
java -jar target/cpa-manage-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

应用将在 `http://localhost:8080` 启动

## 2. 核心API使用

### 创建云手机（支持多台服务器）

```bash
curl -X POST http://localhost:8080/api/scripts/create-phone \
  -H "Content-Type: application/json" \
  -d '{
    "phonePrefix": "tt_107_224",
    "serverHost": "10.7.107.224",
    "country": "US",
    "count": 10,
    "pkgName": "com.zhiliaoapp.musically",
    "scriptPath": "./batch_create_phone.sh"
  }'
```

**参数说明**:
- `phonePrefix`: 云手机名称前缀（如：tt_107_224）
- `serverHost`: 目标服务器IP（可选，默认：10.7.107.224）
- `country`: 国家代码（如：US、GB）
- `count`: 创建数量
- `pkgName`: TikTok包名
- `scriptPath`: 脚本路径

**返回示例**:
```json
{
  "success": true,
  "message": "脚本已提交后台执行，进程ID: 12345",
  "pid": "12345",
  "logFile": "/tmp/batch_create_log_tt_107_224_20251014",
  "async": true
}
```

### 查询异步任务状态

```bash
curl "http://localhost:8080/api/scripts/async-status?pid=12345&logFile=/tmp/batch_create_log_tt_107_224_20251014"
```

**返回示例**:
```json
{
  "success": true,
  "isRunning": false,
  "lastLog": "SUCCESS: tt_107_224_10_US_20251014...",
  "phoneNames": ["tt_107_224_1_US_20251014", "tt_107_224_2_US_20251014", ...]
}
```

### 读取日志并解析（支持多台服务器，自动插入数据库）

```bash
# 使用默认服务器
curl "http://localhost:8080/api/scripts/test-parse-log?logFile=/tmp/batch_create_log_tt_107_224_20251011&pkgName=com.zhiliaoapp.musically&lines=0"

# 指定目标服务器
curl "http://localhost:8080/api/scripts/test-parse-log?targetHost=10.7.107.225&logFile=/tmp/batch_create_log_tt_107_225_20251011&pkgName=com.zhiliaoapp.musically&lines=0"
```

**参数说明**:
- `targetHost`: 目标服务器IP（可选，不传则使用配置文件默认值）
- `logFile`: 日志文件路径（必传）
- `pkgName`: TikTok包名（必传，如：com.zhiliaoapp.musically）
- `lines`: 读取行数（可选，0=全部，10=最后10行）

**功能**:
- 自动读取远程日志
- 解析成功的云手机名称
- 自动提取国家代码
- 检查重复并插入数据库
- 返回插入数量和统计信息

### 设备池管理

```bash
# 获取设备池列表
curl "http://localhost:8080/api/devices/pool?page=0&size=20"

# 获取账号库列表
curl "http://localhost:8080/api/devices/library?page=0&size=20"
```

### 统计数据

```bash
# 系统概览
curl "http://localhost:8080/api/statistics/overview"

# 设备池统计
curl "http://localhost:8080/api/statistics/device-pool"

# 账号库统计
curl "http://localhost:8080/api/statistics/account-library"
```

## 3. 多服务器配置

### 方式1: 通过API参数指定（推荐）

创建云手机时直接传入`serverHost`参数：

```json
{
  "serverHost": "10.7.107.224",  // 服务器1
  "phonePrefix": "tt_107_224",
  ...
}

{
  "serverHost": "10.7.107.225",  // 服务器2
  "phonePrefix": "tt_107_225",
  ...
}
```

### 方式2: 修改配置文件默认值

编辑 `src/main/resources/application.yml`:

```yaml
agent:
  ssh-target-host: 10.7.107.XXX  # 修改默认服务器
```

## 4. SSH配置说明

### 当前配置
```yaml
agent:
  # 跳板机
  ssh-jump-host: 106.75.152.136
  ssh-jump-port: 22
  ssh-jump-username: ubuntu
  
  # 默认目标服务器
  ssh-target-host: 10.7.107.224
  ssh-target-port: 22
  ssh-username: ubuntu
  
  # 私钥（在dev profile中配置）
  ssh-private-key: |-
    -----BEGIN RSA PRIVATE KEY-----
    ...
    -----END RSA PRIVATE KEY-----
```

### 连接流程
```
Java应用 
  ↓ 私钥认证
跳板机 (106.75.152.136:22)
  ↓ 执行: ssh ubuntu@目标服务器 '命令'
目标服务器 (10.7.107.224:22 或 API参数指定)
```

## 5. 常见操作

### 创建云手机完整流程

```bash
# 1. 提交创建任务
curl -X POST http://localhost:8080/api/scripts/create-phone \
  -H "Content-Type: application/json" \
  -d '{"phonePrefix":"tt_107_224","serverHost":"10.7.107.224","country":"US","count":5}'

# 返回: {"pid":"12345","logFile":"/tmp/batch_create_log_tt_107_224_20251014"}

# 2. 等待一段时间后查询状态
curl "http://localhost:8080/api/scripts/async-status?pid=12345&logFile=/tmp/batch_create_log_tt_107_224_20251014"

# 3. 任务完成后，云手机会自动插入设备池
curl "http://localhost:8080/api/devices/pool"
```

### 查看不同服务器的日志并插入数据库

```bash
# 服务器1 - 10月13日日志
curl "http://localhost:8080/api/scripts/test-parse-log?targetHost=10.7.107.224&logFile=/tmp/batch_create_log_tt_107_224_20251013&pkgName=com.zhiliaoapp.musically"

# 服务器2 - 10月11日日志
curl "http://localhost:8080/api/scripts/test-parse-log?targetHost=10.7.107.225&logFile=/tmp/batch_create_log_tt_107_225_20251011&pkgName=com.zhiliaoapp.musically"
```

**返回示例**:
```json
{
  "success": true,
  "parsedCount": 68,
  "insertedCount": 68,
  "statistics": {
    "totalLines": 102,
    "successLines": 68,
    "failedLines": 32,
    "parsedPhones": 68,
    "insertedToDb": 68
  },
  "phoneNames": ["tt_107_224_1_US_20251013", ...]
}
```

## 6. 测试连接

### 测试SSH配置
```bash
curl "http://localhost:8080/api/scripts/test-ssh-config"
```

预期输出：
```json
{
  "success": true,
  "sshConfig": {
    "privateKeyLength": 3242,
    "hasPrivateKey": true,
    "jumpHost": "106.75.152.136",
    "targetHost": "10.7.107.224"
  }
}
```

## 7. 注意事项

1. **多服务器支持**: 
   - 所有目标服务器都通过同一个跳板机访问
   - 每个API调用可以指定不同的`serverHost`或`targetHost`
   - 不传则使用配置文件中的默认值

2. **日志文件路径**: 
   - 每个服务器的日志路径格式：`/tmp/batch_create_log_<前缀>_<日期>`
   - 不同服务器的日志要使用不同的前缀

3. **异步任务监控**: 
   - 创建云手机脚本会在后台运行2-3小时
   - 系统自动监控进程状态
   - 完成后自动解析日志并插入数据库

4. **跳板机**: 
   - 所有SSH连接都通过跳板机 `106.75.152.136`
   - 使用私钥认证，无需密码
   - 采用"两段式SSH连接"方式

## 8. Web界面

访问 `http://localhost:8080` 可以使用Web管理界面（如果已配置）。

---

更多详细信息请查看 `PROJECT_SUMMARY.md`

