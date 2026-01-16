# 日志配置说明（Logback彩色版）

## 🎨 彩色日志效果

### 控制台输出（彩色）

开发环境会显示彩色日志：

```
2025-10-20 14:30:15.123 INFO  [http-nio-8080-exec-1] c.c.service.AutoNurtureService - [getList,123] - >>> 开始执行
2025-10-20 14:30:15.456 WARN  [http-nio-8080-exec-1] c.c.util.SshUtil - [connect,456] - 连接超时警告  
2025-10-20 14:30:15.789 ERROR [http-nio-8080-exec-1] c.c.controller.TaskController - [execute,789] - 任务执行失败
2025-10-20 14:30:16.012 DEBUG [http-nio-8080-exec-2] c.c.repository.TtAccountDataRepository - [select,101] - SQL查询完成
```

**颜色说明：**
- 🔴 **ERROR** - 红色（醒目）
- 🟡 **WARN**  - 黄色（警告）
- 🔵 **INFO**  - 蓝色（常规）
- 🟢 **DEBUG** - 绿色（调试）

**元素颜色：**
- 📅 **时间戳** - 黄色
- 🧵 **线程名** - 青色
- 📦 **类名** - 紫红色（粗体）
- 💬 **消息** - 根据级别自动高亮

---

## 📁 文件日志格式

### 1. 主日志文件 (`cpa-manage-api.log`)

包含INFO及以上级别的所有日志：

```
2025-10-20 14:30:15.123 INFO  [http-nio-exec-1] c.c.service.AutoNurtureService - [getList,123] - >>> 开始执行
2025-10-20 14:30:15.456 WARN  [http-nio-exec-1] c.c.util.SshUtil - [connect,456] - 连接超时
2025-10-20 14:30:15.789 ERROR [http-nio-exec-1] c.c.controller.TaskController - [execute,789] - 任务失败
```

**格式说明：**
- 时间戳（精确到毫秒）
- 日志级别
- 线程名
- 类名
- **[方法名,行号]** - 快速定位代码位置 ⭐
- 日志消息

### 2. ERROR日志文件 (`cpa-manage-api-error.log`)

只记录ERROR级别，便于排查问题：

```
2025-10-20 14:30:15.789 ERROR [http-nio-exec-1] c.c.controller.TaskController - [execute,789] - 任务执行失败：连接超时
java.net.SocketTimeoutException: Read timed out
    at java.net.SocketInputStream.socketRead0(Native Method)
    ...
```

---

## ⚙️ 配置详情

### 日志格式变量

```xml
<!-- 文件格式：包含方法名和行号 -->
<property name="LOG_PATTERN" 
          value="%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{50} - [%method,%line] - %msg%n"/>

<!-- 控制台格式：彩色 -->
<property name="COLOR_LOG_PATTERN" 
          value="%yellow(%date{yyyy-MM-dd HH:mm:ss.SSS}) %highlight(%-5level) %cyan([%thread]) %boldMagenta(%logger{50}) - %highlight(%msg%n)"/>
```

### 日志级别

| 环境 | com.cpa | 第三方框架 | 控制台输出 |
|------|---------|-----------|----------|
| **dev（开发）** | DEBUG | WARN | ✅ 彩色 |
| **prod（生产）** | INFO | WARN | ❌ 无 |
| **默认** | INFO | WARN | ✅ 彩色 |

### 文件轮转策略

| 配置项 | 主日志 | ERROR日志 |
|--------|--------|----------|
| **单文件名** | `cpa-manage-api.log` | `cpa-manage-api-error.log` |
| **历史文件** | `cpa-manage-api.2025-10-20.log` | `cpa-manage-api-error.2025-10-20.log` |
| **保留天数** | 30天 | 90天 |
| **级别过滤** | INFO及以上 | 只有ERROR |

### 异步性能配置

