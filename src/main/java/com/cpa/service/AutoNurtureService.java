package com.cpa.service;

import com.cpa.config.AutoNurtureConfig;
import com.cpa.config.SshProperties;
import com.cpa.entity.DeviceInfo;
import com.cpa.repository.TtAccountDataRepository;
import com.cpa.util.SshUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 设备执行结果
 */
class DeviceExecutionResult {
    private final String phoneId;
    private final String status;
    private final boolean success;
    private final long duration;
    
    public DeviceExecutionResult(String phoneId, String status, boolean success, long duration) {
        this.phoneId = phoneId;
        this.status = status;
        this.success = success;
        this.duration = duration;
    }
    
    public String getPhoneId() {
        return phoneId;
    }
    
    public String getStatus() {
        return status;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public long getDuration() {
        return duration;
    }
}

/**
 * 自动养号服务
 */
@Service
@Slf4j
public class AutoNurtureService {
    
    @Autowired
    private SshProperties sshProperties;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private TtAccountDataRepository accountRepository;
    
    private static final String TASK_KEY_PREFIX = "auto_nurture:task:";
    private static final String SCRIPT_HOST = "10.13.55.85";
    
    /**
     * 启动自动化养号任务
     */
    public Map<String, Object> startAutoNurture(AutoNurtureConfig config) {
        String taskId = "AUTO_NURTURE_" + System.currentTimeMillis();
        
        log.info("=== 启动自动养号任务: {} ===", taskId);
        log.info("配置: {}", config);
        
        // 保存任务状态到Redis
        Map<String, Object> taskStatus = new HashMap<>();
        taskStatus.put("taskId", taskId);
        taskStatus.put("status", "RUNNING");
        taskStatus.put("startTime", LocalDateTime.now().toString());
        taskStatus.put("config", config);
        taskStatus.put("progress", 0);
        taskStatus.put("currentRound", 0);
        taskStatus.put("totalRounds", config.getRounds());
        redisTemplate.opsForValue().set(TASK_KEY_PREFIX + taskId, taskStatus, 24, TimeUnit.HOURS);
        
        // 异步执行
        executeAutoNurtureAsync(taskId, config);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("taskId", taskId);
        result.put("message", "自动养号任务已启动");
        return result;
    }
    
