#!/bin/bash
# 在服务器上测试gRPC接口的脚本

echo "==================== 测试gRPC接口 ===================="
echo ""

# 测试1: 带完整参数
echo "测试1: 带完整参数（appId, sdk, countryCode）"
curl -s -X POST http://localhost:8081/api/grpc-test/get-fast-switch-json \
  -H "Content-Type: application/json" \
  -d '{"countryCode":"US","appId":"com.zhiliaoapp.musically","sdk":"33","needInstallApps":false}' | \
  python3 -m json.tool

echo ""
echo "==================== 测试完成 ===================="



