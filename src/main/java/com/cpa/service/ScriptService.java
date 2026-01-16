package com.cpa.service;

import com.cpa.config.SshProperties;
import com.cpa.entity.TtAccountData;
import com.cpa.entity.TtAccountDataOutlook;
import com.cpa.util.SshUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 脚本执行服务
 */
@Slf4j
@Service("scriptService")
@RequiredArgsConstructor
public class ScriptService {

    private final DeviceService deviceService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final SshProperties sshProperties;

    // 脚本类型常量
    public static final String SCRIPT_TYPE_CREATE_PHONE = "create_phone";
    public static final String SCRIPT_TYPE_REGISTER = "register";
    public static final String SCRIPT_TYPE_FOLLOW = "follow";
    public static final String SCRIPT_TYPE_WATCH_VIDEO = "watch_video";
    public static final String SCRIPT_TYPE_UPLOAD = "upload";
    public static final String SCRIPT_TYPE_EDIT_BIO = "edit_bio";

    /**
     * 执行创建云手机脚本
     * 示例: ./batch_create_phone.sh tt_138_97 GB 5
     * 云手机名称格式: tt_138_97_1_GB_20250918
     */
    public Map<String, Object> executeCreatePhoneScript(Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("开始执行创建云手机脚本，参数: {}", params);
            
            // 获取参数
            String phonePrefix = (String) params.getOrDefault("phonePrefix", "tt_107_224");
            String country = (String) params.getOrDefault("country", "US");
            Integer count = (Integer) params.getOrDefault("count", 1);
            String serverHost = (String) params.getOrDefault("serverHost", "10.7.107.224");
            String scriptPath = (String) params.getOrDefault("scriptPath", "./batch_create_phone.sh");
            
            // 构建执行命令
            String command = String.format("%s %s %s %d", scriptPath, phonePrefix, country, count);
            
            log.info("执行云手机创建命令: {}", command);
            
            // 调用实际的SSH脚本执行逻辑
            Map<String, Object> executeResult = executeRemoteScript(serverHost, command, params);
            
            if ((Boolean) executeResult.getOrDefault("success", false)) {
                // 脚本执行成功，解析返回的云手机名称并创建设备记录
                @SuppressWarnings("unchecked")
                List<String> phoneNames = (List<String>) executeResult.get("phoneNames");
                
                List<TtAccountDataOutlook> createdDevices = new ArrayList<>();
                
                if (phoneNames != null && !phoneNames.isEmpty()) {
                    for (String phoneName : phoneNames) {
                        TtAccountDataOutlook device = new TtAccountDataOutlook();
                        device.setPhoneId(phoneName);
                        device.setPhoneServerId(serverHost);
                        device.setCountry(country);
                        device.setPkgName((String) params.getOrDefault("pkgName", "com.zhiliaoapp.musically"));
                        device.setStatus(0);
                        
                        boolean addResult = deviceService.addDeviceToPool(device);
                        if (addResult) {
                            createdDevices.add(device);
                        }
                    }
                }
                
                result.put("success", true);
                result.put("message", String.format("成功创建 %d 个云手机", createdDevices.size()));
                result.put("devices", createdDevices);
                result.put("count", createdDevices.size());
            } else {
                result.put("success", false);
                result.put("message", "脚本执行失败: " + executeResult.get("message"));
            }
            
        } catch (Exception e) {
            log.error("执行创建云手机脚本失败", e);
            result.put("success", false);
            result.put("message", "执行失败: " + e.getMessage());
        }
        