```xml
<!-- INFO日志 - 大队列，允许阻塞 -->
<appender name="ASYNC_INFO">
    <discardingThreshold>500</discardingThreshold>  <!-- 队列80%时开始丢弃 -->
    <neverBlock>true</neverBlock>                    <!-- 永不阻塞 -->
    <queueSize>20000</queueSize>                     <!-- 队列大小20000 -->
</appender>

<!-- ERROR日志 - 小队列，不丢弃 -->
<appender name="ASYNC_ERROR">
    <discardingThreshold>0</discardingThreshold>     <!-- 永不丢弃 -->
    <queueSize>2000</queueSize>                      <!-- 队列大小2000 -->
</appender>
```

---

## 🔧 使用示例

### 基本用法

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PageFieldService {
    
    private static final Logger logger = LoggerFactory.getLogger(PageFieldService.class);
    
    public List<PageFieldEntity> getList(String factoryId, int loginWorkerId, String type) {
        long startTime = System.currentTimeMillis();
        
        // INFO - 常规信息
        logger.info(">>> 开始查询 - factoryId:{}, worker:{}, type:{}", factoryId, loginWorkerId, type);
        
        // DEBUG - 调试信息（只在dev环境显示）
        logger.debug("查询条件构建完成：{}", example);
        
        try {
            List<PageFieldEntity> list = dao.selectByExample(factoryId, example);
            
            long duration = System.currentTimeMillis() - startTime;
            
            // 根据耗时选择日志级别
            if (duration > 1000) {
                logger.warn("⚠️ 慢查询！耗时:{}ms, 结果数:{}", duration, list.size());
            } else {
                logger.info("✅ 查询完成，耗时:{}ms, 结果数:{}", duration, list.size());
            }
            
            return list;
            
        } catch (Exception e) {
            // ERROR - 错误信息（会单独记录到error.log）
            logger.error("❌ 查询失败！factoryId:{}, 耗时:{}ms", 
                factoryId, System.currentTimeMillis() - startTime, e);
            throw e;
        }
    }
}
```

### 实际输出效果

**开发环境控制台（彩色）：**
```
2025-10-20 14:30:15.123 INFO  [http-nio-exec-1] c.c.service.PageFieldService - [getList,25] - >>> 开始查询 - factoryId:1001, worker:2005, type:SOCHECK
2025-10-20 14:30:15.134 DEBUG [http-nio-exec-1] c.c.service.PageFieldService - [getList,28] - 查询条件构建完成：PageFieldEntityExample...
2025-10-20 14:30:15.456 INFO  [http-nio-exec-1] c.c.service.PageFieldService - [getList,38] - ✅ 查询完成，耗时:333ms, 结果数:15
```

**生产环境（无控制台，只有文件）：**
```
# cpa-manage-api.log
2025-10-20 14:30:15.123 INFO  [http-nio-exec-1] c.c.service.PageFieldService - [getList,25] - >>> 开始查询 - factoryId:1001, worker:2005, type:SOCHECK
2025-10-20 14:30:15.456 INFO  [http-nio-exec-1] c.c.service.PageFieldService - [getList,38] - ✅ 查询完成，耗时:333ms, 结果数:15

# cpa-manage-api-error.log（没有错误时为空）
```

---

## 📊 查看日志

### Windows PowerShell 命令

```powershell
# 实时查看主日志（最后100行，持续监控）
Get-Content logs\cpa-manage-api.log -Tail 100 -Wait

# 实时查看错误日志
Get-Content logs\cpa-manage-api-error.log -Tail 50 -Wait

# 搜索ERROR级别
Select-String "ERROR" logs\cpa-manage-api.log | Select-Object -Last 20

# 搜索特定方法
Select-String "getList" logs\cpa-manage-api.log

# 搜索慢查询（耗时>1000ms）
Select-String "耗时:[0-9]{4,}ms" logs\cpa-manage-api.log

# 统计日志级别分布
(Select-String " INFO  " logs\cpa-manage-api.log).Count
(Select-String " WARN  " logs\cpa-manage-api.log).Count
(Select-String " ERROR " logs\cpa-manage-api.log).Count

# 查看今天的日志
$today = Get-Date -Format "yyyy-MM-dd"
Get-Content "logs\cpa-manage-api.$today.log"

