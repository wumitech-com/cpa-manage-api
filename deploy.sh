#!/bin/bash
# 部署脚本 - 编译、传输和停止旧进程
# 用户需要手动执行启动步骤

set -e

PRIVATE_KEY="$HOME/.ssh/id_rsa"
JUMP_HOST="ubuntu@106.75.152.136"
TARGET_HOST="ubuntu@10.13.135.74"
APP_DIR="/home/ubuntu/cpa-manage-api"

echo "=== 开始部署流程 ==="

# 步骤1: 编译项目
echo ""
echo "[1/5] 编译项目..."
mvn clean package -DskipTests
if [ $? -ne 0 ]; then
    echo "编译失败！"
    exit 1
fi
echo "编译成功！"

# 步骤2: 传输jar文件到跳板机
echo ""
echo "[2/5] 传输jar文件到跳板机..."
scp -i "$PRIVATE_KEY" -o StrictHostKeyChecking=no target/cpa-manage-api-0.0.1-SNAPSHOT.jar ${JUMP_HOST}:/tmp/cpa-manage-api-0.0.1-SNAPSHOT.jar
if [ $? -ne 0 ]; then
    echo "传输到跳板机失败！"
    exit 1
fi
echo "传输到跳板机成功！"

# 步骤3: 从跳板机传输到目标服务器
echo ""
echo "[3/5] 从跳板机传输到目标服务器..."
ssh -i "$PRIVATE_KEY" -o StrictHostKeyChecking=no $JUMP_HOST "scp -o StrictHostKeyChecking=no /tmp/cpa-manage-api-0.0.1-SNAPSHOT.jar ${TARGET_HOST}:/tmp/"
if [ $? -ne 0 ]; then
    echo "传输到目标服务器失败！"
    exit 1
fi
echo "传输到目标服务器成功！"

# 步骤4: 停止旧进程
echo ""
echo "[4/5] 停止旧进程..."
ssh -i "$PRIVATE_KEY" -o StrictHostKeyChecking=no $JUMP_HOST "ssh -o StrictHostKeyChecking=no $TARGET_HOST 'cd $APP_DIR && ps aux | grep java | grep cpa-manage-api | grep -v grep | awk \"{print \\\$2}\" | xargs kill -9 2>/dev/null || sudo pkill -f cpa-manage-api || true'"
echo "旧进程已停止"

# 步骤5: 复制jar文件到应用目录
echo ""
echo "[5/5] 复制jar文件到应用目录..."
ssh -i "$PRIVATE_KEY" -o StrictHostKeyChecking=no $JUMP_HOST "ssh -o StrictHostKeyChecking=no $TARGET_HOST 'cp /tmp/cpa-manage-api-0.0.1-SNAPSHOT.jar $APP_DIR/cpa-manage-api-0.0.1-SNAPSHOT.jar'"
if [ $? -ne 0 ]; then
    echo "复制jar文件失败！"
    exit 1
fi
echo "复制jar文件成功！"

echo ""
echo "=== 部署准备完成 ==="
echo "请手动执行以下命令启动应用："
echo "  ssh -i $PRIVATE_KEY $JUMP_HOST"
echo "  ssh $TARGET_HOST"
echo "  cd $APP_DIR"
echo "  nohup java -jar cpa-manage-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod > app.log 2>&1 &"
echo "  或者使用Java 17的完整路径："
echo "  nohup /usr/lib/jvm/java-17-openjdk-arm64/bin/java -jar cpa-manage-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod > app.log 2>&1 &"
