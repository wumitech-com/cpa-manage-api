FROM openjdk:17-slim

# 添加国内镜像源并设置时区为北京时间
RUN sed -i 's|http://deb.debian.org|http://mirrors.aliyun.com|g' /etc/apt/sources.list \
    && apt-get update && apt-get install -y \
    netcat-openbsd \
    tzdata \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && rm -rf /var/lib/apt/lists/*

# 设置工作目录
WORKDIR /app

# 复制JAR文件
COPY target/cpa-manage-api-0.0.1-SNAPSHOT.jar /app/cpa-manage-api.jar

# 创建日志目录
RUN mkdir -p /app/logs

# 暴露端口
EXPOSE 8081

# 设置时区环境变量
ENV TZ=Asia/Shanghai

# 设置JVM参数（可通过环境变量覆盖）
ENV JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Duser.timezone=Asia/Shanghai"

# 启动应用（支持JAVA_OPTS环境变量和Spring配置）
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar cpa-manage-api.jar --spring.profiles.active=prod"]