        return result;
    }

    // 注册脚本的配置信息
    private static final String REGISTER_TARGET_HOST = "10.13.55.85";
    private static final String REGISTER_SCRIPT_PATH = "/data/appium/com_tiktok_lite_go/sunxy/TTTest_batch_register_tiktok_3group.py";
    private static final String REGISTER_LOG_FILE = "/tmp/register_script.log";
    private static final String REGISTER_STATUS_KEY = "register_script_status";

    // 编辑Bio脚本的配置信息
    private static final String EDIT_BIO_TARGET_HOST = "10.13.55.85";
    private static final String EDIT_BIO_SCRIPT_PATH = "/data/appium/com_zhiliaoapp_musically/zl/TTTest_edit_prodile_rolling.py";
    private static final String EDIT_BIO_LOG_FILE = "/tmp/edit_bio_script.log";
    private static final String EDIT_BIO_STATUS_KEY = "edit_bio_script_status";

    /**
     * 执行注册脚本
     * 直接在目标服务器上执行指定的Python脚本
     */
    public Map<String, Object> executeRegisterScript() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 检查是否已有脚本在运行 - 使用ps命令直接检查
            log.info("检查是否已有注册脚本在运行...");
            String checkCommand = String.format("ps -ef | grep '%s' | grep -v grep", REGISTER_SCRIPT_PATH);
            SshUtil.SshResult checkResult = SshUtil.executeCommandWithPrivateKey(
                REGISTER_TARGET_HOST,
                22,
                "root",
                sshProperties.getSshPrivateKey(),
                sshProperties.getSshPassphrase(),
                checkCommand,
                sshProperties.getSshTimeout(),
                sshProperties.getSshJumpHost(),
                sshProperties.getSshJumpPort(),
                sshProperties.getSshJumpUsername(),
                sshProperties.getSshJumpPassword()
            );
            
            if (checkResult.isSuccess() && checkResult.getOutput() != null && !checkResult.getOutput().trim().isEmpty()) {
                // 找到运行中的进程
                String[] lines = checkResult.getOutput().trim().split("\n");
                List<String> runningPids = new ArrayList<>();
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            runningPids.add(parts[1]);
                        }
                    }
                }
                
                if (!runningPids.isEmpty()) {
                    log.warn("已有 {} 个注册脚本在运行中，PID: {}", runningPids.size(), String.join(",", runningPids));
                    result.put("success", false);
                    result.put("message", String.format("已有 %d 个注册脚本在运行中，请等待完成或检查状态", runningPids.size()));
                    result.put("pid", String.join(",", runningPids));
                    result.put("count", runningPids.size());
                    return result;
                }
            }
            
            log.info("未发现运行中的注册脚本，可以执行新任务");
            
            // 检查Redis中是否有旧状态，如果有且已完成，清除它
            @SuppressWarnings("unchecked")
            Map<String, Object> existingStatus = (Map<String, Object>) redisTemplate.opsForValue().get(REGISTER_STATUS_KEY);
            if (existingStatus != null) {
                String oldStatus = (String) existingStatus.get("status");
                if ("completed".equals(oldStatus) || "error".equals(oldStatus) || "timeout".equals(oldStatus)) {
                    log.info("清除旧的脚本状态: {}", oldStatus);
                    redisTemplate.delete(REGISTER_STATUS_KEY);
                }
            }
            
            // 准备命令 - 使用一个bash脚本来启动进程并获取PID
            String command = String.format("python3 %s", REGISTER_SCRIPT_PATH);
            
            // 使用bash脚本：启动进程，获取PID，将日志重定向到以PID命名的文件
            String execCommand = String.format(
                "bash -c 'nohup %s >/tmp/register_temp_$$.log 2>&1 & PID=$!; sleep 0.5; mv /tmp/register_temp_$$.log %s_${PID}.log 2>/dev/null; echo $PID'",
                command, REGISTER_LOG_FILE
            );
            
            log.info("开始执行注册脚本 - 目标服务器: {}, 脚本: {}", REGISTER_TARGET_HOST, REGISTER_SCRIPT_PATH);
            
            // 通过跳板机执行命令获取PID
            SshUtil.SshResult sshResult = SshUtil.executeCommandWithPrivateKey(
                REGISTER_TARGET_HOST,
                22,  // 默认SSH端口
                "root",  // 使用root用户
                sshProperties.getSshPrivateKey(),
                sshProperties.getSshPassphrase(),
                execCommand,
                sshProperties.getSshTimeout(),
                sshProperties.getSshJumpHost(),
                sshProperties.getSshJumpPort(),
                sshProperties.getSshJumpUsername(),
                sshProperties.getSshJumpPassword()
            );
            
            if (sshResult.isSuccess()) {
                String pid = sshResult.getOutput().trim();
                if (pid == null || pid.isEmpty()) {
                    log.error("未能获取到进程ID");
                    result.put("success", false);
                    result.put("message", "脚本启动失败：未能获取进程ID");
                    return result;
                }
                
                log.info("注册脚本已在后台执行，进程ID: {}", pid);
                
                // 使用PID作为日志文件后缀
                String logFile = String.format("%s_%s.log", REGISTER_LOG_FILE, pid);
                
                // 将进程信息保存到Redis，用于状态查询
                Map<String, Object> status = new HashMap<>();
                status.put("pid", pid);
                status.put("startTime", LocalDateTime.now().toString());
                status.put("status", "running");
                status.put("logFile", logFile);
                status.put("targetHost", REGISTER_TARGET_HOST);
                redisTemplate.opsForValue().set(REGISTER_STATUS_KEY, status);
                
                // 启动异步监控
                startRegisterMonitor(pid, logFile);
                
                result.put("success", true);
                result.put("message", "注册脚本已提交执行");
                result.put("pid", pid);
                result.put("logFile", logFile);
                
            } else {
                log.error("提交注册脚本失败: {}", sshResult.getErrorMessage());
                result.put("success", false);
                result.put("message", "提交脚本失败: " + sshResult.getErrorMessage());
            }
            
        } catch (Exception e) {
            log.error("执行注册脚本失败", e);
            result.put("success", false);
            result.put("message", "执行失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 启动注册脚本监控
     * 定期检查脚本状态并更新Redis中的状态信息
     */
    private void startRegisterMonitor(String pid, String logFile) {
        new Thread(() -> {
            try {
                log.info("启动注册脚本监控，进程ID: {}", pid);
                
                int checkCount = 0;
                int maxChecks = 720; // 最多检查720次（每分钟检查一次，最多12小时）
                
                while (checkCount < maxChecks) {
                    // 等待1分钟后检查
                    Thread.sleep(60000); // 60秒
                    checkCount++;
                    
                    // 检查进程是否还在运行
                    boolean isRunning = SshUtil.isProcessRunning(
                        REGISTER_TARGET_HOST,
                        22,
                        "root",
                        sshProperties.getSshPrivateKey(),
                        sshProperties.getSshPassphrase(),
                        pid,
                        sshProperties.getSshTimeout(),
                        sshProperties.getSshJumpHost(),
                        sshProperties.getSshJumpPort(),
                        sshProperties.getSshJumpUsername(),
                        sshProperties.getSshJumpPassword()
                    );
                    
                    if (!isRunning) {
                        log.info("注册脚本已完成，进程ID: {}", pid);
                        
                        // 读取最终日志
                        String logContent = SshUtil.readRemoteLog(
                            REGISTER_TARGET_HOST,
                            22,
                            "root",
                            sshProperties.getSshPrivateKey(),
                            sshProperties.getSshPassphrase(),
                            logFile,
                            0,
                            sshProperties.getSshTimeout(),
                            sshProperties.getSshJumpHost(),
                            sshProperties.getSshJumpPort(),
                            sshProperties.getSshJumpUsername(),
                            sshProperties.getSshJumpPassword()
                        );
                        
                        // 更新Redis状态（只存最后100行摘要）
                        Map<String, Object> status = new HashMap<>();
                        status.put("pid", pid);
                        status.put("status", "completed");
                        status.put("endTime", LocalDateTime.now().toString());
                        status.put("logFile", logFile);
                        // 只存最后100行摘要，避免数据过大
                        status.put("logSummary", getLastNLines(logContent, 100));
                        status.put("totalLogLines", logContent != null ? logContent.split("\n").length : 0);
                        status.put("targetHost", REGISTER_TARGET_HOST);
                        redisTemplate.opsForValue().set(REGISTER_STATUS_KEY, status, 24, TimeUnit.HOURS); // 保留24小时
                        
                        break;
                    } else {
                        // 更新运行状态
                        @SuppressWarnings("unchecked")
                        Map<String, Object> status = (Map<String, Object>) redisTemplate.opsForValue().get(REGISTER_STATUS_KEY);
                        if (status != null) {
                            status.put("lastCheck", LocalDateTime.now().toString());
                            status.put("runningTime", checkCount);
                            redisTemplate.opsForValue().set(REGISTER_STATUS_KEY, status);
                        }
                        
                        log.debug("注册脚本运行中，进程ID: {}, 已运行 {} 分钟", pid, checkCount);
                    }
                }
                
                if (checkCount >= maxChecks) {
                    log.error("注册脚本监控超时，进程ID: {}", pid);
                    Map<String, Object> status = new HashMap<>();
                    status.put("pid", pid);
                    status.put("status", "timeout");
                    status.put("endTime", LocalDateTime.now().toString());
                    status.put("logFile", logFile);
                    status.put("targetHost", REGISTER_TARGET_HOST);
                    redisTemplate.opsForValue().set(REGISTER_STATUS_KEY, status, 24, TimeUnit.HOURS);
                }
                
            } catch (Exception e) {
                log.error("注册脚本监控失败", e);
                Map<String, Object> status = new HashMap<>();
                status.put("pid", pid);
                status.put("status", "error");
                status.put("error", e.getMessage());
                redisTemplate.opsForValue().set(REGISTER_STATUS_KEY, status);
            }
        }).start();
    }

    /**
     * 查询注册脚本执行状态
     */
    public Map<String, Object> getRegisterScriptStatus() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info(">>> 步骤1: 开始查询注册脚本状态");
            // 从Redis获取进程信息
            @SuppressWarnings("unchecked")
            Map<String, Object> status = (Map<String, Object>) redisTemplate.opsForValue().get(REGISTER_STATUS_KEY);
            log.info(">>> 步骤2: Redis查询完成，status={}", status == null ? "null" : status.get("status"));
            
            if (status == null) {
                log.info(">>> Redis中无数据，直接返回not_running");
                result.put("success", true);
                result.put("status", "not_running");
                result.put("message", "注册脚本未在运行");
                result.put("logContent", "");
                result.put("pid", "");
                result.put("logFile", "");
                result.put("startTime", null);
                return result;
            }
            
            String pid = (String) status.get("pid");
            String logFile = (String) status.get("logFile");
            String targetHost = (String) status.get("targetHost");
            String currentStatus = (String) status.get("status");
            log.info(">>> 步骤3: 当前状态={}, pid={}, targetHost={}", currentStatus, pid, targetHost);
            
            // 始终检查进程是否还在运行 - 完全依赖ps命令查找（不管Redis中的状态）
            boolean isRunning = false;
            String actualPid = pid;
            List<String> runningPids = new ArrayList<>();
            
            // 通过脚本路径查找所有运行中的进程
            log.info(">>> 步骤4: 开始SSH检查进程，目标主机={}", targetHost);
            String checkCommand = String.format("ps -ef | grep '%s' | grep -v grep", REGISTER_SCRIPT_PATH);
            SshUtil.SshResult checkResult = SshUtil.executeCommandWithPrivateKey(
                targetHost,
                22,
                "root",
                sshProperties.getSshPrivateKey(),
                sshProperties.getSshPassphrase(),
                checkCommand,
                sshProperties.getSshTimeout(),
                sshProperties.getSshJumpHost(),
                sshProperties.getSshJumpPort(),
                sshProperties.getSshJumpUsername(),
                sshProperties.getSshJumpPassword()
            );
            log.info(">>> 步骤5: SSH进程检查完成，成功={}", checkResult.isSuccess());
            
            log.debug("检查注册脚本进程，命令输出: {}", checkResult.getOutput());
            
            if (checkResult.isSuccess() && checkResult.getOutput() != null && !checkResult.getOutput().trim().isEmpty()) {
                // 解析ps输出，提取所有PID
                String[] lines = checkResult.getOutput().trim().split("\n");
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        // ps -ef 输出格式: UID PID PPID C STIME TTY TIME CMD
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            runningPids.add(parts[1]); // 第二列是PID
                        }
                    }
                }
                
                if (!runningPids.isEmpty()) {
                    isRunning = true;
                    actualPid = String.join(",", runningPids); // 显示所有运行的PID
                    log.info("找到 {} 个运行中的注册脚本进程，PID: {}", runningPids.size(), actualPid);
                } else {
                    log.info("未找到运行中的注册脚本进程");
                }
            } else {
                log.info("未找到运行中的注册脚本进程（命令无输出）");
            }
            
            // 根据PID确定日志文件名
            if (isRunning && !runningPids.isEmpty()) {
                // 使用第一个PID来确定日志文件
                String firstPid = runningPids.get(0);
                String calculatedLogFile = String.format("%s_%s.log", REGISTER_LOG_FILE, firstPid);
                
                // 如果日志文件路径不同，更新它
                if (!calculatedLogFile.equals(logFile)) {
                    log.info("更新日志文件路径，PID: {}, 日志文件: {}", firstPid, calculatedLogFile);
                    logFile = calculatedLogFile;
                    
                    // 更新Redis中的信息
                    status.put("logFile", logFile);
                    status.put("status", "running");
                    status.put("pid", actualPid);
                    if (status.get("startTime") == null) {
                        status.put("startTime", LocalDateTime.now().toString());
                    }
                    status.put("targetHost", REGISTER_TARGET_HOST);
                    redisTemplate.opsForValue().set(REGISTER_STATUS_KEY, status);
                }
            }
            
            // 读取最新的日志内容（最后100行）
            log.info(">>> 步骤6: 开始读取远程日志，logFile={}", logFile);
            String logContent = "";
            if (logFile != null && !logFile.isEmpty()) {
                logContent = SshUtil.readRemoteLog(
                    targetHost,
                    22,
                    "root",
                    sshProperties.getSshPrivateKey(),
                    sshProperties.getSshPassphrase(),
                    logFile,
                    100,  // 最后100行
                    sshProperties.getSshTimeout(),
                    sshProperties.getSshJumpHost(),
                    sshProperties.getSshJumpPort(),
                    sshProperties.getSshJumpUsername(),
                    sshProperties.getSshJumpPassword()
                );
                log.info(">>> 步骤7: 远程日志读取完成，内容长度={}", logContent.length());
            }
            
            log.info(">>> 步骤8: 准备返回结果，isRunning={}", isRunning);
            result.put("success", true);
            result.put("status", isRunning ? "running" : "completed");
            result.put("message", isRunning ? "注册脚本正在运行" : "注册脚本已完成");
            result.put("pid", actualPid); // 使用实际找到的PID
            result.put("logFile", logFile);
            result.put("logContent", logContent);
            result.put("startTime", status.get("startTime"));
            result.put("runningTime", status.get("runningTime"));
            
            // 如果脚本已完成，更新Redis状态但不删除（保留24小时）
            if (!isRunning && "running".equals(currentStatus)) {
                status.put("status", "completed");
                status.put("endTime", LocalDateTime.now().toString());
                // 只存最后100行摘要
                status.put("logSummary", getLastNLines(logContent, 100));
                status.put("totalLogLines", logContent != null ? logContent.split("\n").length : 0);
                status.put("pid", actualPid); // 更新实际的PID
                redisTemplate.opsForValue().set(REGISTER_STATUS_KEY, status, 24, TimeUnit.HOURS);
            } else if (isRunning && (pid == null || pid.isEmpty()) && actualPid != null && !actualPid.isEmpty()) {
                // 如果之前没有PID，现在找到了，更新Redis
                status.put("pid", actualPid);
                redisTemplate.opsForValue().set(REGISTER_STATUS_KEY, status);
            }
            
        } catch (Exception e) {
            log.error(">>> 异常: 查询注册脚本状态失败", e);
            result.put("success", false);
            result.put("message", "查询失败: " + e.getMessage());
        }
        
        log.info(">>> 步骤9: 方法执行完成，准备返回");
        return result;
    }
    
    private String getStatusMessage(String status) {
        switch (status) {
            case "running": return "注册脚本正在运行";
            case "completed": return "注册脚本已完成";
            case "error": return "注册脚本执行出错";
            case "timeout": return "注册脚本执行超时";
            default: return "未知状态";
        }
    }
    
    /**
     * 获取字符串的最后N行
     * @param content 完整内容
     * @param lines 行数
     * @return 最后N行内容
     */
    private String getLastNLines(String content, int lines) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        
        String[] allLines = content.split("\n");
        if (allLines.length <= lines) {
            return content;
        }
        
        // 只取最后N行
        StringBuilder result = new StringBuilder();
        int startIndex = allLines.length - lines;
        for (int i = startIndex; i < allLines.length; i++) {
            result.append(allLines[i]).append("\n");
        }
        
        return result.toString();
    }

    /**
     * 执行编辑Bio脚本
     * 脚本路径: /data/appium/com_zhiliaoapp_musically/zl/TTTest_edit_prodile_rolling.py
     * 在服务器 10.13.55.85 上执行
     * 参数: python TTTest_edit_prodile_rolling.py 10.7.107.224 com.zhiliaoapp.musically
     */
    public Map<String, Object> executeEditBioScript(Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 检查是否已有脚本在运行
            log.info("检查是否已有编辑Bio脚本在运行...");
            String checkCommand = String.format("ps -ef | grep '%s' | grep -v grep", EDIT_BIO_SCRIPT_PATH);
            SshUtil.SshResult checkResult = SshUtil.executeCommandWithPrivateKey(
                EDIT_BIO_TARGET_HOST,
                22,
                "root",
                sshProperties.getSshPrivateKey(),
                sshProperties.getSshPassphrase(),
                checkCommand,
                sshProperties.getSshTimeout(),
                sshProperties.getSshJumpHost(),
                sshProperties.getSshJumpPort(),
                sshProperties.getSshJumpUsername(),
                sshProperties.getSshJumpPassword()
            );
            
            if (checkResult.isSuccess() && checkResult.getOutput() != null && !checkResult.getOutput().trim().isEmpty()) {
                String[] lines = checkResult.getOutput().trim().split("\n");
                List<String> runningPids = new ArrayList<>();
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            runningPids.add(parts[1]);
                        }
                    }
                }
                
                if (!runningPids.isEmpty()) {
                    log.warn("已有 {} 个编辑Bio脚本在运行中，PID: {}", runningPids.size(), String.join(",", runningPids));
                    result.put("success", false);
                    result.put("message", String.format("已有 %d 个编辑Bio脚本在运行中，请等待完成", runningPids.size()));
                    result.put("pid", String.join(",", runningPids));
                    return result;
                }
            }
            
            log.info("未发现运行中的编辑Bio脚本，可以执行新任务");
            
            // 获取脚本参数
            String targetServerHost = (String) params.getOrDefault("serverHost", "10.7.107.224");
            String pkgName = (String) params.getOrDefault("pkgName", "com.tiktok.lite.go");
            
            // 构建执行命令 - 脚本参数是目标服务器地址和包名
            String command = String.format("python3 %s %s %s", EDIT_BIO_SCRIPT_PATH, targetServerHost, pkgName);
            
            // 使用bash脚本：启动进程，获取PID，将日志重定向到以PID命名的文件
            String execCommand = String.format(
                "bash -c 'nohup %s >/tmp/edit_bio_temp_$$.log 2>&1 & PID=$!; sleep 0.5; mv /tmp/edit_bio_temp_$$.log %s_${PID}.log 2>/dev/null; echo $PID'",
                command, EDIT_BIO_LOG_FILE
            );
            
            log.info("开始执行编辑Bio脚本 - 目标服务器: {}, 脚本参数: {} {}", EDIT_BIO_TARGET_HOST, targetServerHost, pkgName);
            
            // 通过跳板机执行命令获取PID
            SshUtil.SshResult sshResult = SshUtil.executeCommandWithPrivateKey(
                EDIT_BIO_TARGET_HOST,
                22,
                "root",
                sshProperties.getSshPrivateKey(),
                sshProperties.getSshPassphrase(),
                execCommand,
                sshProperties.getSshTimeout(),
                sshProperties.getSshJumpHost(),
                sshProperties.getSshJumpPort(),
                sshProperties.getSshJumpUsername(),
                sshProperties.getSshJumpPassword()
            );
            
            if (sshResult.isSuccess()) {
                String pid = sshResult.getOutput().trim();
                if (pid == null || pid.isEmpty()) {
                    log.error("未能获取到进程ID");
                    result.put("success", false);
                    result.put("message", "脚本启动失败：未能获取进程ID");
                    return result;
                }
                
                log.info("编辑Bio脚本已在后台执行，进程ID: {}", pid);
                
                // 使用PID作为日志文件后缀
                String logFile = String.format("%s_%s.log", EDIT_BIO_LOG_FILE, pid);
                
                // 将进程信息保存到Redis
                Map<String, Object> status = new HashMap<>();
                status.put("pid", pid);
                status.put("startTime", LocalDateTime.now().toString());
                status.put("status", "running");
                status.put("logFile", logFile);
                status.put("targetHost", EDIT_BIO_TARGET_HOST);
                status.put("targetServerHost", targetServerHost);
                status.put("pkgName", pkgName);
                redisTemplate.opsForValue().set(EDIT_BIO_STATUS_KEY, status);
                
                // 启动异步监控
                startEditBioMonitor(pid, logFile);
                
                result.put("success", true);
                result.put("message", "编辑Bio脚本已提交执行");
                result.put("pid", pid);
                result.put("logFile", logFile);
                
            } else {
                log.error("提交编辑Bio脚本失败: {}", sshResult.getErrorMessage());
                result.put("success", false);
                result.put("message", "提交脚本失败: " + sshResult.getErrorMessage());
            }
            
        } catch (Exception e) {
            log.error("执行编辑Bio脚本失败", e);
            result.put("success", false);
            result.put("message", "执行失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 启动编辑Bio脚本监控
     */
    private void startEditBioMonitor(String pid, String logFile) {
        new Thread(() -> {
            try {
                log.info("启动编辑Bio脚本监控，进程ID: {}", pid);
                
                int checkCount = 0;
                int maxChecks = 720; // 最多检查720次（每分钟检查一次，最多12小时）
                
                while (checkCount < maxChecks) {
                    // 等待1分钟后检查
                    Thread.sleep(60000);
                    checkCount++;
                    
                    // 检查进程是否还在运行
                    boolean isRunning = SshUtil.isProcessRunning(
                        EDIT_BIO_TARGET_HOST,
                        22,
                        "root",
                        sshProperties.getSshPrivateKey(),
                        sshProperties.getSshPassphrase(),
                        pid,
                        sshProperties.getSshTimeout(),
                        sshProperties.getSshJumpHost(),
                        sshProperties.getSshJumpPort(),
                        sshProperties.getSshJumpUsername(),
                        sshProperties.getSshJumpPassword()
                    );
                    
                    if (!isRunning) {
                        log.info("编辑Bio脚本已完成，进程ID: {}", pid);
                        
                        // 读取最终日志
                        String logContent = SshUtil.readRemoteLog(
                            EDIT_BIO_TARGET_HOST,
                            22,
                            "root",
                            sshProperties.getSshPrivateKey(),
                            sshProperties.getSshPassphrase(),
                            logFile,
                            0,
                            sshProperties.getSshTimeout(),
                            sshProperties.getSshJumpHost(),
                            sshProperties.getSshJumpPort(),
                            sshProperties.getSshJumpUsername(),
                            sshProperties.getSshJumpPassword()
                        );
                        
                        // 更新Redis状态
                        Map<String, Object> status = new HashMap<>();
                        status.put("pid", pid);
                        status.put("status", "completed");
                        status.put("endTime", LocalDateTime.now().toString());
                        status.put("logFile", logFile);
                        // 只存最后100行摘要
                        status.put("logSummary", getLastNLines(logContent, 100));
                        status.put("totalLogLines", logContent != null ? logContent.split("\n").length : 0);
                        status.put("targetHost", EDIT_BIO_TARGET_HOST);
                        redisTemplate.opsForValue().set(EDIT_BIO_STATUS_KEY, status, 24, TimeUnit.HOURS);
                        
                        break;
                    } else {
                        // 更新运行状态
                        @SuppressWarnings("unchecked")
                        Map<String, Object> status = (Map<String, Object>) redisTemplate.opsForValue().get(EDIT_BIO_STATUS_KEY);
                        if (status != null) {
                            status.put("lastCheck", LocalDateTime.now().toString());
                            status.put("runningTime", checkCount);
                            redisTemplate.opsForValue().set(EDIT_BIO_STATUS_KEY, status);
                        }
                        
                        log.debug("编辑Bio脚本运行中，进程ID: {}, 已运行 {} 分钟", pid, checkCount);
                    }
                }
                
                if (checkCount >= maxChecks) {
                    log.error("编辑Bio脚本监控超时，进程ID: {}", pid);
                    Map<String, Object> status = new HashMap<>();
                    status.put("pid", pid);
                    status.put("status", "timeout");
                    status.put("endTime", LocalDateTime.now().toString());
                    status.put("logFile", logFile);
                    status.put("targetHost", EDIT_BIO_TARGET_HOST);
                    redisTemplate.opsForValue().set(EDIT_BIO_STATUS_KEY, status, 24, TimeUnit.HOURS);
                }
                
            } catch (Exception e) {
                log.error("编辑Bio脚本监控失败", e);
                Map<String, Object> status = new HashMap<>();
                status.put("pid", pid);
                status.put("status", "error");
                status.put("error", e.getMessage());
                redisTemplate.opsForValue().set(EDIT_BIO_STATUS_KEY, status);
            }
        }).start();
    }

    /**
     * 查询编辑Bio脚本执行状态
     */
    public Map<String, Object> getEditBioScriptStatus() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 从Redis获取进程信息
            @SuppressWarnings("unchecked")
            Map<String, Object> status = (Map<String, Object>) redisTemplate.opsForValue().get(EDIT_BIO_STATUS_KEY);
            
            if (status == null) {
                result.put("success", true);
                result.put("status", "not_running");
                result.put("message", "编辑Bio脚本未在运行");
                result.put("logContent", "");
                result.put("pid", "");
                result.put("logFile", "");
                result.put("startTime", null);
                return result;
            }
            
            String pid = (String) status.get("pid");
            String logFile = (String) status.get("logFile");
            String targetHost = (String) status.get("targetHost");
            String currentStatus = (String) status.get("status");
            
            // 始终检查进程是否还在运行
            boolean isRunning = false;
            String actualPid = pid;
            List<String> runningPids = new ArrayList<>();
            
            // 通过脚本路径查找所有运行中的进程
            String checkCommand = String.format("ps -ef | grep '%s' | grep -v grep", EDIT_BIO_SCRIPT_PATH);
            SshUtil.SshResult checkResult = SshUtil.executeCommandWithPrivateKey(
                targetHost,
                22,
                "root",
                sshProperties.getSshPrivateKey(),
                sshProperties.getSshPassphrase(),
                checkCommand,
                sshProperties.getSshTimeout(),
                sshProperties.getSshJumpHost(),
                sshProperties.getSshJumpPort(),
                sshProperties.getSshJumpUsername(),
                sshProperties.getSshJumpPassword()
            );
            
            if (checkResult.isSuccess() && checkResult.getOutput() != null && !checkResult.getOutput().trim().isEmpty()) {
                String[] lines = checkResult.getOutput().trim().split("\n");
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            runningPids.add(parts[1]);
                        }
                    }
                }
                
                if (!runningPids.isEmpty()) {
                    isRunning = true;
                    actualPid = String.join(",", runningPids);
                    log.info("找到 {} 个运行中的编辑Bio脚本进程，PID: {}", runningPids.size(), actualPid);
                }
            }
            
            // 根据PID确定日志文件名
            if (isRunning && !runningPids.isEmpty()) {
                String firstPid = runningPids.get(0);
                String calculatedLogFile = String.format("%s_%s.log", EDIT_BIO_LOG_FILE, firstPid);
                
                if (!calculatedLogFile.equals(logFile)) {
                    log.info("更新日志文件路径，PID: {}, 日志文件: {}", firstPid, calculatedLogFile);
                    logFile = calculatedLogFile;
                    
                    status.put("logFile", logFile);
                    status.put("status", "running");
                    status.put("pid", actualPid);
                    if (status.get("startTime") == null) {
                        status.put("startTime", LocalDateTime.now().toString());
                    }
                    status.put("targetHost", EDIT_BIO_TARGET_HOST);
                    redisTemplate.opsForValue().set(EDIT_BIO_STATUS_KEY, status);
                }
            }
            
            // 读取最新的日志内容（最后100行）
            String logContent = "";
            if (logFile != null && !logFile.isEmpty()) {
                logContent = SshUtil.readRemoteLog(
                    targetHost,
                    22,
                    "root",
                    sshProperties.getSshPrivateKey(),
                    sshProperties.getSshPassphrase(),
                    logFile,
                    100,
                    sshProperties.getSshTimeout(),
                    sshProperties.getSshJumpHost(),
                    sshProperties.getSshJumpPort(),
                    sshProperties.getSshJumpUsername(),
                    sshProperties.getSshJumpPassword()
                );
            }
            
            result.put("success", true);
            result.put("status", isRunning ? "running" : "completed");
            result.put("message", isRunning ? "编辑Bio脚本正在运行" : "编辑Bio脚本已完成");
            result.put("pid", actualPid);
            result.put("logFile", logFile);
            result.put("logContent", logContent);
            result.put("startTime", status.get("startTime"));
            result.put("runningTime", status.get("runningTime"));
            result.put("targetServerHost", status.get("targetServerHost"));
            result.put("pkgName", status.get("pkgName"));
            
            // 如果脚本已完成，更新Redis状态
            if (!isRunning && "running".equals(currentStatus)) {
                status.put("status", "completed");
                status.put("endTime", LocalDateTime.now().toString());
                // 只存最后100行摘要
                status.put("logSummary", getLastNLines(logContent, 100));
                status.put("totalLogLines", logContent != null ? logContent.split("\n").length : 0);
                status.put("pid", actualPid);
                redisTemplate.opsForValue().set(EDIT_BIO_STATUS_KEY, status, 24, TimeUnit.HOURS);
            } else if (isRunning && (pid == null || pid.isEmpty()) && actualPid != null && !actualPid.isEmpty()) {
                status.put("pid", actualPid);
                redisTemplate.opsForValue().set(EDIT_BIO_STATUS_KEY, status);
            }
            
        } catch (Exception e) {
            log.error("查询编辑Bio脚本状态失败", e);
            result.put("success", false);
            result.put("message", "查询失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 执行关注脚本
     */
    public Map<String, Object> executeFollowScript(List<Long> accountIds, String targetUsername) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> details = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;
        
        try {
            log.info("开始执行关注脚本，账号数量: {}, 目标用户: {}", accountIds.size(), targetUsername);
            
            for (Long accountId : accountIds) {
                Map<String, Object> detail = new HashMap<>();
                detail.put("accountId", accountId);
                
                try {
                    // 获取账号信息
                    TtAccountData account = deviceService.getAccountById(accountId);
                    if (account == null) {
                        detail.put("success", false);
                        detail.put("message", "账号不存在");
                        failCount++;
                        details.add(detail);
                        continue;
                    }
                    
                    // 执行关注脚本
                    String scriptPath = "/scripts/follow_user.py";
                    Map<String, Object> scriptParams = new HashMap<>();
                    scriptParams.put("phoneId", account.getPhoneId());
                    scriptParams.put("phoneServerId", account.getPhoneServerId());
                    scriptParams.put("ttUserName", account.getTtUserName());
                    scriptParams.put("targetUsername", targetUsername);
                    
                    boolean scriptResult = executeScript(scriptPath, scriptParams);
                    
                    if (scriptResult) {
                        // 更新关注状态
                        String currentFollowing = account.getFollowingName();
                        String newFollowing = currentFollowing == null ? targetUsername : currentFollowing + "," + targetUsername;
                        account.setFollowingName(newFollowing);
                        account.setUpdatedAt(LocalDateTime.now());
                        
                        boolean updateResult = deviceService.updateAccount(account);
                        
                        if (updateResult) {
                            detail.put("success", true);
                            detail.put("message", "关注成功");
                            detail.put("followingName", newFollowing);
                            successCount++;
                        } else {
                            detail.put("success", false);
                            detail.put("message", "更新关注状态失败");
                            failCount++;
                        }
                    } else {
                        detail.put("success", false);
                        detail.put("message", "脚本执行失败");
                        failCount++;
                    }
                    
                } catch (Exception e) {
                    log.error("账号 {} 关注失败", accountId, e);
                    detail.put("success", false);
                    detail.put("message", "关注失败: " + e.getMessage());
                    failCount++;
                }
                
                details.add(detail);
            }
            
            result.put("success", true);
            result.put("message", String.format("批量关注完成，成功: %d, 失败: %d", successCount, failCount));
            result.put("successCount", successCount);
            result.put("failCount", failCount);
            result.put("details", details);
            
        } catch (Exception e) {
            log.error("执行关注脚本失败", e);
            result.put("success", false);
            result.put("message", "执行失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 执行刷视频脚本
     */
    public Map<String, Object> executeWatchVideoScript(List<Long> accountIds) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> details = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;
        
        try {
            log.info("开始执行刷视频脚本，账号数量: {}", accountIds.size());
            
            for (Long accountId : accountIds) {
                Map<String, Object> detail = new HashMap<>();
                detail.put("accountId", accountId);
                
                try {
                    // 获取账号信息
                    TtAccountData account = deviceService.getAccountById(accountId);
                    if (account == null) {
                        detail.put("success", false);
                        detail.put("message", "账号不存在");
                        failCount++;
                        details.add(detail);
                        continue;
                    }
                    
                    // 执行刷视频脚本
                    String scriptPath = "/scripts/watch_video.py";
                    Map<String, Object> scriptParams = new HashMap<>();
                    scriptParams.put("phoneId", account.getPhoneId());
                    scriptParams.put("phoneServerId", account.getPhoneServerId());
                    scriptParams.put("ttUserName", account.getTtUserName());
                    scriptParams.put("country", account.getCountry());
                    
                    boolean scriptResult = executeScript(scriptPath, scriptParams);
                    
                    if (scriptResult) {
                        // 更新刷视频天数
                        int currentDays = account.getVideoDays() != null ? account.getVideoDays() : 0;
                        account.setVideoDays(currentDays + 1);
                        account.setUpdatedAt(LocalDateTime.now());
                        
                        boolean updateResult = deviceService.updateAccount(account);
                        
                        if (updateResult) {
                            detail.put("success", true);
                            detail.put("message", "刷视频成功");
                            detail.put("videoDays", account.getVideoDays());
                            successCount++;
                        } else {
                            detail.put("success", false);
                            detail.put("message", "更新刷视频天数失败");
                            failCount++;
                        }
                    } else {
                        detail.put("success", false);
                        detail.put("message", "脚本执行失败");
                        failCount++;
                    }
                    
                } catch (Exception e) {
                    log.error("账号 {} 刷视频失败", accountId, e);
                    detail.put("success", false);
                    detail.put("message", "刷视频失败: " + e.getMessage());
                    failCount++;
                }
                
                details.add(detail);
            }
            
            result.put("success", true);
            result.put("message", String.format("批量刷视频完成，成功: %d, 失败: %d", successCount, failCount));
            result.put("successCount", successCount);
            result.put("failCount", failCount);
            result.put("details", details);
            
        } catch (Exception e) {
            log.error("执行刷视频脚本失败", e);
            result.put("success", false);
            result.put("message", "执行失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取脚本执行状态
     */
    public Map<String, Object> getScriptExecutionStatus(String taskId) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 从Redis获取任务状态
            String statusKey = "script_task_status:" + taskId;
            Object status = redisTemplate.opsForValue().get(statusKey);
            
            if (status != null) {
                result.put("success", true);
                result.put("status", status);
            } else {
                result.put("success", false);
                result.put("message", "任务不存在或已过期");
            }
            
        } catch (Exception e) {
            log.error("获取脚本执行状态失败", e);
            result.put("success", false);
            result.put("message", "获取状态失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 执行脚本的核心方法（模拟实现）
     */
    private boolean executeScript(String scriptPath, Map<String, Object> params) {
        try {
            log.info("执行脚本: {}, 参数: {}", scriptPath, params);
            
            // 这里应该调用实际的脚本执行逻辑
            // 比如使用ProcessBuilder执行Python脚本
            // 或者调用其他脚本执行框架
            
            // 模拟脚本执行时间
            Thread.sleep(2000);
            
            // 模拟90%的成功率
            Random random = new Random();
            boolean success = random.nextInt(10) < 9;
            
            log.info("脚本执行结果: {}", success ? "成功" : "失败");
            return success;
            
        } catch (Exception e) {
            log.error("脚本执行异常", e);
            return false;
        }
    }

    /**
     * 批量执行脚本
     */
    public Map<String, Object> batchExecuteScript(String scriptType, List<Long> targetIds, Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String taskId = "task_" + System.currentTimeMillis();
            
            // 将任务状态存储到Redis
            String statusKey = "script_task_status:" + taskId;
            Map<String, Object> taskStatus = new HashMap<>();
            taskStatus.put("taskId", taskId);
            taskStatus.put("scriptType", scriptType);
            taskStatus.put("targetCount", targetIds.size());
            taskStatus.put("status", "running");
            taskStatus.put("startTime", LocalDateTime.now());
            
            redisTemplate.opsForValue().set(statusKey, taskStatus, 24, TimeUnit.HOURS);
            
            // 异步执行脚本
            executeScriptAsync(scriptType, targetIds, params, taskId);
            
            result.put("success", true);
            result.put("taskId", taskId);
            result.put("message", "任务已提交，请通过taskId查询执行状态");
            
        } catch (Exception e) {
            log.error("批量执行脚本失败", e);
            result.put("success", false);
            result.put("message", "执行失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 异步执行脚本
     */
    private void executeScriptAsync(String scriptType, List<Long> targetIds, Map<String, Object> params, String taskId) {
        // 这里应该使用异步任务执行
        // 可以使用@Async注解或线程池
        new Thread(() -> {
            try {
                Map<String, Object> scriptResult = null;
                
                switch (scriptType) {
                    case SCRIPT_TYPE_REGISTER:
                        scriptResult = executeRegisterScript();
                        break;
                    case SCRIPT_TYPE_FOLLOW:
                        String targetUsername = (String) params.get("targetUsername");
                        scriptResult = executeFollowScript(targetIds, targetUsername);
                        break;
                    case SCRIPT_TYPE_WATCH_VIDEO:
                        scriptResult = executeWatchVideoScript(targetIds);
                        break;
                    default:
                        scriptResult = new HashMap<>();
                        scriptResult.put("success", false);
                        scriptResult.put("message", "不支持的脚本类型");
                }
                
                // 更新任务状态
                String statusKey = "script_task_status:" + taskId;
                Map<String, Object> taskStatus = new HashMap<>();
                taskStatus.put("taskId", taskId);
                taskStatus.put("scriptType", scriptType);
                taskStatus.put("targetCount", targetIds.size());
                taskStatus.put("status", "completed");
                taskStatus.put("startTime", LocalDateTime.now());
                taskStatus.put("endTime", LocalDateTime.now());
                taskStatus.put("result", scriptResult);
                
                redisTemplate.opsForValue().set(statusKey, taskStatus, 24, TimeUnit.HOURS);
                
            } catch (Exception e) {
                log.error("异步执行脚本失败", e);
                
                // 更新任务状态为失败
                String statusKey = "script_task_status:" + taskId;
                Map<String, Object> taskStatus = new HashMap<>();
                taskStatus.put("taskId", taskId);
                taskStatus.put("scriptType", scriptType);
                taskStatus.put("targetCount", targetIds.size());
                taskStatus.put("status", "failed");
                taskStatus.put("startTime", LocalDateTime.now());
                taskStatus.put("endTime", LocalDateTime.now());
                taskStatus.put("error", e.getMessage());
                
                redisTemplate.opsForValue().set(statusKey, taskStatus, 24, TimeUnit.HOURS);
            }
        }).start();
    }

    /**
     * 执行远程脚本（通过SSH连接到云手机服务器）
     * 
     * @param serverHost 服务器地址
     * @param command 要执行的命令
     * @param params 额外参数
     * @return 执行结果
     */
    private Map<String, Object> executeRemoteScript(String serverHost, String command, Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("在服务器 {} 上执行远程命令: {}", serverHost, command);
            
            // 获取SSH配置
            String privateKey = sshProperties.getSshPrivateKey();
            String username = sshProperties.getSshUsername();
            Integer port = sshProperties.getSshPort();
            Integer timeout = sshProperties.getSshTimeout();
            String passphrase = sshProperties.getSshPassphrase();
            
            if (privateKey != null && !privateKey.isEmpty()) {
                // 使用nohup后台异步执行（适合长时间任务，2-3小时）
                log.info("使用私钥认证连接SSH（异步执行）");
                
                // 生成日志文件名（使用服务器上的标准日志路径）
                // 格式: batch_create_log_tt_107_224_20251013
                String phonePrefix = (String) params.getOrDefault("phonePrefix", "tt_107_224");

                String dateStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
                String logFile = String.format("/tmp/batch_create_output_log_%s_%s", phonePrefix, dateStr);
                String scriptLogFile = String.format("/tmp/batch_create_log_%s_%s", phonePrefix, dateStr);

                log.info("执行云手机创建脚本 - 目标服务器: {}, 命令: {}", serverHost, command);
                
                // 异步执行命令（通过跳板机）
                SshUtil.SshResult sshResult = SshUtil.executeCommandAsync(
                    serverHost,  // 使用参数中指定的服务器
                    sshProperties.getSshTargetPort(), 
                    username, 
                    privateKey, 
                    passphrase,
                    command,
                    logFile,
                    timeout,
                    sshProperties.getSshJumpHost(),     // 跳板机
                    sshProperties.getSshJumpPort(),
                    sshProperties.getSshJumpUsername(),
                    sshProperties.getSshJumpPassword()
                );
                
                if (sshResult.isSuccess()) {
                    String pid = sshResult.getOutput(); // 后台进程ID
                    log.info("脚本已在后台执行，进程ID: {}, 日志文件: {}", pid, logFile);
                    
                    // 启动异步监控任务（稍后解析结果）
                    startAsyncMonitor(serverHost, sshProperties.getSshTargetPort(), 
                                    username, privateKey, passphrase, pid, scriptLogFile, timeout, params);
                    
                    // 立即返回（不等待脚本完成）
                    result.put("success", true);
                    result.put("message", String.format("脚本已提交后台执行，进程ID: %s", pid));
                    result.put("pid", pid);
                    result.put("logFile", logFile);
                    result.put("async", true);
                    
                    log.info("异步任务已提交，将在后台监控执行结果");
                    return result;
                } else {
                    result.put("success", false);
                    result.put("message", "脚本提交失败: " + sshResult.getErrorMessage());
                    log.error("异步脚本提交失败: {}", sshResult.getErrorMessage());
                }
            } else {
                result.put("success", false);
                result.put("message", "未配置SSH私钥");
            }
            
        } catch (Exception e) {
            log.error("执行远程脚本失败", e);
            result.put("success", false);
            result.put("message", "执行失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 查询异步任务状态
     */
    public Map<String, Object> getAsyncTaskStatus(String host, String pid, String logFile) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 获取SSH配置
            String privateKey = sshProperties.getSshPrivateKey();
            String username = sshProperties.getSshUsername();
            Integer port = sshProperties.getSshPort();
            Integer timeout = sshProperties.getSshTimeout();
            String passphrase = sshProperties.getSshPassphrase();
            
            // 检查进程是否还在运行
            boolean isRunning = SshUtil.isProcessRunning(host, port, username, privateKey, passphrase, pid, timeout);
            
            // 读取最后50行日志
            String logContent = SshUtil.readRemoteLog(host, port, username, privateKey, passphrase, logFile, 50, timeout);
            
            result.put("success", true);
            result.put("isRunning", isRunning);
            result.put("status", isRunning ? "running" : "completed");
            result.put("logContent", logContent);
            result.put("pid", pid);
            result.put("logFile", logFile);
            
            if (!isRunning) {
                // 如果已完成，解析云手机名称
                String fullLog = SshUtil.readRemoteLog(host, port, username, privateKey, passphrase, logFile, 0, timeout);
                List<String> phoneNames = parsePhoneNames(fullLog);
                result.put("phoneNames", phoneNames);
                result.put("count", phoneNames.size());
            }
            
        } catch (Exception e) {
            log.error("查询异步任务状态失败", e);
            result.put("success", false);
            result.put("message", "查询失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 启动异步监控任务
     * 定期检查远程脚本执行状态，完成后解析结果并插入数据库
     */
    private void startAsyncMonitor(String serverHost, int port, String username, 
                                   String privateKey, String passphrase,
                                   String pid, String logFile, int timeout,
                                   Map<String, Object> params) {
        new Thread(() -> {
            try {
                log.info("启动异步监控任务，进程ID: {}", pid);
                
                int checkCount = 0;
                int maxChecks = 720; // 最多检查720次（每分钟检查一次，最多12小时）
                
                while (checkCount < maxChecks) {
                    // 等待1分钟后检查
                    Thread.sleep(60000); // 60秒
                    checkCount++;
                    
                    // 检查进程是否还在运行（通过跳板机）
                    boolean isRunning = SshUtil.isProcessRunning(
                        serverHost, port,  // ← 使用传入的实际服务器地址
                        username, privateKey, passphrase, pid, timeout,
                        sshProperties.getSshJumpHost(), sshProperties.getSshJumpPort(),
                        sshProperties.getSshJumpUsername(), sshProperties.getSshJumpPassword()
                    );
                    
                    if (!isRunning) {
                        log.info("后台进程已完成，进程ID: {}", pid);
                        Thread.sleep(3000);
                        // 读取日志文件内容（通过跳板机）
                        String logContent = SshUtil.readRemoteLog(
                            serverHost, port,  // ← 使用传入的实际服务器地址
                            username, privateKey, passphrase, logFile, 0, timeout,
                            sshProperties.getSshJumpHost(), sshProperties.getSshJumpPort(),
                            sshProperties.getSshJumpUsername(), sshProperties.getSshJumpPassword()
                        );
                        
                        // 解析云手机名称
                        List<String> phoneNames = parsePhoneNames(logContent);
                        
                        if (!phoneNames.isEmpty()) {
                            // 插入数据库
                            String country = (String) params.get("country");
                            String pkgName = (String) params.getOrDefault("pkgName", "com.tiktok.lite.go");
                            
                            for (String phoneName : phoneNames) {
                                TtAccountDataOutlook device = new TtAccountDataOutlook();
                                device.setPhoneId(phoneName);
                                device.setPhoneServerId(serverHost);
                                device.setCountry(country);
                                device.setPkgName(pkgName);
                                device.setStatus(0);
                                
                                deviceService.addDeviceToPool(device);
                            }
                            
                            log.info("异步任务完成，成功创建 {} 个云手机并插入数据库", phoneNames.size());
                        } else {
                            log.warn("异步任务完成，但未解析到云手机名称，日志内容: {}", logContent);
                        }
                        
                        break;
                    } else {
                        log.debug("后台进程仍在执行，进程ID: {}, 已检查 {} 次", pid, checkCount);
                    }
                }
                
                if (checkCount >= maxChecks) {
                    log.error("异步任务监控超时，进程ID: {}", pid);
                }
                
            } catch (Exception e) {
                log.error("异步监控任务失败", e);
            }
        }).start();
    }

    /**
     * 测试SSH配置
     */
    public Map<String, Object> testSshConfig() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("测试SSH配置加载");
            
            // 获取SSH配置
            String privateKey = sshProperties.getSshPrivateKey();
            String username = sshProperties.getSshUsername();
            Integer port = sshProperties.getSshPort();
            Integer timeout = sshProperties.getSshTimeout();
            String passphrase = sshProperties.getSshPassphrase();
            String jumpHost = sshProperties.getSshJumpHost();
            Integer jumpPort = sshProperties.getSshJumpPort();
            String targetHost = sshProperties.getSshTargetHost();
            Integer targetPort = sshProperties.getSshTargetPort();
            
            log.info("SSH配置详情:");
            log.info("- 私钥长度: {}", privateKey != null ? privateKey.length() : 0);
            log.info("- 用户名: {}", username);
            log.info("- 端口: {}", port);
            log.info("- 超时: {}", timeout);
            log.info("- 跳板机: {}:{}", jumpHost, jumpPort);
            log.info("- 目标主机: {}:{}", targetHost, targetPort);
            
            result.put("success", true);
            result.put("sshConfig", Map.of(
                "privateKeyLength", privateKey != null ? privateKey.length() : 0,
                "username", username != null ? username : "null",
                "port", port,
                "timeout", timeout,
                "jumpHost", jumpHost != null ? jumpHost : "null",
                "jumpPort", jumpPort,
                "targetHost", targetHost != null ? targetHost : "null",
                "targetPort", targetPort,
                "hasPrivateKey", privateKey != null && !privateKey.isEmpty()
            ));
            
        } catch (Exception e) {
            log.error("测试SSH配置失败", e);
            result.put("success", false);
            result.put("message", "测试失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 测试读取日志并解析云手机名称，并插入数据库
     */
    public Map<String, Object> testParseLog(String targetHost, String logFile, String pkgName, Integer lines) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("测试读取日志文件: {}, 包名: {}", logFile, pkgName);
            
            // 获取SSH配置
            String privateKey = sshProperties.getSshPrivateKey();
            String username = sshProperties.getSshUsername();
            Integer port = sshProperties.getSshPort();
            Integer timeout = sshProperties.getSshTimeout();
            String passphrase = sshProperties.getSshPassphrase();
            
            // 如果未指定目标服务器，使用配置文件中的默认服务器
            String actualTargetHost = (targetHost != null && !targetHost.isEmpty()) 
                ? targetHost 
                : sshProperties.getSshTargetHost();
            
            log.info("SSH配置检查 - 私钥长度: {}, 用户名: {}, 目标主机: {}, 跳板机: {}", 
                privateKey != null ? privateKey.length() : 0, username, 
                actualTargetHost, sshProperties.getSshJumpHost());
            
            if (privateKey == null || privateKey.isEmpty()) {
                result.put("success", false);
                result.put("message", "未配置SSH私钥");
                return result;
            }
            
            // 读取日志文件（通过跳板机）
            String logContent = SshUtil.readRemoteLog(
                actualTargetHost, sshProperties.getSshTargetPort(),
                username, privateKey, passphrase, logFile, lines, timeout,
                sshProperties.getSshJumpHost(), sshProperties.getSshJumpPort(),
                sshProperties.getSshJumpUsername(), sshProperties.getSshJumpPassword()
            );
            
            if (logContent == null || logContent.isEmpty()) {
                result.put("success", false);
                result.put("message", "日志文件为空或读取失败");
                return result;
            }
            
            // 解析云手机名称
            List<String> phoneNames = parsePhoneNames(logContent);
            
            // 统计信息
            String[] allLines = logContent.split("\n");
            int totalLines = allLines.length;
            int successLines = 0;
            int failedLines = 0;
            
            for (String line : allLines) {
                if (line.startsWith("SUCCESS:")) {
                    successLines++;
                }
                if (line.startsWith("FAILED:")) {
                    failedLines++;
                }
            }
            
            // 插入成功的云手机到数据库
            int insertedCount = 0;
            if (!phoneNames.isEmpty()) {
                log.info("开始将 {} 个云手机插入数据库，包名: {}", phoneNames.size(), pkgName);
                
                // 从日志内容中提取国家信息
                String country = extractCountryFromLog(logContent);
                log.info("提取到的国家代码: {}", country);
                
                for (String phoneName : phoneNames) {
                    try {
                        // 检查是否已存在
                        TtAccountDataOutlook existing = deviceService.findDeviceByPhoneId(phoneName);
                        if (existing != null) {
                            log.debug("云手机已存在，跳过: {}", phoneName);
                            continue;
                        }
                        
                        // 创建新设备记录
                        TtAccountDataOutlook device = new TtAccountDataOutlook();
                        device.setPhoneId(phoneName);
                        device.setPhoneServerId(actualTargetHost);
                        device.setCountry(country);
                        device.setPkgName(pkgName);
                        device.setStatus(0); // 正常状态
                        device.setEditStatus(0); // 未编辑
                        device.setEmailStatus(0); // 未绑定邮箱
                        device.setUploadStatus(0); // 不上传视频
                        
                        deviceService.addDeviceToPool(device);
                        insertedCount++;
                        log.debug("成功插入云手机: {}", phoneName);
                        
                    } catch (Exception e) {
                        log.error("插入云手机失败: {}, 错误: {}", phoneName, e.getMessage());
                    }
                }
                
                log.info("数据库插入完成，成功插入: {} 个，跳过重复: {} 个", 
                        insertedCount, phoneNames.size() - insertedCount);
            }
            
            result.put("success", true);
            result.put("logContent", logContent);
            result.put("phoneNames", phoneNames);
            result.put("parsedCount", phoneNames.size());
            result.put("insertedCount", insertedCount);
            result.put("statistics", Map.of(
                "totalLines", totalLines,
                "successLines", successLines,
                "failedLines", failedLines,
                "parsedPhones", phoneNames.size(),
                "insertedToDb", insertedCount
            ));
            
            log.info("测试完成，解析到 {} 个云手机名称，插入数据库 {} 个", phoneNames.size(), insertedCount);
            
        } catch (Exception e) {
            log.error("测试读取日志失败", e);
            result.put("success", false);
            result.put("message", "测试失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 解析云手机名称
     * 
     * @param output 脚本输出
     * @return 云手机名称列表
输出到     * 
     * 支持的日志格式：
     * SUCCESS: tt_107_224_2_US_20251011 (序号: 2) - Sat 11 Oct 2025 04:37:22 PM HKT
     * FAILED: tt_107_224_1_US_20251011 (序号: 1, 创建失败) - Sat 11 Oct 2025 04:35:11 PM HKT
     */
    private List<String> parsePhoneNames(String output) {
        List<String> phoneNames = new ArrayList<>();
        
        try {
            log.info("开始解析云手机名称，日志长度: {} 字符", output.length());
            
            String[] lines = output.split("\n");
            int successCount = 0;
            int failedCount = 0;
            
            for (String line : lines) {
                line = line.trim();
                
                // 匹配 SUCCESS: 开头的行
                if (line.startsWith("SUCCESS:")) {
                    // 提取云手机名称
                    // SUCCESS: tt_107_224_2_US_20251011 (序号: 2) - ...
                    // 匹配格式: <前缀>_<序号>_<国家>_<日期>
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("SUCCESS:\\s+([^\\s]+_\\d+_[A-Z]{2}_\\d{8})");
                    java.util.regex.Matcher matcher = pattern.matcher(line);
                    
                    if (matcher.find()) {
                        String phoneName = matcher.group(1);
                        phoneNames.add(phoneName);
                        successCount++;
                        log.debug("解析成功: {}", phoneName);
                    }
                } else if (line.startsWith("FAILED:")) {
                    failedCount++;
                    log.debug("跳过失败记录: {}", line);
                }
            }
            
            log.info("解析完成，成功: {} 个，失败: {} 个，总共: {} 个", successCount, failedCount, phoneNames.size());
            
        } catch (Exception e) {
            log.error("解析云手机名称失败", e);
        }
        
        return phoneNames;
    }

    /**
     * 从日志内容中提取国家代码
     */
    private String extractCountryFromLog(String logContent) {
        try {
            // 从日志第一行或参数行提取：参数: 名称头=tt_107_224, 国家=US, 数量=100
            String[] lines = logContent.split("\n");
            for (String line : lines) {
                if (line.contains("国家=") || line.contains("country=")) {
                    // 提取国家代码
                    int startIdx = line.indexOf("国家=");
                    if (startIdx == -1) {
                        startIdx = line.indexOf("country=");
                    }
                    if (startIdx != -1) {
                        startIdx += (line.contains("国家=") ? 3 : 8); // "国家=".length() or "country=".length()
                        int endIdx = line.indexOf(",", startIdx);
                        if (endIdx == -1) {
                            endIdx = line.indexOf(" ", startIdx);
                        }
                        if (endIdx == -1) {
                            endIdx = line.length();
                        }
                        
                        String country = line.substring(startIdx, endIdx).trim();
                        log.debug("从日志提取国家代码: {}", country);
                        return country;
                    }
                }
                
                // 从云手机名称提取：tt_107_224_1_US_20251013
                if (line.contains("SUCCESS:") && line.contains("_")) {
                    String[] parts = line.split("\\s+");
                    for (String part : parts) {
                        if (part.startsWith("tt_")) {
                            String[] nameParts = part.split("_");
                            if (nameParts.length >= 5) {
                                String country = nameParts[nameParts.length - 2];
                                log.debug("从云手机名称提取国家代码: {}", country);
                                return country;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("提取国家代码失败，使用默认值US", e);
        }
        
        return "US"; // 默认美国
    }
}
