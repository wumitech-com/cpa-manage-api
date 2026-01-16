# Proto文件说明

## 文件结构

- `phone_center.proto` - PhoneCenter服务的proto定义
- `generate_proto_java.bat` - Windows下生成Java代码的脚本
- `google/` - Google API的proto文件（用于import）

## 使用步骤

### 方法1: 使用Maven插件自动编译（推荐）

运行Maven编译命令，会自动编译proto文件：

```bash
mvn clean compile
```

编译后的Java类会生成在 `src/main/java/phone_center/` 目录下。

### 方法2: 使用批处理脚本手动编译

1. 确保已安装 `protoc` 编译器
2. 确保已安装 `protoc-gen-grpc-java` 插件
3. 运行脚本：

```bash
cd src/main/proto
generate_proto_java.bat
```

## 编译后的类

编译成功后，会生成以下Java类：

- `phone_center.PhoneCenterGrpc` - gRPC服务存根类
- `phone_center.PhoneCenterProto.GetFastSwitchJsonRequest` - 请求类
- `phone_center.PhoneCenterProto.GetFastSwitchJsonResponse` - 响应类
- `phone_center.PhoneCenterProto.Atom` - Atom消息类
- 其他相关消息类

## 配置gRPC服务器地址

在 `application.yml` 或 `application-dev.yml` 中配置：

```yaml
grpc:
  phone-center:
    host: your-grpc-server-host  # gRPC服务器地址
    port: 50051                  # gRPC服务器端口
```

## 使用说明

编译proto文件后，需要：

1. 取消注释 `PhoneCenterGrpcService.java` 中的代码
2. 添加正确的import语句：
   ```java
   import phone_center.PhoneCenterGrpc;
   import phone_center.PhoneCenterProto.*;
   ```
3. 根据实际情况调整参数（sdk、countryCode、appId等）



