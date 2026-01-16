# 自动养号系统实现文档

## 📋 概述

本次实现将原有的 `TTTest_main_auto.py` Python脚本的自动化控制逻辑迁移到Java后台，实现了一个完整的**后台自动化编排系统**。

### 核心思路
- **Java后台**: 负责任务编排、设备分组、状态管理、数据库操作、进度监控
- **Python脚本**: 保留原有功能，只做底层执行（关注、刷视频、上传视频等）
- **不创建任务表**: 使用Redis临时存储任务状态（24小时）
- **脚本自带日志**: Python脚本的日志保持不变，后端只做编排

---

## 🏗️ 架构设计

```
┌─────────────────────────────────────────┐
│          Java后台 (控制层)              │
├─────────────────────────────────────────┤
│  - 任务配置与调度                        │
│  - 设备分组与编排 (每组10个)             │
│  - 状态管理 (Redis)                     │
│  - 数据库操作 (获取设备列表)             │
│  - 日志聚合与监控                        │
│  - 前端可视化                           │
└─────────────────────────────────────────┘
             ↓ SSH调用
┌─────────────────────────────────────────┐
│        Python脚本 (执行层)              │
├─────────────────────────────────────────┤
│  - TT_fast_follow.py (关注任务)         │
│  - TTTest_browse_video_main_dev.py      │
│    (浏览视频任务)                        │
│  - TTTest_video_distribute_dev.py       │
│    (视频分发)                            │
│  - TTTest_create_dev.py                 │
│    (上传视频、关闭应用)                  │
└─────────────────────────────────────────┘
             ↓ 操作
┌─────────────────────────────────────────┐
│        Shell脚本 & Docker命令           │
├─────────────────────────────────────────┤
│  - tiktok_check_static_ip.sh            │
│  - tiktok_phone_start.sh                │
│  - tiktok_phone_stop.sh                 │
│  - tiktok_switch_dynamic_ip_to_closeli.sh│
│  - docker exec (IP检查、开机状态)        │
└─────────────────────────────────────────┘
```

---

## 📦 实现内容

### 1. 后端实现

#### 1.1 配置类
- **`AutoNurtureConfig.java`**: 自动养号任务配置
  - 执行轮次 (默认3轮)
  - 每组设备数 (默认10个)
  - 云手机服务器IP
  - 允许的IP地区 (US, CA)
  - 组超时时间 (默认40分钟)

#### 1.2 实体类
- **`DeviceInfo.java`**: 设备信息实体
  - 云手机ID (phoneId)
  - 服务器IP (serverIp)
  - 包名 (pkgName)
  - 状态 (status)
  - 上传状态 (uploadStatus)

#### 1.3 Repository
- **`TtAccountDataRepository.java`**: 新增方法
  ```java
  List<DeviceInfo> getDeviceList(
      @Param("phoneServerId") String phoneServerId,
      @Param("status") Integer status,
      @Param("uploadStatus") Integer uploadStatus
  );
  ```

#### 1.4 核心服务
- **`AutoNurtureService.java`**: 自动养号核心服务
  - `startAutoNurture()`: 启动任务
  - `getTaskStatus()`: 查询任务状态
  - `getTaskList()`: 获取任务列表
  - `executeAutoNurtureAsync()`: 异步执行任务
  - `executeDeviceGroup()`: 执行设备组
  - `executeSingleDevice()`: 执行单个设备任务
  - `executeFollowTask()`: 调用关注脚本
  - `executeBrowseTask()`: 调用浏览视频脚本

#### 1.5 控制器
- **`AutoNurtureController.java`**: API接口
  - `POST /api/auto-nurture/start`: 启动任务
  - `GET /api/auto-nurture/status/{taskId}`: 查询状态
  - `GET /api/auto-nurture/tasks`: 获取任务列表

---

### 2. 前端实现

#### 2.1 页面布局
- **`index.html`**: 新增"自动养号"导航菜单和页面区域
  - 任务配置表单
  - 任务列表 (状态、进度、统计)
  - 任务详情 (实时监控)

#### 2.2 JavaScript函数
- **`app.js`**: 新增自动养号相关函数
  - `startAutoNurture()`: 启动任务
  - `loadAutoNurtureTasks()`: 加载任务列表
  - `viewTaskDetail()`: 查看任务详情
  - `refreshTaskDetail()`: 刷新任务状态 (每5秒)
  - `updateTaskDetailUI()`: 更新UI

---

## 🔄 执行流程

### 整体流程
```
用户配置任务参数 → 点击启动
    ↓
Java后台创建任务ID → 保存Redis状态
    ↓
异步执行 executeAutoNurtureAsync()
    ↓
执行3轮任务 (根据配置)
    ↓
每轮从数据库获取设备列表
    ↓
按10个一组分组执行
    ↓
每组执行 executeDeviceGroup()
    ↓
并发执行单设备任务 (最多10个线程)
    ↓
单设备任务流程:
  1. 检查静态IP
  2. 启动云手机
  3. 等待开机
  4. 视频分发 (如需上传)
  5. 切换IP到closeli
  6. 检查IP地区 (US/CA)
  7. 上传视频 (如需要)
  8. 关注任务
  9. 浏览视频
  10. 关闭应用
  11. 关闭云手机
    ↓
更新Redis状态 (进度、统计)
    ↓
前端实时刷新 (每5秒)
    ↓
任务完成，保留24小时
```

