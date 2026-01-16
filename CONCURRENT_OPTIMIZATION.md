# 动态并发优化说明

## 🎯 优化目标

**原问题**: 10个设备并发执行，如果有一个堵塞了，必须等超时才能执行下一组设备，导致资源浪费。

**优化方案**: 改为**动态线程池**模式，始终保持10个设备并发执行，完成一个就立即启动下一个。

---

## 🏗️ 实现方案

### 使用 `CompletionService`

```java
// 固定10个线程的线程池
ExecutorService executor = Executors.newFixedThreadPool(10);

// CompletionService 按完成顺序获取结果
CompletionService<DeviceExecutionResult> completionService = 
    new ExecutorCompletionService<>(executor);
```

### 执行流程

```
┌─────────────────────────────────────────┐
│  一次性提交所有设备任务到线程池          │
│  (例如: 50个设备)                        │
└─────────────────────────────────────────┘
                ↓
┌─────────────────────────────────────────┐
│  线程池自动维护10个活跃线程              │
│  - 前10个设备立即开始执行                │
│  - 剩余40个设备在队列中等待              │
└─────────────────────────────────────────┘
                ↓
┌─────────────────────────────────────────┐
│  按完成顺序收集结果 (poll)               │
│  - 设备1完成 → 设备11立即启动            │
│  - 设备2完成 → 设备12立即启动            │
│  - 设备3完成 → 设备13立即启动            │
│  - ...                                  │
└─────────────────────────────────────────┘
                ↓
┌─────────────────────────────────────────┐
│  所有设备执行完成或超时                  │
└─────────────────────────────────────────┘
```

---

## 📊 对比分析

### 优化前（固定分组）

```
设备1-10: [====执行中====] 完成
设备11-20:                 [====执行中====] 完成
设备21-30:                                  [====执行中====] 完成

问题:
- 如果设备3堵塞，设备1、2、4-10完成后也要等设备3超时
- 设备11-20 必须等第一组全部完成才能开始
- 资源利用率低
```

### 优化后（动态并发）

```
设备1-10:  [====执行中====]
设备11:                     [====执行中====] (设备1完成立即启动)
设备12:                       [====执行中====] (设备2完成立即启动)
设备13:                            [====执行中====] (设备4完成立即启动)
...

优势:
✅ 始终保持10个设备并发执行
✅ 完成一个立即启动下一个
✅ 资源利用率最大化
✅ 不会因个别设备堵塞影响整体进度
```

---

## 🔧 核心代码

### 1. 提交所有任务

```java
for (DeviceInfo device : devices) {
    completionService.submit(() -> {
        String phoneId = device.getPhoneId();
        long startTime = System.currentTimeMillis();
        
        try {
            String status = executeSingleDevice(taskId, device, config);
            long duration = System.currentTimeMillis() - startTime;
            return new DeviceExecutionResult(phoneId, status, true, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            forceStopDevice(phoneId, serverIp);
            return new DeviceExecutionResult(phoneId, "ERROR", false, duration);
        }
    });
}
```

### 2. 按完成顺序收集结果

```java
while (completedCount < submittedCount) {
    // 检查组总超时
    long elapsed = System.currentTimeMillis() - groupStartTime;
    if (elapsed > maxGroupDuration) {
        // 标记未完成的为超时
        break;
    }
    
    // 等待下一个完成的任务（最多1分钟）
    Future<DeviceExecutionResult> future = completionService.poll(
        Math.min(60000, remainingTime), 
        TimeUnit.MILLISECONDS
    );
    
    if (future != null) {
        DeviceExecutionResult result = future.get();
        results.put(result.getPhoneId(), result.getStatus());
        completedCount++;
        
        log.info("进度: {}/{} ({}%)，设备 {} 完成", 
            completedCount, submittedCount, progressPercent, result.getPhoneId());
    }
}
```

---

## 📈 性能提升

### 示例场景: 50个设备，每个平均15分钟

