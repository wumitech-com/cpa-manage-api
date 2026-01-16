# 多策略批量注册TT账号设计方案

## 一、需求分析

### 1.1 资源情况
- **服务器数量**: 3台
- **每台服务器云手机数量**: 10个docker（10台云手机）
- **总云手机数量**: 30台

### 1.2 目标
- 测试不同注册策略（每种策略100个账号）
- 需要不断reset、换机、注册
- **优化目标**: 提高注册速度

### 1.3 策略示例
不同策略可能包含以下组合：
- **IP渠道策略**: 
  - 策略1: 纯动态（closeli）
  - 策略2: 纯动态（ipidea）
  - 策略3: 纯动态（netnut）
  - 策略4: 动静结合（closeli + ipidea静态）
  - 策略5: 动静结合（ipidea + ipidea静态）
  
- **国家策略**: US, BR, IN, TH等

- **SDK版本策略**: 33, 32, 31等

- **GAID标签策略**: 不同的标签组合

## 二、设计方案

### 2.1 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                     前端策略配置界面                           │
│  - 定义多个策略（策略名称、参数组合）                          │
│  - 每个策略设置目标账号数量（如100个）                         │
│  - 配置设备池（30台云手机）                                   │
└───────────────────┬─────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────────┐
│                  策略任务调度器                                │
│  - 策略队列管理                                               │
│  - 任务分配算法（智能调度）                                   │
│  - 进度追踪                                                   │
└───────────────────┬─────────────────────────────────────────┘
                    │
        ┌───────────┴───────────┐
        │                       │
        ▼                       ▼
┌──────────────┐      ┌─────────────────┐
│  任务队列     │      │   设备池管理     │
│ - 待执行任务  │      │ - 设备状态       │
│ - 执行中任务  │      │ - 设备分配       │
│ - 已完成任务  │      │ - 负载均衡       │
└──────┬───────┘      └─────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────┐
│              并行执行引擎（线程池/异步执行）                    │
│  - 每个设备独立线程                                           │
│  - 最大并发数：30（30台设备同时执行）                          │
│  - 失败重试机制                                               │
└───────────────────┬─────────────────────────────────────────┘
                    │
        ┌───────────┴───────────┐
        │                       │
        ▼                       ▼
┌──────────────┐      ┌─────────────────┐
│  ResetPhoneEnv│      │   注册脚本执行   │
│  (reset+换机) │      │   (TikTok注册)  │
└──────────────┘      └─────────────────┘
```

### 2.2 核心设计要点

#### 2.2.1 策略定义

```java
public class RegistrationStrategy {
    private String strategyId;          // 策略ID
    private String strategyName;        // 策略名称
    private String country;             // 国家代码
    private String sdk;                 // SDK版本
    private String dynamicIpChannel;    // 动态IP渠道
    private String staticIpChannel;     // 静态IP渠道
    private String gaidTag;             // GAID标签
    private String biz;                 // 业务标识
    private int targetCount;            // 目标账号数量（如100）
    private int completedCount;         // 已完成数量
    private int failedCount;            // 失败数量
    private String status;              // 策略状态：PENDING/RUNNING/COMPLETED/PAUSED
}
```

#### 2.2.2 任务队列系统

```java
public class RegistrationTask {
    private String taskId;              // 任务ID
    private String strategyId;          // 所属策略ID
    private String phoneId;             // 云手机ID
    private String serverIp;            // 服务器IP
    private Map<String, String> params; // ResetPhoneEnv参数
    private String status;              // 任务状态：PENDING/RUNNING/SUCCESS/FAILED
    private LocalDateTime startTime;    // 开始时间
    private LocalDateTime endTime;      // 结束时间
    private String errorMessage;        // 错误信息
    private int retryCount;             // 重试次数
}
```

#### 2.2.3 设备池管理

```java
public class DevicePool {
    private String phoneId;             // 云手机ID
    private String serverIp;            // 服务器IP
    private String status;              // 设备状态：IDLE/BUSY/ERROR/MAINTENANCE
    private String currentTaskId;       // 当前执行的任务ID
    private LocalDateTime lastUsedTime; // 最后使用时间
    private int successCount;           // 成功次数
    private int failedCount;            // 失败次数
    private String lastError;           // 最后错误信息
}
```

### 2.3 优化策略

#### 2.3.1 并行执行优化

**当前问题**: 串行执行，一台设备完成后再处理下一台
**优化方案**: 
- 使用线程池，最大并发数 = 30（所有设备同时执行）
- 每个设备独立线程，互不阻塞
- 使用 `@Async` + `CompletableFuture` 实现异步并发

**性能提升估算**:
- 串行: 30个设备 × 10分钟/设备 = 300分钟（5小时）
- 并行: 10分钟（最慢的那台设备）
- **速度提升**: 约30倍

#### 2.3.2 智能调度算法

**1. 负载均衡策略**
```
- 轮询分配：确保每台服务器负载均衡
- 优先使用空闲设备
- 避免单台服务器过载
```

**2. 策略轮换**
```
- 不同策略的任务均匀分配到设备池
- 避免同一策略集中在某些设备
- 提高测试的随机性和可靠性
```

**3. 失败重试策略**
```
- 失败任务自动进入重试队列
- 优先分配给其他空闲设备
- 避免重复使用有问题的设备
```

#### 2.3.3 任务优先级

```
优先级1: 新策略任务（待执行）
优先级2: 失败重试任务（重试队列）
优先级3: 补充任务（已完成但未达到目标数量）
```

### 2.4 执行流程

#### 2.4.1 策略任务创建流程

```
1. 用户在前端配置多个策略
   ├─ 策略1: 纯动态closeli，目标100个账号
   ├─ 策略2: 纯动态ipidea，目标100个账号
   └─ 策略3: 动静结合，目标100个账号