    /**
     * 查询任务状态
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTaskStatus(String taskId) {
        Map<String, Object> taskStatus = (Map<String, Object>) redisTemplate.opsForValue().get(TASK_KEY_PREFIX + taskId);
        
        if (taskStatus == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "任务不存在或已过期");
            return result;
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", taskStatus);
        return result;
    }
    
    /**
     * 获取任务列表
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTaskList() {
        Set<String> keys = redisTemplate.keys(TASK_KEY_PREFIX + "*");
        
        List<Map<String, Object>> taskList = new ArrayList<>();
        if (keys != null && !keys.isEmpty()) {
            for (String key : keys) {
                Map<String, Object> task = (Map<String, Object>) redisTemplate.opsForValue().get(key);
                if (task != null) {
                    taskList.add(task);
                }
            }
        }
        
        // 按开始时间倒序排序
        taskList.sort((a, b) -> {
            String timeA = (String) a.get("startTime");
            String timeB = (String) b.get("startTime");
            return timeB.compareTo(timeA);
        });
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", taskList);
        return result;
    }
    
    /**
     * 异步执行自动养号
     */
    @Async
    public void executeAutoNurtureAsync(String taskId, AutoNurtureConfig config) {
        try {
            log.info("=== 任务 {} 开始执行 ===", taskId);
            
            Map<String, Integer> totalSummary = new HashMap<>();
            totalSummary.put("total", 0);
            totalSummary.put("success", 0);
            totalSummary.put("failed", 0);
            totalSummary.put("ip_failed", 0);
            totalSummary.put("log_out", 0);
            totalSummary.put("black_list", 0);
            totalSummary.put("follow_all", 0);
            
            // 执行多轮任务
            for (int round = 0; round < config.getRounds(); round++) {
                log.info("=== 任务 {} 开始第 {}/{} 轮 ===", taskId, round + 1, config.getRounds());
                
                // 更新当前轮次
                updateTaskField(taskId, "currentRound", round + 1);
                
                // 确定本轮配置
                AutoNurtureConfig roundConfig = buildRoundConfig(config, round);
                
                // 1. 从数据库获取设备列表
                List<DeviceInfo> devices = fetchDevicesFromDB(roundConfig);
                log.info("任务 {} 第 {} 轮获取到 {} 个设备", taskId, round + 1, devices.size());
                
                if (devices.isEmpty()) {
                    log.info("任务 {} 第 {} 轮没有可执行设备，跳过", taskId, round + 1);
                    continue;
                }
                
                // 2. 分组执行（每组10个）
                List<List<DeviceInfo>> groups = partitionList(devices, config.getGroupSize());
                
                for (int i = 0; i < groups.size(); i++) {
                    List<DeviceInfo> group = groups.get(i);
                    log.info("=== 任务 {} 第 {} 轮开始执行第 {}/{} 组，设备数: {} ===", 
                        taskId, round + 1, i + 1, groups.size(), group.size());
                    
                    // 3. 执行分组任务
                    Map<String, String> groupResult = executeDeviceGroup(taskId, group, roundConfig);
                    
                    // 4. 统计结果
                    for (String status : groupResult.values()) {
                        totalSummary.put("total", totalSummary.get("total") + 1);
                        
                        if ("FOLLOW_SUCCESS".equals(status)) {
                            totalSummary.put("success", totalSummary.get("success") + 1);
                        } else if ("FOLLOW_ALL".equals(status)) {
                            totalSummary.put("follow_all", totalSummary.get("follow_all") + 1);
                        } else if ("IP_FAILED".equals(status)) {
                            totalSummary.put("ip_failed", totalSummary.get("ip_failed") + 1);
                        } else if ("LOG_OUT".equals(status)) {
                            totalSummary.put("log_out", totalSummary.get("log_out") + 1);
                        } else if ("BLACK_LIST".equals(status)) {
                            totalSummary.put("black_list", totalSummary.get("black_list") + 1);
                        } else {
                            totalSummary.put("failed", totalSummary.get("failed") + 1);
                        }
                    }
                    
                    // 5. 更新Redis状态
                    int totalGroups = 0;
                    for (int r = 0; r <= round; r++) {
                        totalGroups += (fetchDevicesFromDB(buildRoundConfig(config, r)).size() + config.getGroupSize() - 1) / config.getGroupSize();
                    }
                    int currentGroupIndex = 0;
                    for (int r = 0; r < round; r++) {
                        currentGroupIndex += (fetchDevicesFromDB(buildRoundConfig(config, r)).size() + config.getGroupSize() - 1) / config.getGroupSize();
                    }
                    currentGroupIndex += (i + 1);
                    
                    int progress = currentGroupIndex * 100 / Math.max(totalGroups, 1);
                    updateTaskProgress(taskId, progress, totalSummary);
                    
                    // 6. 组间等待
                    if (i < groups.size() - 1) {
                        log.info("任务 {} 第 {} 轮第 {} 组完成，等待30秒后继续...", taskId, round + 1, i + 1);
                        Thread.sleep(30000);
                    }
                }
                
                // 轮次间等待
                if (round < config.getRounds() - 1) {
                    log.info("任务 {} 第 {} 轮完成，等待30秒后开始第 {} 轮...", taskId, round + 1, round + 2);
                    Thread.sleep(30000);
                }
            }
            
            // 7. 完成，更新状态
            Map<String, Object> finalStatus = new HashMap<>();
            finalStatus.put("taskId", taskId);
            finalStatus.put("status", "COMPLETED");
            @SuppressWarnings("unchecked")
            Map<String, Object> currentStatus = (Map<String, Object>) redisTemplate.opsForValue().get(TASK_KEY_PREFIX + taskId);
            finalStatus.put("startTime", currentStatus != null ? currentStatus.get("startTime") : LocalDateTime.now().toString());
            finalStatus.put("endTime", LocalDateTime.now().toString());
            finalStatus.put("progress", 100);
            finalStatus.put("summary", totalSummary);
            finalStatus.put("config", config);
            redisTemplate.opsForValue().set(TASK_KEY_PREFIX + taskId, finalStatus, 24, TimeUnit.HOURS);
            
            log.info("=== 任务 {} 执行完成，统计: {} ===", taskId, totalSummary);
            
        } catch (Exception e) {
            log.error("任务 {} 执行失败", taskId, e);
            Map<String, Object> errorStatus = new HashMap<>();
            errorStatus.put("taskId", taskId);
            errorStatus.put("status", "FAILED");
            errorStatus.put("error", e.getMessage());
            errorStatus.put("endTime", LocalDateTime.now().toString());
            redisTemplate.opsForValue().set(TASK_KEY_PREFIX + taskId, errorStatus, 24, TimeUnit.HOURS);
        }
    }
    
