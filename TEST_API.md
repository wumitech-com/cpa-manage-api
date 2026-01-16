# 测试API调用指南

## 测试读取日志并解析云手机名称

### API接口

```
GET /api/scripts/test-parse-log
```

### 参数

| 参数 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| host | String | 是 | 服务器地址 | 10.7.107.224 |
| logFile | String | 是 | 日志文件路径 | /tmp/batch_create_log_tt_107_224_20251011 |
| lines | Integer | 否 | 读取行数，0=全部 | 0 |

### 测试命令

#### 方式1: 使用curl（PowerShell）

```powershell
# 读取全部日志
Invoke-WebRequest -Uri "http://localhost:8080/api/scripts/test-parse-log?host=10.7.107.224&logFile=/tmp/batch_create_log_tt_107_224_20251011&lines=0" -UseBasicParsing | Select-Object -ExpandProperty Content | ConvertFrom-Json | ConvertTo-Json -Depth 5

# 只读取最后50行
Invoke-WebRequest -Uri "http://localhost:8080/api/scripts/test-parse-log?host=10.7.107.224&logFile=/tmp/batch_create_log_tt_107_224_20251011&lines=50" -UseBasicParsing | Select-Object -ExpandProperty Content | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

#### 方式2: 使用浏览器

直接在浏览器中访问：
```
http://localhost:8080/api/scripts/test-parse-log?host=10.7.107.224&logFile=/tmp/batch_create_log_tt_107_224_20251011&lines=0
```

#### 方式3: 使用Postman或其他API工具

```
GET http://localhost:8080/api/scripts/test-parse-log
Query Parameters:
  - host: 10.7.107.224
  - logFile: /tmp/batch_create_log_tt_107_224_20251011
  - lines: 0
```

### 预期响应

```json
{
  "success": true,
  "logContent": "开始批量创建云手机...\nSUCCESS: tt_107_224_2_US_20251011...\n...",
  "phoneNames": [
    "tt_107_224_2_US_20251011",
    "tt_107_224_3_US_20251011",
    "tt_107_224_4_US_20251011",
    "tt_107_224_5_US_20251011",
    "tt_107_224_6_US_20251011",
    "tt_107_224_7_US_20251011",
    "tt_107_224_9_US_20251011",
    "tt_107_224_10_US_20251011",
    "tt_107_224_12_US_20251011",
    "tt_107_224_13_US_20251011",
    "tt_107_224_14_US_20251011",
    "tt_107_224_15_US_20251011",
    "tt_107_224_16_US_20251011",
    "tt_107_224_17_US_20251011",
    "tt_107_224_18_US_20251011",
    "tt_107_224_20_US_20251011",
    "tt_107_224_21_US_20251011"
  ],
  "parsedCount": 17,
  "statistics": {
    "totalLines": 123,
    "successLines": 17,
    "failedLines": 3,
    "parsedPhones": 17
  }
}
```

### 解析规则说明

系统会：
1. 读取日志文件内容
2. 逐行扫描
3. 只提取 `SUCCESS:` 开头的行
4. 使用正则表达式提取云手机名称: `SUCCESS:\s+([^\s]+_\d+_[A-Z]{2}_\d{8})`
5. 跳过 `FAILED:` 行
6. 返回成功创建的云手机名称列表

### 示例日志解析

**输入（日志文件内容）**:
```
开始批量创建云手机 - Sat 11 Oct 2025 04:33:15 PM HKT
参数: 名称头=tt_107_224, 国家=US, 数量=100
FAILED: tt_107_224_1_US_20251011 (序号: 1, 创建失败) - Sat 11 Oct 2025 04:35:11 PM HKT
SUCCESS: tt_107_224_2_US_20251011 (序号: 2) - Sat 11 Oct 2025 04:37:22 PM HKT
SUCCESS: tt_107_224_3_US_20251011 (序号: 3) - Sat 11 Oct 2025 04:39:51 PM HKT
```

**输出（解析结果）**:
```json
{
  "phoneNames": [
    "tt_107_224_2_US_20251011",
    "tt_107_224_3_US_20251011"
  ],
  "parsedCount": 2
}
```

## 其他可用日志文件

根据您的服务器 `/tmp` 目录，可以测试以下日志文件：

```
/tmp/batch_create_log_tt_107_224_20250918
/tmp/batch_create_log_tt_107_224_20250919
/tmp/batch_create_log_tt_107_224_20250920
/tmp/batch_create_log_tt_107_224_20250922
/tmp/batch_create_log_tt_107_224_20250924
/tmp/batch_create_log_tt_107_224_20250928
/tmp/batch_create_log_tt_107_224_20250930
/tmp/batch_create_log_tt_107_224_20251009
/tmp/batch_create_log_tt_107_224_20251010
/tmp/batch_create_log_tt_107_224_20251011
```

## 快速测试命令

```powershell
# 测试读取20251011的日志
$response = Invoke-WebRequest -Uri "http://localhost:8080/api/scripts/test-parse-log?host=10.7.107.224&logFile=/tmp/batch_create_log_tt_107_224_20251011&lines=0" -UseBasicParsing
$json = $response.Content | ConvertFrom-Json

# 查看解析到的云手机数量
Write-Host "解析到的云手机数量: $($json.parsedCount)"
Write-Host "成功行数: $($json.statistics.successLines)"
Write-Host "失败行数: $($json.statistics.failedLines)"

# 显示前5个云手机名称
$json.phoneNames | Select-Object -First 5
```

## 注意事项

1. **确保应用已启动**: 应用需要运行在 http://localhost:8080
2. **确保使用dev profile**: `mvn spring-boot:run -Dspring-boot.run.profiles=dev`
3. **确保SSH私钥已配置**: 在 `application-ssh.yml` 中
4. **服务器可访问**: 确保能SSH连接到 10.7.107.224
