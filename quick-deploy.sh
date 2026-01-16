#!/bin/bash
docker load -i /tmp/cpa-manage-api-image.tar
rm -f /tmp/cpa-manage-api-image.tar
docker run -d --name cpa-manage-api --restart unless-stopped --network host \
  -v /home/ubuntu/cpa-manage-api/logs:/app/logs \
  -e TZ=Asia/Shanghai \
  -e JAVA_OPTS='-Xms512m -Xmx2048m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Duser.timezone=Asia/Shanghai' \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e MAINBOARD_PUBLIC_IP=206.119.108.2 \
  -e MAINBOARD_APPIUM_SERVER=10.7.124.25 \
  uhub.service.ucloud.cn/wumitech.public/cpa-manage-api:latest

