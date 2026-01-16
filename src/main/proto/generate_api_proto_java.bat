@echo off

chcp 65001 >nul

REM Generate protobuf Java code for api.proto

echo Generating api.proto Java code...

cd /d %~dp0\..

echo.
echo ========================================
echo Generating api.proto Java code...
echo ========================================
echo.

REM Note: api.proto has the following imports:
REM   - proto/basic/basic.proto (may need to be created)
REM   - proto/third_party/google/api/annotations.proto
REM 
REM The import paths in api.proto use "proto/" prefix, but the actual files
REM are in src/main/proto directory. We need to adjust the import paths.
REM 
REM Since proto/third_party/google/api/annotations.proto doesn't exist exactly,
REM but google/api/annotations.proto does, we may need to create a symlink or
REM adjust the import paths.

REM First, try to compile with current structure
echo Attempting to compile api.proto...
echo.

protoc -I src/main/proto ^
  -I src/main/proto/google ^
  --java_out=src/main/java ^
  --grpc-java_out=src/main/java ^
  src/main/proto/api.proto

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================
    echo Success! api.proto compiled successfully.
    echo ========================================
    echo.
    echo Generated files should be in:
    echo   - src/main/java/api/*.java (Java message classes)
    echo   - src/main/java/api/ApiServiceGrpc.java (gRPC service classes)
    echo.
) else (
    echo.
    echo ========================================
    echo Compilation failed!
    echo ========================================
    echo.
    echo Common issues:
    echo   1. Missing dependency: proto/basic/basic.proto
    echo      - This file is referenced in api.proto but may not exist
    echo      - You may need to create it or comment out the import
    echo.
    echo   2. Import path mismatch: proto/third_party/google/api/annotations.proto
    echo      - The actual file is at: google/api/annotations.proto
    echo      - You may need to adjust the import path in api.proto
    echo.
    echo Solutions:
    echo   1. Recommended: Use Maven plugin instead
    echo      mvn clean compile
    echo      (Maven plugin handles dependencies automatically)
    echo.
    echo   2. Create missing proto files:
    echo      - Create src/main/proto/proto/basic/basic.proto (if needed)
    echo      - Or adjust import paths in api.proto
    echo.
    echo   3. Ensure protoc and protoc-gen-grpc-java are installed:
    echo      - Download protoc: https://github.com/protocolbuffers/protobuf/releases
    echo      - Download protoc-gen-grpc-java: 
    echo        https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/
    echo.
)

cd /d %~dp0

echo.
echo Done!
pause