2. 系统为每个策略创建任务队列
   ├─ 策略1: 创建100个任务
   ├─ 策略2: 创建100个任务
   └─ 策略3: 创建100个任务

3. 任务分配到设备池
   ├─ 30台设备同时开始执行
   ├─ 每台设备从任务队列取一个任务
   └─ 执行完成后自动取下一个任务
```

#### 2.4.2 单设备执行流程

```
设备A获取任务
  ↓
启动云手机（如果未启动）
  ↓
执行ResetPhoneEnv（reset+换机+代理切换）
  ↓
安装TikTok APK
  ↓
执行注册脚本
  ↓
任务完成，标记成功/失败
  ↓
从队列获取下一个任务（循环）
```

### 2.5 进度追踪

#### 2.5.1 策略级别进度

```json
{
  "strategyId": "strategy_1",
  "strategyName": "纯动态closeli策略",
  "targetCount": 100,
  "completedCount": 45,
  "failedCount": 5,
  "runningCount": 10,
  "pendingCount": 40,
  "progress": 45.0,
  "status": "RUNNING"
}
```

#### 2.5.2 设备级别进度

```json
{
  "phoneId": "tt_107_224_1",
  "serverIp": "10.7.107.224",
  "currentTask": {
    "taskId": "task_123",
    "strategyId": "strategy_1",
    "status": "RUNNING",
    "startTime": "2024-01-01T10:00:00"
  },
  "todayStats": {
    "successCount": 8,
    "failedCount": 2
  }
}
```

#### 2.5.3 全局进度

```json
{
  "totalStrategies": 5,
  "totalTargetCount": 500,
  "totalCompletedCount": 120,
  "totalFailedCount": 15,
  "totalRunningCount": 25,
  "totalPendingCount": 340,
  "overallProgress": 24.0,
  "estimatedTimeRemaining": "2小时30分钟"
}
```

## 三、实现方案

### 3.1 数据库设计（可选，用于持久化）

```sql
-- 策略表
CREATE TABLE registration_strategy (
    id VARCHAR(50) PRIMARY KEY,
    strategy_name VARCHAR(100),
    country VARCHAR(10),
    sdk VARCHAR(10),
    dynamic_ip_channel VARCHAR(50),
    static_ip_channel VARCHAR(50),
    gaid_tag VARCHAR(50),
    biz VARCHAR(50),
    target_count INT,
    completed_count INT DEFAULT 0,
    failed_count INT DEFAULT 0,
    status VARCHAR(20),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 任务表
CREATE TABLE registration_task (
    id VARCHAR(50) PRIMARY KEY,
    strategy_id VARCHAR(50),
    phone_id VARCHAR(100),
    server_ip VARCHAR(50),
    params JSON,
    status VARCHAR(20),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    error_message TEXT,
    retry_count INT DEFAULT 0,
    FOREIGN KEY (strategy_id) REFERENCES registration_strategy(id)
);
```

### 3.2 核心类设计

#### 3.2.1 StrategyBatchRegistrationService

```java
@Service
public class StrategyBatchRegistrationService {
    
    // 策略管理
    public String createStrategy(RegistrationStrategy strategy);
    public List<RegistrationStrategy> getAllStrategies();
    public RegistrationStrategy getStrategy(String strategyId);
    public void updateStrategyProgress(String strategyId, int completed, int failed);
    
    // 任务队列管理
    public void createTasksForStrategy(String strategyId, int count);
    public RegistrationTask getNextTask();
    public void completeTask(String taskId, boolean success, String errorMessage);
    public void retryTask(String taskId);
    
    // 批量执行
    public String startBatchRegistration(List<String> strategyIds);
    public void executeBatchRegistrationAsync(String batchId);
    
    // 进度查询
    public Map<String, Object> getStrategyProgress(String strategyId);
    public Map<String, Object> getOverallProgress();
    public Map<String, Object> getDeviceStatus();
}
```

#### 3.2.2 DevicePoolManager

```java
@Component
public class DevicePoolManager {
    
    private List<DeviceInfo> devicePool; // 30台设备
    
    // 设备分配
    public DeviceInfo allocateDevice();
    public void releaseDevice(String phoneId);
    public void markDeviceError(String phoneId, String error);
    
    // 设备状态查询
    public List<DeviceInfo> getAvailableDevices();
    public List<DeviceInfo> getBusyDevices();
    public DeviceInfo getDevice(String phoneId);
    
    // 负载均衡
    public DeviceInfo getDeviceByLoadBalance(String serverIp);
}
```

### 3.3 并发执行优化

#### 3.3.1 使用线程池

```java
@Configuration
public class AsyncConfig {
    @Bean(name = "registrationExecutor")
    public Executor registrationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(30);      // 核心线程数 = 设备数
        executor.setMaxPoolSize(30);       // 最大线程数 = 设备数
        executor.setQueueCapacity(1000);   // 队列容量
        executor.setThreadNamePrefix("reg-");
        executor.initialize();
        return executor;
    }
}
```

#### 3.3.2 并行执行实现

```java
public void executeBatchRegistrationAsync(String batchId) {
    // 获取所有待执行任务
    List<RegistrationTask> tasks = taskQueue.getPendingTasks();
    
    // 并行执行所有任务
    List<CompletableFuture<Void>> futures = tasks.stream()
        .map(task -> CompletableFuture.runAsync(() -> {
            DeviceInfo device = devicePoolManager.allocateDevice();
            try {
                executeRegistrationTask(task, device);
            } finally {
                devicePoolManager.releaseDevice(device.getPhoneId());
            }
        }, registrationExecutor))
        .collect(Collectors.toList());
    
    // 等待所有任务完成
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
}
```

### 3.4 前端界面设计

#### 3.4.1 策略配置界面

```
┌─────────────────────────────────────────────────────────┐
│  策略批量注册管理                                         │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  【添加策略】                                            │
│                                                          │
│  策略列表：                                              │
│  ┌──────────────────────────────────────────────────┐  │
│  │ 策略1: 纯动态closeli                              │  │
│  │ 目标: 100 | 已完成: 45 | 失败: 5 | 进行中: 10   │  │
│  │ [进度条: ████████░░░░░░░░] 45%                  │  │
│  │ [启动] [暂停] [删除]                              │  │
│  └──────────────────────────────────────────────────┘  │
│                                                          │
│  ┌──────────────────────────────────────────────────┐  │
│  │ 策略2: 纯动态ipidea                               │  │
│  │ 目标: 100 | 已完成: 30 | 失败: 3 | 进行中: 8    │  │
│  │ [进度条: ██████░░░░░░░░░░░░] 30%                │  │
│  │ [启动] [暂停] [删除]                              │  │
│  └──────────────────────────────────────────────────┘  │
│                                                          │
│  全局进度：                                              │
│  总目标: 500 | 已完成: 120 | 失败: 15 | 进行中: 25 │  │
│  [总体进度条: █████░░░░░░░░░░░░░░░] 24%              │  │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