# 搜索包含堆栈的错误
Select-String -Pattern "ERROR.*Exception" logs\cpa-manage-api-error.log -Context 0,10
```

### Linux 命令

```bash
# 实时查看
tail -f -n 100 logs/cpa-manage-api.log

# 搜索
grep "ERROR" logs/cpa-manage-api.log | tail -20
grep "getList" logs/cpa-manage-api.log

# 统计
grep -c " INFO  " logs/cpa-manage-api.log
grep -c " ERROR " logs/cpa-manage-api.log
```

---

## 💡 最佳实践

### ✅ 推荐做法

```java
// 1. 方法入口记录关键参数
logger.info(">>> 方法开始 - 参数: id={}, type={}", id, type);

// 2. 关键操作记录耗时
long start = System.currentTimeMillis();
// ... 操作
logger.info("操作完成，耗时: {}ms", System.currentTimeMillis() - start);

// 3. 异常要记录完整堆栈
logger.error("操作失败: {}", errorMsg, exception);  // exception放最后

// 4. 使用占位符，不要字符串拼接
logger.info("用户{}执行了{}", userId, action);  // ✅ 好
logger.info("用户" + userId + "执行了" + action);  // ❌ 差

// 5. 慢操作要WARN
if (duration > 1000) {
    logger.warn("⚠️ 慢查询！耗时:{}ms", duration);
}

// 6. 使用emoji让日志更醒目（可选）
logger.info("✅ 成功");
logger.warn("⚠️ 警告");  
logger.error("❌ 失败");
logger.debug("🔍 调试");
```

### ❌ 避免做法

```java
// 1. 不要在循环中大量打印
for (int i = 0; i < 10000; i++) {
    logger.info("处理第{}条", i);  // ❌ 日志爆炸
}
// 应该：每100条打印一次
if (i % 100 == 0) {
    logger.info("已处理{}条", i);  // ✅
}

// 2. 不要打印敏感信息
logger.info("密码: {}", password);  // ❌ 安全问题
logger.info("登录成功，用户: {}", username);  // ✅

// 3. 不要在生产环境开DEBUG
// 会产生大量日志，影响性能
// 使用 spring.profiles.active=prod 自动切换到INFO

// 4. 不要吞掉异常
catch (Exception e) {
    logger.error("出错了");  // ❌ 没有堆栈信息
}
// 应该：
catch (Exception e) {
    logger.error("操作失败: {}", e.getMessage(), e);  // ✅ 有堆栈
}
```

---

## 🎯 性能监控完整示例

结合连接池监控的完整示例：

```java
@Override
@DataSource(name = DataSourcesNames.READ)
public List<PageFieldEntity> getList(String factoryId, int loginWorkerId, String type) {
    long methodStart = System.currentTimeMillis();
    String threadName = Thread.currentThread().getName();
    
    logger.info(">>> [{}] getList 开始 - factory:{}, worker:{}, type:{}", 
        threadName, factoryId, loginWorkerId, type);
    
    try {
        // 1. 构建查询条件
        long step1 = System.currentTimeMillis();
        PageFieldEntityExample example = new PageFieldEntityExample();
        example.createCriteria()
            .andFactoryIdEqualTo(factoryId)
            .andCreateUidEqualTo(loginWorkerId)
            .andTypeEqualTo(type);
        example.setOrderByClause("order_sort");
        logger.debug("查询条件构建耗时: {}ms", System.currentTimeMillis() - step1);
        
        // 2. 执行数据库查询
        long dbStart = System.currentTimeMillis();
        logger.debug(">>> [{}] 准备执行数据库查询...", threadName);
        
        List<PageFieldEntity> list = pageFieldDao.selectByExample(factoryId, example);
        
        long dbEnd = System.currentTimeMillis();
        long dbDuration = dbEnd - dbStart;
        
        // 3. 根据耗时选择日志级别
        if (dbDuration > 1000) {
            logger.warn("⚠️ [{}] 慢查询！DB耗时:{}ms, 结果数:{}", threadName, dbDuration, list.size());
        } else if (dbDuration > 500) {
            logger.info("⚡ [{}] 查询较慢，DB耗时:{}ms, 结果数:{}", threadName, dbDuration, list.size());
        } else {
            logger.info("✅ [{}] 查询完成，DB耗时:{}ms, 结果数:{}", threadName, dbDuration, list.size());
        }
        
        long totalDuration = System.currentTimeMillis() - methodStart;
        logger.info(">>> [{}] getList 完成，总耗时:{}ms", threadName, totalDuration);
        
        return list;
        
    } catch (Exception e) {
        long errorTime = System.currentTimeMillis();
        long errorDuration = errorTime - methodStart;
        logger.error("❌ [{}] getList 异常！factory:{}, worker:{}, type:{}, 耗时:{}ms", 
            threadName, factoryId, loginWorkerId, type, errorDuration, e);
        throw e;
    }
}
```

**输出效果（控制台彩色）：**

```
# 正常情况
2025-10-20 14:30:15.123 INFO  [http-nio-exec-1] c.c.service.PageFieldService - [getList,10] - >>> [http-nio-exec-1] getList 开始 - factory:1001, worker:2005, type:SOCHECK
2025-10-20 14:30:15.456 INFO  [http-nio-exec-1] c.c.service.PageFieldService - [getList,35] - ✅ [http-nio-exec-1] 查询完成，DB耗时:333ms, 结果数:15
2025-10-20 14:30:15.457 INFO  [http-nio-exec-1] c.c.service.PageFieldService - [getList,41] - >>> [http-nio-exec-1] getList 完成，总耗时:334ms