### 3轮任务策略
```
第1轮:
  - 条件: status=0 & upload_status=1
  - 操作: 上传视频 + 关注 + 浏览

第2轮:
  - 条件: status=0 & upload_status=0
  - 操作: 随机上传(30%概率) + 关注 + 浏览

第3轮:
  - 条件: status=2
  - 操作: 仅记录黑名单状态
```

---

## 📊 Redis数据结构

### 任务状态键
```
auto_nurture:task:{taskId}
```

### 数据内容
```json
{
  "taskId": "AUTO_NURTURE_1697512345678",
  "status": "RUNNING|COMPLETED|FAILED",
  "startTime": "2023-10-17T10:00:00",
  "endTime": "2023-10-17T12:30:00",
  "progress": 75,
  "currentRound": 2,
  "totalRounds": 3,
  "config": {
    "rounds": 3,
    "groupSize": 10,
    "phoneServerIp": "10.7.107.224",
    "allowedCountries": ["US", "CA"],
    "groupTimeout": 40
  },
  "summary": {
    "total": 150,
    "success": 120,
    "failed": 10,
    "ip_failed": 5,
    "log_out": 3,
    "black_list": 10,
    "follow_all": 2
  }
}
```

---

## 🎯 状态码说明

| 状态码 | 含义 | 说明 |
|--------|------|------|
| `FOLLOW_SUCCESS` | 关注成功 | 正常完成关注任务 |
| `FOLLOW_FAILED` | 无法关注 | 关注失败但账号正常 |
| `FOLLOW_ALL` | 已关注所有目标 | 目标全部关注完毕 |
| `FOLLOW_ERROR` | 关注异常 | 脚本执行异常 |
| `BLACK_LIST` | 冷却期账号 | status=2的账号 |
| `LOG_OUT` | 账号被封 | 浏览视频检测到封号 |
| `IP_FAILED` | IP检查失败 | IP地区不符合要求 |
| `ERROR` | 执行异常 | 任务执行过程异常 |
| `TIMEOUT` | 超时 | 组执行超过40分钟 |

---

## 🚀 使用方法

### 1. 启动服务
```bash
# 确保服务正常运行
mvn spring-boot:run
```

### 2. 访问前端
```
http://localhost:8081
```

### 3. 启动自动养号任务
1. 点击侧边栏 "自动养号"
2. 配置任务参数:
   - 执行轮次: 3轮
   - 每组设备数: 10个
   - 云手机服务器: 10.7.107.224
   - 组超时: 40分钟
   - 允许IP地区: US,CA
3. 点击 "启动自动养号任务"
4. 自动跳转到任务详情，实时监控

### 4. 查看任务列表
- 任务列表自动刷新
- 点击 "查看" 按钮查看详情
- 详情页面每5秒自动刷新

---

## ⚙️ 配置说明

### 数据库查询条件
```sql
SELECT phone_id, phone_server_id, pkg_name, status, upload_status
FROM tt_account_data
WHERE phone_server_id = '10.7.107.224'
  AND status = ?
  AND upload_status = ?
```

### SSH连接配置
- 脚本服务器: `10.13.55.85` (root)
- 云手机服务器: `10.7.107.224` (ubuntu)
- 通过跳板机连接
- 使用私钥认证

---

## 📝 日志说明

### Java日志
```
任务 AUTO_NURTURE_xxx 开始执行
任务 AUTO_NURTURE_xxx 第1轮获取到 50 个设备
=== 任务 AUTO_NURTURE_xxx 第1轮开始执行第1/5组，设备数: 10 ===
phone_001 - 检查静态IP
phone_001 - 启动云手机
phone_001 - 切换到closeli
phone_001 - 检查IP地区: US
phone_001 - 执行关注任务
phone_001 - 浏览视频
phone_001 - 任务完成，最终状态: FOLLOW_SUCCESS
=== 任务 AUTO_NURTURE_xxx 执行完成，统计: {total=150, success=120, ...} ===
```

### Python日志
- 保持原有脚本的日志输出
- 位置: `/data/appium/com_zhiliaoapp_musically/zl/log/`

---

## 🔧 故障排查

### 1. 任务启动失败
- 检查数据库连接
- 检查Redis连接
- 检查SSH配置

### 2. 设备获取为空
- 检查数据库查询条件
- 检查 `phone_server_id`、`status`、`upload_status` 是否正确

### 3. SSH执行失败
- 检查跳板机连接
- 检查私钥配置
- 检查目标服务器连通性

### 4. 脚本执行超时
- 增加 `groupTimeout` 配置
- 检查网络延迟
- 检查云手机响应速度

---

## 🎉 完成清单

- ✅ 创建配置类和实体类
- ✅ 实现 AutoNurtureService 核心服务
- ✅ 创建 AutoNurtureController API接口
- ✅ 实现前端页面 (任务配置、监控、日志)
- ✅ 修复所有Linter错误
- ⏳ 端到端测试 (待用户确认)

---

## 📞 后续支持

如有问题，请检查:
1. 日志文件 (`logs/spring.log`)
2. Redis状态 (`redis-cli keys "auto_nurture:*"`)
3. 数据库数据 (`SELECT * FROM tt_account_data LIMIT 10`)
4. SSH连接 (`ssh root@10.13.55.85`)

---

**实现完成时间**: 2025-10-15
**版本**: v1.0

