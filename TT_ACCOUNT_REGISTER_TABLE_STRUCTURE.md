# tt_account_register 表结构说明

## 推测的表结构

根据代码实现和用户提供的字段信息，推测的表结构如下：

```sql
CREATE TABLE `tt_account_register` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `phone_id` VARCHAR(100) COMMENT '云手机ID',
    `phone_server_ip` VARCHAR(50) COMMENT '云手机服务器IP',
    `email` VARCHAR(255) COMMENT '邮箱账号',
    `password` VARCHAR(255) COMMENT '密码',
    `username` VARCHAR(100) COMMENT '用户名',
    `nickname_behavior_result` VARCHAR(255) COMMENT '昵称行为结果',
    `created_at` DATETIME COMMENT '创建时间',
    `updated_at` DATETIME COMMENT '更新时间',
    INDEX `idx_phone_id` (`phone_id`),
    INDEX `idx_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TT账号注册记录表';
```

## 字段说明

| 字段名 | 类型 | 说明 | 来源 |
|--------|------|------|------|
| id | BIGINT | 主键，自增 | 自动生成 |
| phone_id | VARCHAR(100) | 云手机ID | 参数传入 |
| phone_server_ip | VARCHAR(50) | 云手机服务器IP | 参数传入 |
| email | VARCHAR(255) | 邮箱账号 | 从脚本输出解析 |
| password | VARCHAR(255) | 密码 | 从脚本输出解析 |
| username | VARCHAR(100) | 用户名 | 从脚本输出解析 |
| nickname_behavior_result | VARCHAR(255) | 昵称行为结果 | 从脚本输出解析 |
| created_at | DATETIME | 创建时间 | 系统时间 |
| updated_at | DATETIME | 更新时间 | 系统时间 |

## 脚本输出格式

脚本 `tiktok_register_us_test_account.py` 的输出格式应该是：

```
  email: test@example.com
  password: password123
  username: testuser
  nickname_behavior_result: result_value
```

## 数据库配置

**数据库地址**: `10.7.43.162:3306`
**数据库名称**: `tt`
**用户名**: `root`
**密码**: `Wumitech`

## 注意事项

1. 如果表结构不同，请提供实际的表结构，我会相应调整实体类
2. 如果字段名不同，请告知，我会修改 `@TableField` 注解
3. 如果缺少某些字段，实体类已包含，但不会插入
4. 如果有多余字段，实体类不包含，不影响

## 验证步骤

1. 请先执行以下SQL查看实际表结构：
```sql
DESC tt_account_register;
```

2. 或者执行以下SQL查看表结构定义：
```sql
SHOW CREATE TABLE tt_account_register;
```

3. 如果表结构与我推测的不同，请提供实际的表结构，我会调整代码。



