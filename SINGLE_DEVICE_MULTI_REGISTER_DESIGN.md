# 单设备多次注册简单版本设计方案

## 一、需求分析

### 1.1 需求
- **输入**: 一台云手机（phoneId + serverIp）
- **目标**: 注册50个账号
- **流程**: 循环执行，每次都是完整的注册流程（reset + 换机 + 注册）

### 1.2 当前功能问题
- ❌ 当前 `batchRegisterTtAccounts` 是按phoneIds列表，每个phoneId只注册一次
- ❌ 不支持同一个phoneId重复注册多次

## 二、简单版本设计方案

### 2.1 核心设计

```
输入: phoneId + serverIp + targetCount(50) + resetParams
    ↓
创建任务（返回taskId）
    ↓
异步执行循环注册
    ↓
循环50次 {
    启动手机（如果未启动）
      ↓
    ResetPhoneEnv（reset + 换机 + 代理切换）
      ↓
    安装TikTok APK
      ↓
    执行注册脚本
      ↓
    记录结果（成功/失败）
      ↓
    继续下一轮
}
    ↓
任务完成，返回统计结果
```

### 2.2 API设计

#### 2.2.1 新增接口

```java
POST /api/tt-register/repeat
```

**请求参数**:
```json
{
  "phoneId": "tt_107_224_1",
  "serverIp": "10.7.107.224",
  "targetCount": 50,
  "resetParams": {
    "country": "BR",
    "sdk": "33",
    "imagePath": "uhub.service.ucloud.cn/phone/android13_cpu:20251120",
    "gaidTag": "20250410",
    "dynamicIpChannel": "closeli",
    "staticIpChannel": "",
    "biz": ""
  }
}
```

**响应**:
```json
{
  "success": true,
  "taskId": "TT_REPEAT_REGISTER_1234567890",
  "message": "单设备重复注册任务已启动"
}
```

#### 2.2.2 查询进度接口（复用现有的）

```
GET /api/tt-register/status/{taskId}
```

**响应**:
```json
{
  "success": true,
  "taskId": "TT_REPEAT_REGISTER_1234567890",
  "status": "RUNNING",
  "totalCount": 50,
  "completedCount": 12,
  "successCount": 10,
  "failedCount": 2,
  "progress": 24.0,
  "startTime": "2024-01-01T10:00:00",
  "deviceResults": [
    {
      "round": 1,
      "status": "SUCCESS",
      "timestamp": "2024-01-01T10:05:00"
    },
    {
      "round": 2,
      "status": "FAILED",
      "errorMessage": "注册脚本执行失败",
      "timestamp": "2024-01-01T10:15:00"
    }
  ]
}
```

### 2.3 实现方案

#### 2.3.1 Controller层

```java
@PostMapping("/repeat")
public Map<String, Object> repeatRegisterOnDevice(@RequestBody Map<String, Object> request) {
    // 参数验证
    String phoneId = (String) request.get("phoneId");
    String serverIp = (String) request.get("serverIp");
    Integer targetCount = (Integer) request.get("targetCount");
    @SuppressWarnings("unchecked")
    Map<String, String> resetParams = (Map<String, String>) request.get("resetParams");
    
    // 调用Service
    return ttRegisterService.repeatRegisterOnDevice(phoneId, serverIp, targetCount, resetParams);
}
```

#### 2.3.2 Service层核心逻辑

```java
public Map<String, Object> repeatRegisterOnDevice(
    String phoneId, 
    String serverIp, 
    int targetCount, 
    Map<String, String> resetParams
) {
    // 1. 创建任务
    String taskId = "TT_REPEAT_" + System.currentTimeMillis();
    TaskInfo taskInfo = new TaskInfo();
    taskInfo.setTaskId(taskId);
    taskInfo.setPhoneIds(List.of(phoneId));
    taskInfo.setServerIp(serverIp);
    taskInfo.setStatus("RUNNING");
    taskInfo.setStartTime(LocalDateTime.now());
    taskInfoMap.put(taskId, taskInfo);
    
    // 2. 异步执行
    executeRepeatRegisterAsync(phoneId, serverIp, targetCount, resetParams, taskId);
    
    // 3. 返回任务ID
    return Map.of(
        "success", true,
        "taskId", taskId,
        "message", "单设备重复注册任务已启动"
    );
}

@Async
private void executeRepeatRegisterAsync(
    String phoneId, 
    String serverIp, 
    int targetCount, 
    Map<String, String> resetParams, 
    String taskId
) {
    TaskInfo taskInfo = taskInfoMap.get(taskId);
    int successCount = 0;
    int failCount = 0;
    List<Map<String, Object>> roundResults = new ArrayList<>();
    
    try {
        // 循环执行targetCount次
        for (int round = 1; round <= targetCount; round++) {
            log.info("=== 第 {}/{} 轮注册开始 ===", round, targetCount);
            
            Map<String, Object> roundResult = new HashMap<>();
            roundResult.put("round", round);
            roundResult.put("timestamp", LocalDateTime.now());
            
            try {
                // 执行单次注册（复用现有的registerSingleDevice逻辑）
                String status = registerSingleDevice(
                    phoneId, 
                    serverIp, 
                    round,           // currentIndex
                    targetCount,     // totalCount
                    resetParams
                );
                
                roundResult.put("status", status);
                roundResult.put("success", "SUCCESS".equals(status));
                
                if ("SUCCESS".equals(status)) {
                    successCount++;
                    log.info("第 {}/{} 轮注册成功", round, targetCount);
                } else {
                    failCount++;
                    roundResult.put("errorMessage", status);
                    log.warn("第 {}/{} 轮注册失败: {}", round, targetCount, status);
                }
                
            } catch (Exception e) {
                failCount++;
                roundResult.put("status", "ERROR");
                roundResult.put("success", false);
                roundResult.put("errorMessage", e.getMessage());
                log.error("第 {}/{} 轮注册异常", round, targetCount, e);
            }
            
            roundResults.add(roundResult);
            
            // 更新任务信息
            taskInfo.setSuccessCount(successCount);
            taskInfo.setFailCount(failCount);
            taskInfo.setDeviceResults(roundResults); // 需要扩展TaskInfo支持roundResults
            
            // 如果达到目标成功数量，可以选择提前结束（可选）
            // if (successCount >= targetCount) break;
        }
        
        // 任务完成
        taskInfo.setStatus("COMPLETED");
        taskInfo.setEndTime(LocalDateTime.now());
        log.info("=== 单设备重复注册任务完成: {} === 成功: {}, 失败: {}", taskId, successCount, failCount);
        
    } catch (Exception e) {
        taskInfo.setStatus("FAILED");
        taskInfo.setEndTime(LocalDateTime.now());
        log.error("单设备重复注册任务异常: {}", taskId, e);
    }
}
```

