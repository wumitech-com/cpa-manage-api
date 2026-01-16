# CPA云手机管理系统

## 项目概述

Java Spring Boot后台管理系统，用于自动化管理云手机创建、账号注册、养号等操作。

## 核心功能

### 1. 云手机生命周期管理

#### 设备池 (`tt_account_data_outlook`)
- 存储新创建的云手机
- 等待注册Outlook邮箱和TikTok账号

#### 账号库 (`tt_account_data`)
- 存储已注册的TikTok账号
- 管理养号状态和进度

### 2. 自动化脚本执行

#### 支持的脚本类型
1. **云手机创建** - 批量创建云手机（2-3小时长任务）
2. **Outlook注册** - 自动注册Outlook邮箱
3. **TikTok注册** - 自动注册TikTok账号
4. **TikTok关注** - 自动关注指定账号
5. **视频刷留存** - 自动观看视频养号

#### 执行方式
- **同步执行**: 适合快速任务
- **异步执行**: 使用`nohup`后台执行，适合长时间任务
- **定时任务**: 每日自动执行指定脚本

### 3. SSH跳板机连接

#### 连接架构
```
Java应用 → 跳板机(106.75.152.136) → 目标服务器(10.7.107.224)
         [私钥认证]              [两段式SSH]
```

#### 实现方式
使用"两段式SSH连接"：
1. 用私钥连接跳板机
2. 在跳板机上执行 `ssh ubuntu@目标服务器 '命令'`

优势：利用跳板机上已配置的SSH认证，无需在目标服务器部署额外密钥

### 4. 异步任务监控

#### 云手机创建流程
1. API调用 → 提交创建任务
2. 后台执行 → `nohup`在远程服务器运行
3. 返回PID → 立即返回进程ID
4. 异步监控 → 每分钟检查进程状态
5. 自动解析 → 完成后读取日志解析云手机名称
6. 插入数据库 → 自动将成功创建的云手机录入设备池

#### 日志解析
- 自动识别SUCCESS和FAILED记录
- 提取云手机名称（如：`tt_107_224_1_US_20251011`）
- 统计成功/失败数量

## 技术栈

### 后端框架
- **Spring Boot 2.7.18**
- **Spring Data JPA** - 数据持久化
- **MyBatis Plus 3.5.3.1** - ORM框架
- **Spring Task** - 定时任务

### 数据存储
- **MySQL 8.0** - 主数据库
- **Redis** - 缓存和任务队列

### 远程执行
- **JSch 0.1.55** - SSH客户端库
- 支持私钥认证
- 支持跳板机连接

## 项目结构

```
src/main/java/com/cpa/
├── config/
│   ├── RedisConfig.java           # Redis配置
│   └── SshProperties.java         # SSH配置属性
├── controller/
│   ├── DeviceController.java      # 设备管理API
│   ├── ScriptController.java      # 脚本执行API
│   ├── TaskController.java        # 任务管理API
│   └── StatisticsController.java  # 统计数据API
├── service/
│   ├── DeviceService.java         # 设备业务逻辑
│   ├── ScriptService.java         # 脚本执行逻辑
│   ├── TaskSchedulerService.java  # 定时任务
│   └── StatisticsService.java     # 统计服务
├── repository/
│   ├── TtAccountDataRepository.java        # 账号库DAO
│   └── TtAccountDataOutlookRepository.java # 设备池DAO
├── entity/
│   ├── TtAccountData.java         # 账号实体
│   └── TtAccountDataOutlook.java  # 设备实体
├── util/
│   └── SshUtil.java               # SSH工具类
└── CpaManageApiApplication.java   # 启动类
```

## API接口

### 设备管理
- `GET /api/devices/pool` - 获取设备池列表
- `GET /api/devices/library` - 获取账号库列表
- `POST /api/devices/pool` - 添加设备到设备池
- `PUT /api/devices/pool/{id}` - 更新设备信息
- `DELETE /api/devices/pool/{id}` - 删除设备

### 脚本执行
- `POST /api/scripts/create-phone` - 创建云手机（异步）
- `POST /api/scripts/register-outlook` - 注册Outlook
- `POST /api/scripts/register-tt` - 注册TikTok
- `POST /api/scripts/follow` - TikTok关注
- `POST /api/scripts/watch-video` - 刷视频养号
- `GET /api/scripts/async-status` - 查询异步任务状态
- `GET /api/scripts/test-parse-log` - 测试日志解析

### 任务管理
- `GET /api/tasks/list` - 获取定时任务列表
- `POST /api/tasks/create` - 创建定时任务
- `POST /api/tasks/pause/{id}` - 暂停任务
- `POST /api/tasks/resume/{id}` - 恢复任务