#### 3.4.2 设备状态监控界面

```
┌─────────────────────────────────────────────────────────┐
│  设备池状态监控                                           │
├─────────────────────────────────────────────────────────┤
│  服务器1 (10.7.107.224)                                 │
│  ┌──────┬────────┬───────┬──────────┬──────────────┐  │
│  │ 设备 │ 状态   │ 当前任务 │ 今日成功 │ 今日失败    │  │
│  ├──────┼────────┼───────┼──────────┼──────────────┤  │
│  │ tt_1 │ 运行中 │ 策略1  │ 8        │ 2           │  │
│  │ tt_2 │ 空闲   │ -      │ 5        │ 1           │  │
│  │ ...  │ ...    │ ...    │ ...      │ ...         │  │
│  └──────┴────────┴───────┴──────────┴──────────────┘  │
│                                                          │
│  服务器2 (10.7.107.225)                                 │
│  [同上]                                                  │
│                                                          │
│  服务器3 (10.7.107.226)                                 │
│  [同上]                                                  │
└─────────────────────────────────────────────────────────┘
```

## 四、性能优化建议

### 4.1 当前方案（串行）性能

假设每台设备注册一个账号需要10分钟：
- 30台设备串行执行100个任务 = 100 × 10分钟 = 1000分钟 ≈ 16.7小时