    /**
     * 构建每轮的配置
     */
    private AutoNurtureConfig buildRoundConfig(AutoNurtureConfig config, int round) {
        AutoNurtureConfig roundConfig = new AutoNurtureConfig();
        roundConfig.setGroupSize(config.getGroupSize());
        roundConfig.setAllowedCountries(config.getAllowedCountries());
        roundConfig.setPhoneServerIp(config.getPhoneServerIp());
        roundConfig.setGroupTimeout(config.getGroupTimeout());
        
        if (round == 0) {
            // 第1轮：需要上传视频的正常账号
            roundConfig.setDeviceStatus(0);
            roundConfig.setUploadStatus(1);
            roundConfig.setUploadVideo(true);
        } else if (round == 1) {
            // 第2轮：正常账号，随机上传
            roundConfig.setDeviceStatus(0);
            roundConfig.setUploadStatus(0);
            roundConfig.setUploadVideo(new Random().nextInt(100) < 30); // 30%概率上传
        } else {
            // 第3轮：黑名单账号
            roundConfig.setDeviceStatus(2);
            roundConfig.setUploadStatus(0);
            roundConfig.setUploadVideo(false);
        }
        
        return roundConfig;
    }
    
    /**
     * 从数据库获取设备列表
     */
    private List<DeviceInfo> fetchDevicesFromDB(AutoNurtureConfig config) {
        try {
            return accountRepository.getDeviceList(
                config.getPhoneServerIp(),
                config.getDeviceStatus(),
                config.getUploadStatus()
            );
        } catch (Exception e) {
            log.error("从数据库获取设备列表失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 执行设备组
     */
    private Map<String, String> executeDeviceGroup(String taskId, 
                                                     List<DeviceInfo> devices, 
                                                     AutoNurtureConfig config) {
        Map<String, String> results = new ConcurrentHashMap<>();
        String serverIp = config.getPhoneServerIp();
        
        try {
            // ====== 阶段1: 批量准备（串行） ======
            log.info("任务 {} 开始批量准备阶段，设备数: {}", taskId, devices.size());
            
            for (DeviceInfo device : devices) {
                String phoneId = device.getPhoneId();
                
                // 1.1 检查静态IP
                log.info("{} - 检查静态IP", phoneId);
                sshCommand(serverIp, String.format(
                    "sudo /home/ubuntu/tiktok_check_static_ip.sh %s US", phoneId));
                
                // 1.2 启动云手机
                log.info("{} - 启动云手机", phoneId);
                sshCommand(serverIp, String.format(
                    "sudo bash /home/ubuntu/tiktok_phone_start.sh %s", phoneId));
            }
            
            // 1.3 等待启动
            log.info("任务 {} 等待设备启动，等待20秒...", taskId);
            Thread.sleep(20000);
            
            // 1.4 检查开机状态
            for (DeviceInfo device : devices) {
                String phoneId = device.getPhoneId();
                for (int retry = 0; retry < 3; retry++) {
                    log.info("{} - 检查开机状态 (尝试 {}/3)", phoneId, retry + 1);
                    SshUtil.SshResult bootCheck = sshCommand(serverIp, String.format(
                        "docker exec %s getprop sys.boot_completed", phoneId));
                    if (bootCheck.isSuccess() && bootCheck.getOutput().contains("1")) {
                        log.info("{} - 开机成功", phoneId);
                        break;
                    }
                    Thread.sleep(8000);
                }
            }
            
            // 1.5 视频分发（如果需要上传）
            if (config.getUploadVideo()) {
                log.info("任务 {} 开始视频分发，设备数: {}", taskId, devices.size());
                List<String> phoneIds = devices.stream()
                    .map(DeviceInfo::getPhoneId)
                    .collect(Collectors.toList());
                
                // 调用视频分发脚本
                String phoneIdsStr = "['" + String.join("','", phoneIds) + "']";
                String distributeCmd = String.format(
                    "cd /data/appium/com_zhiliaoapp_musically/zl && " +
                    "python3 -c \"from TTTest_video_distribute_dev import distribute_dev; " +
                    "result = distribute_dev(%s, '%s', upload_video_count=1, video_start_index=0); " +
                    "import json; print(json.dumps(result if result else {}))\"",
                    phoneIdsStr, serverIp
                );
                SshUtil.SshResult distributeResult = sshCommand(SCRIPT_HOST, distributeCmd);
                log.info("视频分发完成: {}", distributeResult.isSuccess() ? "成功" : "失败");
            }
            
            // ====== 阶段2: 设备任务（动态并发，单设备超时控制） ======
            log.info("任务 {} 开始设备任务阶段，总设备数: {}，保持20个并发，单设备超时: {}分钟", 
                taskId, devices.size(), config.getGroupTimeout());
            
            // 使用固定大小线程池，保持20个活跃线程
            ExecutorService executor = Executors.newFixedThreadPool(20);
            
            // 使用CompletionService来按完成顺序获取结果
            CompletionService<DeviceExecutionResult> completionService = 
                new ExecutorCompletionService<>(executor);
            
            // 单设备超时时间（毫秒）
            final long deviceTimeout = config.getGroupTimeout() * 60 * 1000L;
            
            // 提交所有设备任务，每个设备都有独立的超时控制
            int submittedCount = 0;
            for (DeviceInfo device : devices) {
                completionService.submit(() -> {
                    String phoneId = device.getPhoneId();
                    long startTime = System.currentTimeMillis();
                    
                    // 创建单设备执行的Future，带超时控制
                    ExecutorService singleDeviceExecutor = Executors.newSingleThreadExecutor();
                    Future<String> deviceFuture = singleDeviceExecutor.submit(() -> 
                        executeSingleDevice(taskId, device, config)
                    );
                    
                    try {
                        // 等待设备执行完成，带超时
                        String status = deviceFuture.get(deviceTimeout, TimeUnit.MILLISECONDS);
                        long duration = System.currentTimeMillis() - startTime;
                        log.info("{} - 任务完成，状态: {}，耗时: {}秒", 
                            phoneId, status, duration / 1000);
                        return new DeviceExecutionResult(phoneId, status, true, duration);
                        
                    } catch (TimeoutException e) {
                        // 单设备超时
                        long duration = System.currentTimeMillis() - startTime;
                        log.error("{} - 执行超时（{}分钟），耗时: {}秒，强制终止", 
                            phoneId, config.getGroupTimeout(), duration / 1000);
                        
                        // 取消任务并强制停止设备
                        deviceFuture.cancel(true);
                        forceStopDevice(phoneId, serverIp);
                        
                        return new DeviceExecutionResult(phoneId, "TIMEOUT", false, duration);
                        
                    } catch (Exception e) {
                        long duration = System.currentTimeMillis() - startTime;
                        log.error("{} - 任务执行异常，耗时: {}秒", phoneId, duration / 1000, e);
                        forceStopDevice(phoneId, serverIp);
                        return new DeviceExecutionResult(phoneId, "ERROR", false, duration);
                        
                    } finally {
                        singleDeviceExecutor.shutdownNow();
                    }
                });
                submittedCount++;
            }
            
            log.info("任务 {} 已提交 {} 个设备到线程池，开始按完成顺序处理结果", taskId, submittedCount);
            
            // 按完成顺序收集结果（每个设备都有自己的超时控制）
            int completedCount = 0;
            
            while (completedCount < submittedCount) {
                try {
                    // 等待下一个完成的任务，最多等待5分钟
                    // （因为单设备超时已经在提交任务时设置，这里只是等待结果）
                    Future<DeviceExecutionResult> future = completionService.poll(
                        5, 
                        TimeUnit.MINUTES
                    );
                    
                    if (future != null) {
                        DeviceExecutionResult result = future.get();
                        results.put(result.getPhoneId(), result.getStatus());
                        completedCount++;
                        
                        // 计算完成百分比
                        int progressPercent = (completedCount * 100) / submittedCount;
                        log.info("任务 {} 进度: {}/{} ({}%)，设备 {} 状态: {}，耗时: {}秒", 
                            taskId, completedCount, submittedCount, progressPercent,
                            result.getPhoneId(), result.getStatus(), result.getDuration() / 1000);
                    } else {
                        // poll超时，但设备任务还在执行中，继续等待
                        log.debug("任务 {} 等待设备完成，继续等待... ({}/{})", 
                            taskId, completedCount, submittedCount);
                    }
                    
                } catch (Exception e) {
                    log.error("任务 {} 获取设备执行结果异常", taskId, e);
                    completedCount++;
                }
            }
            
            executor.shutdown();
            
            // 等待线程池完全关闭（最多等待30秒）
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("任务 {} 线程池关闭超时，强制关闭", taskId);
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("任务 {} 等待线程池关闭被中断", taskId);
                executor.shutdownNow();
            }
            
            log.info("任务 {} 设备组执行完成，总计: {}，成功收集结果: {}", 
                taskId, submittedCount, results.size());
            
        } catch (Exception e) {
            log.error("任务 {} 设备组执行失败", taskId, e);
            // 强制关闭所有设备
            for (DeviceInfo device : devices) {
                forceStopDevice(device.getPhoneId(), serverIp);
            }
        }
        
        return results;
    }
    
    /**
     * 执行单个设备任务（核心流程）
     */
    private String executeSingleDevice(String taskId, DeviceInfo device, AutoNurtureConfig config) {
        String phoneId = device.getPhoneId();
        String serverIp = device.getServerIp();
        String pkgName = device.getPkgName();
        
        try {
            // 随机延迟
            Thread.sleep((long)(10000 + Math.random() * 2000));
            
            // ====== 1. 切换IP ======
            log.info("{} - 切换到closeli", phoneId);
            boolean switchSuccess = false;
            for (int retry = 0; retry < 5; retry++) {
                SshUtil.SshResult switchResult = sshCommand(serverIp, String.format(
                    "sudo bash /home/ubuntu/tiktok_switch_dynamic_ip_to_closeli.sh %s", phoneId));
                if (switchResult.isSuccess() && switchResult.getOutput().contains("切换到CLOSELI完成")) {
                    log.info("{} - 切换closeli成功", phoneId);
                    switchSuccess = true;
                    break;
                }
                Thread.sleep((long)(2000 + Math.random() * 3000));
            }
            
            if (!switchSuccess) {
                log.warn("{} - 切换closeli失败，继续执行", phoneId);
            }
            
            Thread.sleep((long)(10000 + Math.random() * 5000));
            
            // ====== 2. 检查IP地区 ======
            log.info("{} - 检查IP地区", phoneId);
            SshUtil.SshResult ipCheckResult = sshCommand(serverIp, String.format(
                "docker exec %s curl -s dynamicip.wumitech.com/json | jq -r .iso_code", phoneId));
            
            String country = "";
            if (ipCheckResult.isSuccess()) {
                country = ipCheckResult.getOutput().trim().replace("\"", "");
                log.info("{} - IP地区: {}", phoneId, country);
            }
            
            if (!config.getAllowedCountries().contains(country)) {
                log.error("{} - IP地区不符: {}，要求: {}", phoneId, country, config.getAllowedCountries());
                forceStopDevice(phoneId, serverIp);
                return "IP_FAILED";
            }
            
            // ====== 3. 上传视频（如果需要） ======
            if (config.getUploadVideo() && "com.zhiliaoapp.musically".equals(pkgName)) {
                log.info("{} - 上传视频", phoneId);
                // 视频已在分发阶段准备好，这里只需要调用上传
                String uploadCmd = String.format(
                    "cd /data/appium/com_zhiliaoapp_musically/zl && " +
                    "python3 -c \"from TTTest_create_dev import autoUploadVedio; " +
                    "autoUploadVedio('%s', '%s', 'auto_video')\"",
                    phoneId, serverIp
                );
                sshCommand(SCRIPT_HOST, uploadCmd);
            }
            
            // ====== 4. 养号任务（关注 + 浏览，随机顺序） ======
            String followStatus = "SUCCESS";
            boolean browseVideoSuccess = false; // 标记刷视频是否成功
            
            if (config.getDeviceStatus() == 2) {
                // 黑名单账号，只记录状态
                log.info("{} - 黑名单账号，跳过养号任务", phoneId);
                followStatus = "BLACK_LIST";
            } else {
                // 随机决定执行顺序（模拟真实用户行为）
                boolean followFirst = Math.random() < 0.5;
                
                if (followFirst) {
                    // 先关注，后浏览
                    log.info("{} - 执行顺序: 关注 → 浏览视频", phoneId);
                    
                    log.info("{} - 执行关注任务", phoneId);
                    followStatus = executeFollowTask(phoneId, serverIp, pkgName);
                    
                    log.info("{} - 浏览视频", phoneId);
                    String browseStatus = executeBrowseTask(phoneId, serverIp, pkgName);
                    
                    if ("LOG_OUT".equals(browseStatus)) {
                        followStatus = "LOG_OUT";
                    } else if ("SUCCESS".equals(browseStatus)) {
                        browseVideoSuccess = true; // 刷视频成功
                    }
                } else {
                    // 先浏览，后关注
                    log.info("{} - 执行顺序: 浏览视频 → 关注", phoneId);
                    
                    log.info("{} - 浏览视频", phoneId);
                    String browseStatus = executeBrowseTask(phoneId, serverIp, pkgName);
                    
                    if ("LOG_OUT".equals(browseStatus)) {
                        followStatus = "LOG_OUT";
                    } else {
                        if ("SUCCESS".equals(browseStatus)) {
                            browseVideoSuccess = true; // 刷视频成功
                        }
                        log.info("{} - 执行关注任务", phoneId);
                        followStatus = executeFollowTask(phoneId, serverIp, pkgName);
                    }
                }
            }
            
            // ====== 5. 检查上传状态（如果上传了视频） ======
            if (config.getUploadVideo() && "com.zhiliaoapp.musically".equals(pkgName)) {
                log.info("{} - 检查上传状态", phoneId);
                for (int retry = 0; retry < 3; retry++) {
                    String checkUploadCmd = String.format(
                        "cd /data/appium/com_zhiliaoapp_musically/zl && " +
                        "python3 -c \"from TTTest_create_dev import isuploaded; " +
                        "result = isuploaded('%s', '%s'); print(result)\"",
                        phoneId, serverIp
                    );
                    SshUtil.SshResult uploadCheckResult = sshCommand(SCRIPT_HOST, checkUploadCmd);
                    if (uploadCheckResult.isSuccess() && uploadCheckResult.getOutput().contains("True")) {
                        log.info("{} - 视频上传成功", phoneId);
                        break;
                    }
                    Thread.sleep(5000);
                }
            }
            
            // ====== 6. 更新数据库（刷视频天数+1） ======
            if (browseVideoSuccess) {
                try {
                    log.info("{} - 刷视频成功，更新数据库：video_days + 1", phoneId);
                    accountRepository.updateVideoDaysByPhoneId(phoneId);
                    log.info("{} - 数据库更新成功", phoneId);
                } catch (Exception e) {
                    log.error("{} - 更新数据库失败", phoneId, e);
                }
            }
            
            // ====== 7. 清理与关闭 ======
            log.info("{} - 关闭应用", phoneId);
            closeApp(phoneId, serverIp);
            
            log.info("{} - 关闭手机", phoneId);
            sshCommand(serverIp, String.format(
                "sudo bash /home/ubuntu/tiktok_phone_stop.sh %s", phoneId));
            
            log.info("{} - 任务完成，最终状态: {}", phoneId, followStatus);
            return followStatus;
            
        } catch (Exception e) {
            log.error("{} - 任务执行异常", phoneId, e);
            forceStopDevice(phoneId, serverIp);
            return "ERROR";
        }
    }
    
    /**
     * 执行关注任务（调用Python脚本）
     */
    private String executeFollowTask(String phoneId, String serverIp, String pkgName) {
        try {
            String moduleDir = "com.tiktok.lite.go".equals(pkgName) 
                ? "/data/appium/com_tiktok_lite_go"
                : "/data/appium/com_zhiliaoapp_musically/zl";
            
            String moduleName = "com.tiktok.lite.go".equals(pkgName)
                ? "TT_fast_follow"
                : "TT_fast_follow_0902";
            
            String followCmd = String.format(
                "cd %s && " +
                "python3 -c \"from %s import fast_follow; " +
                "ret = fast_follow('%s', '%s'); print(ret)\"",
                moduleDir, moduleName, phoneId, serverIp
            );
            
            SshUtil.SshResult followResult = sshCommand(SCRIPT_HOST, followCmd);
            
            if (followResult.isSuccess()) {
                String output = followResult.getOutput().trim();
                log.info("{} - 关注任务返回: {}", phoneId, output);
                
                if (output.contains("1")) {
                    return "FOLLOW_SUCCESS";
                } else if (output.contains("0")) {
                    return "FOLLOW_FAILED";
                } else if (output.contains("2")) {
                    return "FOLLOW_ALL";
                } else {
                    return "FOLLOW_ERROR";
                }
            } else {
                log.error("{} - 关注任务执行失败: {}", phoneId, followResult.getErrorMessage());
                return "FOLLOW_ERROR";
            }
            
        } catch (Exception e) {
            log.error("{} - 关注任务异常", phoneId, e);
            return "FOLLOW_ERROR";
        }
    }
    
    /**
     * 执行浏览视频任务（调用Python脚本）
     */
    private String executeBrowseTask(String phoneId, String serverIp, String pkgName) {
        try {
            // 浏览视频2轮
            for (int round = 0; round < 2; round++) {
                log.info("{} - 浏览视频第 {}/2 轮", phoneId, round + 1);
                
                String browseCmd = String.format(
                    "cd /data/appium/com_zhiliaoapp_musically/zl && " +
                    "python3 -c \"from TTTest_browse_video_main_dev import worker; " +
                    "ret = worker('%s', '%s', '%s'); print(ret)\"",
                    phoneId, serverIp, pkgName
                );
                
                SshUtil.SshResult browseResult = sshCommand(SCRIPT_HOST, browseCmd);
                
                // 获取返回值
                String output = browseResult.getOutput() != null ? browseResult.getOutput().trim() : "";
                
                log.info("{} - 浏览视频第 {} 轮执行结果:", phoneId, round + 1);
                log.info("  - 返回值: '{}'", output);
                log.info("  - 退出码: {}", browseResult.isSuccess() ? "0(成功)" : "非0(失败)");
                
                // 判断返回值类型
                // False: 账号被封/登出，严重问题，记录为 LOG_OUT
                // True: 完整执行所有任务（刷视频 + 搜索 + 刷直播）
                // None: 部分完成（只完成刷视频，未完成刷直播）
                
                if ("False".equals(output)) {
                    log.warn("{} - 账号已封禁/登出 (返回False)", phoneId);
                    return "LOG_OUT";
                } else if ("True".equals(output)) {
                    log.info("{} - 浏览视频第 {} 轮完整执行 (返回True)", phoneId, round + 1);
                    break; // 完整执行，退出循环
                } else if ("None".equals(output)) {
                    log.info("{} - 浏览视频第 {} 轮部分完成 (返回None)", phoneId, round + 1);
                    break; // 部分完成也算正常，退出循环
                } else {
                    // 其他情况（空字符串、异常等），尝试第2轮
                    log.warn("{} - 浏览视频第 {} 轮返回未知值: '{}', 尝试下一轮", 
                        phoneId, round + 1, output);
                }
            }
            
            return "SUCCESS";
            
        } catch (Exception e) {
            log.error("{} - 浏览视频异常", phoneId, e);
            return "ERROR";
        }
    }
    
    /**
     * 关闭应用
     */
    private void closeApp(String phoneId, String serverIp) {
        try {
            String closeCmd = String.format(
                "cd /data/appium/com_zhiliaoapp_musically/zl && " +
                "python3 -c \"from TTTest_create_dev import close_app; " +
                "close_app('%s', '%s')\"",
                phoneId, serverIp
            );
            sshCommand(SCRIPT_HOST, closeCmd);
        } catch (Exception e) {
            log.error("{} - 关闭应用失败", phoneId, e);
        }
    }
    
    /**
     * 强制停止设备
     */
    private void forceStopDevice(String phoneId, String serverIp) {
        try {
            log.info("{} - 强制停止设备", phoneId);
            closeApp(phoneId, serverIp);
            sshCommand(serverIp, String.format(
                "sudo bash /home/ubuntu/tiktok_phone_stop.sh %s", phoneId));
        } catch (Exception e) {
            log.error("{} - 强制停止失败", phoneId, e);
        }
    }
    
    /**
     * 统一SSH命令执行
     */
    private SshUtil.SshResult sshCommand(String targetHost, String command) {
        String username = "root";
        if (targetHost.equals("10.7.107.224")) {
            username = "ubuntu";
        }
        
        return SshUtil.executeCommandWithPrivateKey(
            targetHost, 
            22, 
            username,
            sshProperties.getSshPrivateKey(),
            sshProperties.getSshPassphrase(),
            command, 
            300,
            sshProperties.getSshJumpHost(),
            sshProperties.getSshJumpPort(),
            sshProperties.getSshJumpUsername(),
            sshProperties.getSshJumpPassword()
        );
    }
    
    /**
     * 分割列表为指定大小的子列表
     */
    private <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return result;
    }
    
    /**
     * 更新任务进度
     */
    @SuppressWarnings("unchecked")
    private void updateTaskProgress(String taskId, int progress, Map<String, Integer> summary) {
        Map<String, Object> taskStatus = (Map<String, Object>) redisTemplate.opsForValue().get(TASK_KEY_PREFIX + taskId);
        if (taskStatus != null) {
            taskStatus.put("progress", progress);
            taskStatus.put("summary", summary);
            taskStatus.put("lastUpdate", LocalDateTime.now().toString());
            redisTemplate.opsForValue().set(TASK_KEY_PREFIX + taskId, taskStatus, 24, TimeUnit.HOURS);
        }
    }
    
    /**
     * 更新任务字段
     */
    @SuppressWarnings("unchecked")
    private void updateTaskField(String taskId, String field, Object value) {
        Map<String, Object> taskStatus = (Map<String, Object>) redisTemplate.opsForValue().get(TASK_KEY_PREFIX + taskId);
        if (taskStatus != null) {
            taskStatus.put(field, value);
            redisTemplate.opsForValue().set(TASK_KEY_PREFIX + taskId, taskStatus, 24, TimeUnit.HOURS);
        }
    }
}