### 统计数据
- `GET /api/statistics/overview` - 系统概览
- `GET /api/statistics/device-pool` - 设备池统计
- `GET /api/statistics/account-library` - 账号库统计

## 配置文件

### application.yml
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/tt
    username: root
    password: Wumitech
  redis:
    host: localhost
    port: 6379

agent:
  ssh-timeout: 10000
  ssh-username: ubuntu
  ssh-port: 22
  # 跳板机配置
  ssh-jump-host: 106.75.152.136
  ssh-jump-port: 22
  ssh-jump-username: ubuntu
  # 目标服务器
  ssh-target-host: 10.7.107.224
  ssh-target-port: 22

---
# dev profile - SSH私钥配置
spring:
  config:
    activate:
      on-profile: dev

agent:
  ssh-private-key: |-
    -----BEGIN RSA PRIVATE KEY-----
    [私钥内容]
    -----END RSA PRIVATE KEY-----
```

## 启动方式

### 开发环境
```bash
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

### 生产环境
```bash
mvn clean package -DskipTests
java -jar target/cpa-manage-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

## 核心特性

### 1. SSH两段式连接
- ✅ 自动连接跳板机
- ✅ 通过跳板机执行远程命令
- ✅ 私钥认证
- ✅ 连接保活机制

### 2. 异步任务执行
- ✅ `nohup`后台执行
- ✅ 进程ID追踪
- ✅ 自动状态监控
- ✅ 日志实时读取

### 3. 智能日志解析
- ✅ 识别SUCCESS/FAILED记录
- ✅ 提取云手机名称
- ✅ 统计成功/失败数量
- ✅ 自动录入数据库

### 4. 定时任务调度
- ✅ 每日自动执行
- ✅ 可配置执行时间
- ✅ 任务暂停/恢复
- ✅ 执行历史记录

## 数据流转

### 云手机创建流程
```
1. API调用创建脚本
   ↓
2. SSH连接跳板机 (106.75.152.136)
   ↓
3. 在跳板机执行: ssh ubuntu@10.7.107.224 "nohup ./batch_create_phone.sh ..."
   ↓
4. 返回进程ID，启动异步监控
   ↓
5. 每分钟检查进程状态
   ↓
6. 完成后读取日志文件
   ↓
7. 解析云手机名称
   ↓
8. 插入 tt_account_data_outlook 表
```

### 账号注册流程
```
1. 从 tt_account_data_outlook 查询未注册设备
   ↓
2. 执行注册脚本
   ↓
3. 注册成功后
   ↓
4. 移动到 tt_account_data 表
   ↓
5. 开始养号流程
```

## 养号机制

### 养号天数
- `video_days` 字段记录观看视频的天数
- 每天执行一次视频观看脚本，`video_days + 1`

### 养号状态
- `0` - 未开始养号
- `1` - 养号中
- `2` - 养号完成
- `3` - 养号暂停

## 运维监控

### 日志查看
```bash
# 应用日志
tail -f logs/cpa-manage-api.log

# 远程脚本日志
curl "http://localhost:8080/api/scripts/test-parse-log?logFile=/tmp/batch_create_log_tt_107_224_20251011&lines=50"
```

### 健康检查
```bash
# SSH配置检查
curl "http://localhost:8080/api/scripts/test-ssh-config"

# 系统概览
curl "http://localhost:8080/api/statistics/overview"
```

## 注意事项

1. **SSH私钥安全**: 私钥内容在配置文件中，确保文件权限安全
2. **数据库连接**: 确保MySQL和Redis服务正常运行
3. **跳板机连接**: 确保跳板机SSH服务可访问
4. **脚本路径**: 确认远程服务器上的脚本路径正确
5. **日志清理**: 定期清理远程服务器上的日志文件

## 未来优化

- [ ] 添加Web前端界面
- [ ] 增强错误重试机制
- [ ] 实现脚本执行队列管理
- [ ] 添加账号健康度监控
- [ ] 支持多服务器管理
- [ ] 实现账号标签分类

## 技术亮点

1. **SSH跳板机自动化**: 创新使用两段式SSH连接，无需额外配置目标服务器
2. **异步任务监控**: 智能后台任务监控，自动处理长时间任务
3. **智能日志解析**: 自动识别和解析远程脚本执行结果
4. **两表分离设计**: 设备池和账号库分离，清晰的数据流转逻辑
5. **全自动化流程**: 从云手机创建到养号完成，全程自动化

---

**项目状态**: ✅ 核心功能已完成并测试通过
**最后更新**: 2025-10-14

