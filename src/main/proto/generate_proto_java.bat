@echo off

chcp 65001 >nul

REM Generate protobuf Java code

echo Generating protobuf Java code...

REM If protoc-gen-grpc-java is not in PATH, specify the plugin path
REM Example: --plugin=protoc-gen-grpc-java=path\to\protoc-gen-grpc-java.exe

cd /d %~dp0\..

echo.
echo ========================================
echo Generating api.proto Java code...
echo ========================================
echo.

REM Note: api.proto references proto/basic/basic.proto which may not exist
REM If compilation fails, you may need to:
REM 1. Create the missing proto files, or
REM 2. Comment out the import statements in api.proto, or  
REM 3. Use Maven plugin: mvn clean compile

protoc -I src/main/proto ^
  -I src/main/proto/google ^
  --java_out=src/main/java ^
  --grpc-java_out=src/main/java ^
  src/main/proto/api.proto

if %ERRORLEVEL% EQU 0 (
    echo.
    echo Success! api.proto compiled successfully.
    echo Generated files:
    echo   - src/main/java/api/*.java (Java classes based on proto package)
    echo   - src/main/java/api/*Grpc.java (gRPC service classes)
) else (
    echo.
    echo Warning: api.proto compilation failed!
    echo This might be due to missing dependency files:
    echo   - proto/basic/basic.proto (referenced but may not exist)
    echo   - proto/third_party/google/api/annotations.proto
    echo.
    echo Solutions:
    echo   1. Create the missing proto files in the correct directories
    echo   2. Adjust import paths in api.proto to match actual file locations
    echo   3. Use Maven plugin instead: mvn clean compile
    echo      (Maven plugin may handle dependencies better)
    echo.
    echo If protoc is not installed, please:
    echo   1. Download protoc compiler from:
    echo      https://github.com/protocolbuffers/protobuf/releases
    echo   2. For gRPC Java support:
    echo      - Download protoc-gen-grpc-java from:
    echo        https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/
    echo      - Or use Maven/Gradle plugins
    echo   3. Add protoc-gen-grpc-java to your PATH
    echo.
)

cd /d %~dp0

echo.
echo Done!
pause