### 2.4 TaskInfo扩展

需要在现有的TaskInfo中添加字段支持轮次结果：

```java
public static class TaskInfo {
    // ... 现有字段 ...
    private List<Map<String, Object>> deviceResults;  // 改为支持轮次结果
    private int totalCount;                           // 总目标数量
    // ... getters and setters ...
}
```

### 2.5 前端界面设计

#### 2.5.1 简单版本界面

```
┌─────────────────────────────────────────────────────────┐
│  单设备多次注册                                           │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  云手机ID: [________________]                            │
│  服务器IP: [________________]                            │
│  目标账号数: [50]                                        │
│                                                          │
│  ResetPhoneEnv参数配置:                                  │
│  国家代码: [BR]                                          │
│  SDK版本: [33]                                           │
│  镜像路径: [uhub.service...]                             │
│  GAID标签: [20250410]                                    │
│  动态IP渠道: [closeli ▼]                                 │
│  静态IP渠道: [不使用 ▼]                                   │
│  业务标识: [________]                                    │
│                                                          │
│  [开始注册]                                              │
│                                                          │
│  任务进度:                                               │
│  ┌──────────────────────────────────────────────────┐  │
│  │ 任务ID: TT_REPEAT_1234567890                     │  │
│  │ 状态: 运行中                                      │  │
│  │ 进度: 12/50 (24%)                                │  │
│  │ [进度条: ████████░░░░░░░░░░░░░░]                 │  │
│  │                                                   │  │
│  │ 成功: 10 | 失败: 2                                │  │
│  │                                                   │  │
│  │ 执行记录:                                         │  │
│  │ 第1轮: ✓ 成功 (10:05:00)                         │  │
│  │ 第2轮: ✗ 失败 - 注册脚本执行失败 (10:15:00)       │  │
│  │ 第3轮: ✓ 成功 (10:25:00)                         │  │
│  │ ...                                               │  │
│  └──────────────────────────────────────────────────┘  │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

## 三、实现步骤

### 步骤1: 扩展TaskInfo（5分钟）
- 添加 `totalCount` 字段
- 扩展 `deviceResults` 支持轮次结果（或改为 `roundResults`）

### 步骤2: 新增Service方法（30分钟）
- 实现 `repeatRegisterOnDevice` 方法
- 实现 `executeRepeatRegisterAsync` 方法
- 复用现有的 `registerSingleDevice` 方法

### 步骤3: 新增Controller接口（10分钟）
- 添加 `/api/tt-register/repeat` 接口
- 参数验证
- 调用Service

### 步骤4: 前端界面（30分钟）
- 添加单设备多次注册表单
- 添加进度显示
- 调用新接口

### 步骤5: 测试（20分钟）
- 测试单设备多次注册功能
- 验证进度追踪
- 验证错误处理

**总时间估算: 约1.5小时**

## 四、优化建议（可选）

### 4.1 提前结束选项
如果成功数量达到目标，可以选择提前结束：
```java
if (successCount >= targetCount) {
    log.info("已达到目标成功数量，提前结束任务");
    break;
}
```

### 4.2 失败重试策略
- 失败后可以选择立即重试
- 或者记录失败，继续下一轮，最后统一重试

### 4.3 暂停/恢复功能
- 支持暂停任务
- 支持恢复任务

### 4.4 设备状态检查
- 在执行前检查设备状态
- 如果设备有问题，提前报错

## 五、使用示例

### 5.1 调用示例

```bash
curl -X POST http://localhost:8081/api/tt-register/repeat \
  -H "Content-Type: application/json" \
  -d '{
    "phoneId": "tt_107_224_1",
    "serverIp": "10.7.107.224",
    "targetCount": 50,
    "resetParams": {
      "country": "BR",
      "sdk": "33",
      "dynamicIpChannel": "closeli",
      "staticIpChannel": ""
    }
  }'
```

### 5.2 查询进度

```bash
curl http://localhost:8081/api/tt-register/status/TT_REPEAT_1234567890
```

## 六、总结

### 6.1 核心特点
- ✅ 简单直接：单设备循环注册
- ✅ 复用现有代码：使用 `registerSingleDevice` 方法
- ✅ 进度追踪：实时查看每轮注册结果
- ✅ 异步执行：不阻塞主线程

### 6.2 适用场景
- ✅ 单设备测试不同策略
- ✅ 单设备批量注册账号
- ✅ 验证设备稳定性

### 6.3 后续扩展
- 可以扩展为多设备并行执行（参考完整版设计方案）
- 可以添加策略管理功能
- 可以添加更详细的数据统计