# 慢查询情况
2025-10-20 14:30:16.123 INFO  [http-nio-exec-2] c.c.service.PageFieldService - [getList,10] - >>> [http-nio-exec-2] getList 开始 - factory:1001, worker:2005, type:SOCHECK
2025-10-20 14:30:17.456 WARN  [http-nio-exec-2] c.c.service.PageFieldService - [getList,33] - ⚠️ [http-nio-exec-2] 慢查询！DB耗时:1333ms, 结果数:150
2025-10-20 14:30:17.457 INFO  [http-nio-exec-2] c.c.service.PageFieldService - [getList,41] - >>> [http-nio-exec-2] getList 完成，总耗时:1334ms
```

---

## 🛠️ 切换环境

### 开发环境

```yaml
# application.yml 或启动参数
spring:
  profiles:
    active: dev
```

效果：
- ✅ 控制台彩色输出
- ✅ DEBUG级别日志
- ✅ 文件日志

### 生产环境

```yaml
spring:
  profiles:
    active: prod
```

效果：
- ❌ 无控制台输出（节省性能）
- ✅ INFO级别日志
- ✅ 文件日志

---

## ✨ 特色功能

### 1. 方法名+行号定位

```
- [getList,123] - 
  ↑       ↑
  方法名  行号
```

快速定位到具体代码位置！

### 2. 异步高性能

- 日志不阻塞业务代码
- 队列满了也能继续（neverBlock）
- ERROR级别永不丢弃

### 3. 环境自动切换

- dev：DEBUG + 彩色控制台
- prod：INFO + 只写文件
- 无需手动修改配置

### 4. 智能分级

- 主日志：INFO及以上（常规查看）
- ERROR日志：只有错误（快速排查）

---

## 🎉 完成！

重启应用后，你会看到：
- ✅ **控制台彩色输出**（开发环境）
- ✅ **文件包含方法名和行号**（快速定位）
- ✅ **ERROR日志单独存储**（便于排查）
- ✅ **异步高性能**（不影响业务）
- ✅ **生产环境自动优化**（关闭控制台）

查看效果：
1. 启动应用
2. 观察控制台彩色日志
3. 访问 `logs/cpa-manage-api.log` 查看文件日志
4. 故意制造一个错误，查看 `logs/cpa-manage-api-error.log`

**建议使用的终端：**
- Windows: Windows Terminal、PowerShell、ConEmu
- Linux/Mac: 原生终端
- IDE: IntelliJ IDEA、VS Code（都支持ANSI颜色）
