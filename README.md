# CPA管理后台API

这是一个用于管理CPA脚本自动化的Java后台管理系统，支持云手机设备管理、账号注册、养号、批量操作等功能。

## 功能特性

### 核心功能
- **设备池管理**: 管理刚创建的云手机设备（outlook表）
- **账号库管理**: 管理已注册完成的账号（主表）
- **脚本自动化**: 支持创建云手机、注册、关注、刷视频等脚本自动执行
- **定时任务**: 每天8点自动执行注册、养号、业务任务
- **批量操作**: 支持批量注册、批量养号、批量关注等操作
- **数据统计**: 提供详细的统计数据和趋势分析

### 业务流程
```
创建云手机 → 设备池(outlook表) → 注册脚本 → 账号库(主表) → 养号脚本 → 业务脚本
```

## 技术栈

- **后端**: Spring Boot 2.7.18, MyBatis Plus, Redis
- **数据库**: MySQL 8.0
- **前端**: Bootstrap 5, JavaScript (原生)
- **构建工具**: Maven

## 项目结构

```
src/main/java/com/cpa/
├── CpaManageApiApplication.java          # 主启动类
├── entity/                               # 实体类
│   ├── TtAccountDataOutlook.java        # 设备池实体
│   └── TtAccountData.java               # 账号库实体
├── repository/                          # 数据访问层
│   ├── TtAccountDataOutlookRepository.java
│   └── TtAccountDataRepository.java
├── service/                             # 业务逻辑层
│   ├── DeviceService.java               # 设备管理服务
│   ├── ScriptService.java               # 脚本执行服务
│   ├── TaskSchedulerService.java        # 定时任务服务
│   └── StatisticsService.java           # 数据统计服务
└── controller/                          # 控制器层
    ├── DeviceController.java            # 设备管理控制器
    ├── ScriptController.java            # 脚本执行控制器
    ├── TaskController.java              # 任务管理控制器
    └── StatisticsController.java        # 统计控制器

src/main/resources/
├── application.yml                      # 配置文件
├── static/
│   ├── index.html                      # 主页面
│   └── js/app.js                       # 前端脚本
└── README.md                           # 说明文档
```

## 快速开始

### 1. 环境要求
- JDK 8+
- MySQL 8.0+
- Redis 6.0+
- Maven 3.6+

### 2. 数据库配置
确保MySQL中已存在以下表：
- `tt_account_data_outlook` (设备池表)
- `tt_account_data` (账号库表)

### 3. 配置文件
修改 `src/main/resources/application.yml` 中的数据库和Redis连接信息：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/your_database?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
    username: your_username
    password: your_password
  redis:
    host: localhost
    port: 6379
    password: your_redis_password
```

### 4. 启动应用
```bash
# 编译项目
mvn clean compile

# 启动应用
mvn spring-boot:run
```

### 5. 访问系统
打开浏览器访问: http://localhost:8080

## API接口文档

### 设备管理接口

#### 获取设备池列表
```
GET /api/devices/pool?pageNum=1&pageSize=10&country=US&status=0
```

#### 获取账号库列表
```
GET /api/devices/accounts?pageNum=1&pageSize=10&country=US&status=0&nurtureStatus=0
```

#### 获取需要注册的设备
```
GET /api/devices/need-register
```

#### 获取需要养号的账号
```
GET /api/devices/need-nurture
```

### 脚本执行接口

#### 执行创建云手机脚本
```
POST /api/scripts/create-phone
Content-Type: application/json

{
  "country": "US",
  "pkgName": "com.zhiliaoapp.musically",
  "count": 10
}
```

#### 批量执行注册脚本
```
POST /api/scripts/register
Content-Type: application/json

{
  "deviceIds": [1, 2, 3, 4, 5]
}
```

#### 批量执行关注脚本
```
POST /api/scripts/follow
Content-Type: application/json

{
  "accountIds": [1, 2, 3],
  "targetUsername": "target_user"
}
```

#### 批量执行刷视频脚本
```
POST /api/scripts/watch-video
Content-Type: application/json

{
  "accountIds": [1, 2, 3, 4, 5]
}
```

### 任务管理接口

#### 手动触发每日任务
```
POST /api/tasks/daily
```

#### 获取任务调度状态
```
GET /api/tasks/schedule
```

### 数据统计接口

#### 获取整体统计概览
```
GET /api/statistics/overview
```

#### 获取设备池统计
```
GET /api/statistics/device-pool
```

#### 获取账号库统计
```
GET /api/statistics/account-library
```

## 使用说明

### 1. 设备管理
- **设备池**: 查看刚创建的云手机设备，执行注册操作
- **账号库**: 查看已注册的账号，执行养号和业务操作
- **批量操作**: 支持批量选择设备进行统一操作

### 2. 脚本执行
- **创建云手机**: 批量创建新的云手机设备
- **注册脚本**: 为设备注册outlook邮箱和TT账号
- **关注脚本**: 批量关注指定用户
- **刷视频脚本**: 执行养号操作，增加刷视频天数

### 3. 定时任务
- **每日任务**: 每天8点自动执行注册、养号、业务任务
- **手动触发**: 支持手动触发每日任务
- **任务监控**: 实时查看任务执行状态

### 4. 数据统计
- **整体概览**: 查看设备总数、账号总数、转化率等关键指标
- **分类统计**: 按国家、包名、状态等维度统计
- **趋势分析**: 查看最近7天的数据变化趋势

## 注意事项

1. **脚本路径**: 需要确保服务器上存在对应的脚本文件，并修改 `ScriptService` 中的脚本路径
2. **数据库权限**: 确保数据库用户有足够的权限执行增删改查操作
3. **Redis连接**: 确保Redis服务正常运行，用于缓存任务状态
4. **定时任务**: 定时任务默认每天8点执行，可根据需要调整时间
5. **批量操作**: 批量操作有数量限制，避免对服务器造成过大压力

## 扩展开发

### 添加新的脚本类型
1. 在 `ScriptService` 中添加新的脚本类型常量
2. 实现对应的脚本执行方法
3. 在 `ScriptController` 中添加对应的接口
4. 在前端页面中添加相应的操作按钮

### 添加新的统计维度
1. 在 `StatisticsService` 中实现新的统计方法
2. 在 `StatisticsController` 中添加对应的接口
3. 在前端页面中展示新的统计数据

### 自定义定时任务
1. 在 `TaskSchedulerService` 中添加新的定时任务方法
2. 使用 `@Scheduled` 注解配置执行时间
3. 在 `TaskController` 中添加手动触发接口

## 故障排除

### 常见问题
1. **数据库连接失败**: 检查数据库配置和网络连接
2. **Redis连接失败**: 检查Redis服务状态和连接配置
3. **脚本执行失败**: 检查脚本文件路径和权限
4. **定时任务不执行**: 检查 `@EnableScheduling` 注解和cron表达式

### 日志查看
应用日志会输出到控制台，包含详细的执行信息和错误信息，便于问题排查。

## 许可证

本项目采用 MIT 许可证。