### 4.2 优化后方案（并行）性能

假设每台设备注册一个账号需要10分钟：
- 30台设备并行执行，每轮30个任务同时完成
- 100个任务 = 4轮（100÷30≈4轮）
- 总时间 = 4 × 10分钟 = 40分钟

**性能提升：16.7小时 → 40分钟（约25倍）**

### 4.3 进一步优化

1. **任务预分配**: 提前准备好所有任务，避免任务创建延迟
2. **失败快速重试**: 失败任务立即分配给其他设备重试
3. **设备状态缓存**: 缓存设备状态，减少查询时间
4. **批量操作优化**: 对于相同服务器的设备，可以批量操作

## 五、实施步骤

### 阶段1: 基础功能（1-2天）
1. ✅ 创建策略管理数据结构
2. ✅ 实现任务队列系统
3. ✅ 实现设备池管理
4. ✅ 实现基础的任务执行流程

### 阶段2: 并发优化（1天）
1. ✅ 实现线程池配置
2. ✅ 改造为并行执行
3. ✅ 实现任务分配算法
4. ✅ 测试并发执行效果

### 阶段3: 前端界面（1-2天）
1. ✅ 策略配置界面
2. ✅ 进度监控界面
3. ✅ 设备状态界面
4. ✅ 实时更新机制

### 阶段4: 优化完善（1天）
1. ✅ 失败重试机制
2. ✅ 负载均衡优化
3. ✅ 性能监控
4. ✅ 日志完善

## 六、风险评估

### 6.1 潜在风险

1. **并发过高导致资源竞争**
   - 风险：30个并发可能导致SSH连接数过多
   - 缓解：使用连接池，限制每台服务器的并发连接数

2. **任务重复执行**
   - 风险：任务分配算法可能导致同一任务被多个设备执行
   - 缓解：使用分布式锁（Redis）确保任务唯一性

3. **设备故障**
   - 风险：某些设备持续失败
   - 缓解：设备故障自动标记，暂停分配任务，手动排查

### 6.2 监控指标

- 任务执行时间分布
- 设备成功率统计
- 策略完成进度
- 系统资源使用情况（CPU、内存、网络）

## 七、总结

### 7.1 核心优化点

1. **并行执行**: 从串行改为并行，性能提升约25倍
2. **智能调度**: 负载均衡、策略轮换、失败重试
3. **进度追踪**: 策略级别、设备级别、全局进度
4. **用户体验**: 友好的前端界面，实时监控

### 7.2 预期效果

- **速度提升**: 16.7小时 → 40分钟（100个账号）
- **资源利用率**: 从约3%提升到100%
- **可扩展性**: 易于添加新策略，易于扩展设备数量
- **可维护性**: 清晰的架构，易于排查问题