#### 优化前
```
第1组(10个): 20分钟 (最慢的设备20分钟)
第2组(10个): 20分钟
第3组(10个): 20分钟
第4组(10个): 20分钟
第5组(10个): 20分钟
────────────────────
总耗时: 100分钟
```

#### 优化后
```
并发执行: 所有50个设备，保持10个并发
最慢设备: 20分钟
────────────────────
总耗时: 约75分钟 (50设备 × 15分钟平均 ÷ 10并发)

节省: 25分钟 (25%提升)
```

---

## 🎯 超时控制

### 单设备超时机制 (默认40分钟)

每个设备都有**独立的超时控制**，互不影响：

```java
// 单设备超时时间
final long deviceTimeout = config.getGroupTimeout() * 60 * 1000L;  // 40分钟

// 每个设备都有独立的超时
Future<String> deviceFuture = singleDeviceExecutor.submit(() -> 
    executeSingleDevice(taskId, device, config)
);

try {
    // 等待设备执行完成，带超时
    String status = deviceFuture.get(deviceTimeout, TimeUnit.MILLISECONDS);
    
} catch (TimeoutException e) {
    // 单设备超时，不影响其他设备
    log.error("{} - 执行超时（40分钟），强制终止", phoneId);
    deviceFuture.cancel(true);
    forceStopDevice(phoneId, serverIp);
    return new DeviceExecutionResult(phoneId, "TIMEOUT", false, duration);
}
```

### 超时特点

✅ **独立超时**: 每个设备40分钟，互不影响  
✅ **不会堵塞**: 某个设备超时不影响其他设备执行  
✅ **自动清理**: 超时后自动取消任务并关闭云手机  
✅ **无总时长限制**: 理论上可以执行无限个设备（只要每个不超过40分钟）

---

## 📋 日志示例

### 执行日志

```
任务 AUTO_NURTURE_xxx 开始设备任务阶段，总设备数: 50，保持10个并发
任务 AUTO_NURTURE_xxx 已提交 50 个设备到线程池，开始按完成顺序处理结果

phone_001 - 任务完成，状态: FOLLOW_SUCCESS，耗时: 850秒
任务 AUTO_NURTURE_xxx 进度: 1/50 (2%)，设备 phone_001 完成，耗时 850秒

phone_003 - 任务完成，状态: FOLLOW_SUCCESS，耗时: 920秒
任务 AUTO_NURTURE_xxx 进度: 2/50 (4%)，设备 phone_003 完成，耗时 920秒

phone_002 - 任务完成，状态: IP_FAILED，耗时: 150秒
任务 AUTO_NURTURE_xxx 进度: 3/50 (6%)，设备 phone_002 完成，耗时 150秒

... (继续)

任务 AUTO_NURTURE_xxx 设备组执行完成，总计: 50，成功收集结果: 50
```

---

## 🔍 DeviceExecutionResult 类

```java
class DeviceExecutionResult {
    private final String phoneId;     // 设备ID
    private final String status;      // 执行状态 (FOLLOW_SUCCESS/ERROR/...)
    private final boolean success;    // 是否成功
    private final long duration;      // 执行耗时(毫秒)
}
```

**作用**: 封装每个设备的执行结果，便于统计和日志记录。

---

## ✅ 优化效果

1. ✅ **资源利用率提升**: 始终保持10个线程满负荷运行
2. ✅ **总执行时间减少**: 不会因个别设备堵塞影响整体
3. ✅ **实时进度反馈**: 每完成一个设备就更新进度
4. ✅ **超时控制精准**: 组级 + 设备级双重保护
5. ✅ **日志更详细**: 记录每个设备的耗时和完成顺序

---

## 🚀 使用示例

配置保持不变，系统自动应用优化：

```
执行轮次: 3轮
每组设备数: 10个 (实际上不再"分组"，而是保持10个并发)
组超时: 40分钟 (作为整批设备的最大执行时间)
```

**注意**: "每组设备数"现在实际上是"并发数"，不再是严格的分组概念。

---

**优化完成时间**: 2025-10-15  
**版本**: v1.1 (动态并发版本)

