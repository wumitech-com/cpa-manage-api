package com.cpa.service;

import com.cpa.config.SshProperties;
import com.cpa.entity.TtAccountRegister;
import com.cpa.entity.TtRegisterTask;
import com.cpa.entity.TtRetentionRecord;
import com.cpa.repository.TtAccountRegisterRepository;
import com.cpa.repository.TtRegisterTaskRepository;
import com.cpa.repository.TtRetentionRecordRepository;
import com.cpa.util.SshUtil;
import com.cpa.util.SshConnectionPool;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TT账号批量注册服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TtRegisterService {

    private final SshProperties sshProperties;
    private final ApiService apiService;
    private final TtAccountRegisterRepository ttAccountRegisterRepository;
    private final TtRegisterTaskRepository ttRegisterTaskRepository;
    private final TtRetentionRecordRepository ttRetentionRecordRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${tt-register.default-image:}")
    private String defaultImage;
    
    @Value("${tt-register.default-dynamic-ip-channel:closeli}")
    private String defaultDynamicIpChannel;
    
    @Value("${tt-register.default-static-ip-channel:}")
    private String defaultStaticIpChannel;

    /** 留存任务脚本名称（与注册脚本同目录 /data/appium/com_zhiliaoapp_musically/） */
    @Value("${tt-register.retention-script-name:fast_retention_noappium_swipe.py}")
    private String retentionScriptName;
    
    // 主板机环境变量配置 - 已注释（云手机部署）
    // @Value("${MAINBOARD_PUBLIC_IP:206.119.108.2}")
    // private String mainboardPublicIp;
    
    // @Value("${MAINBOARD_APPIUM_SERVER:10.7.124.25}")
    // private String mainboardAppiumServer;
    
    // 任务信息存储（taskId -> TaskInfo）
    private final Map<String, TaskInfo> taskInfoMap = new ConcurrentHashMap<>();
    
    // 多线程池配置：分离不同类型的任务
    // 1. 并行注册线程池（用于多设备并行注册）
    private static final AtomicInteger threadCounter = new AtomicInteger(0);
    private final ExecutorService parallelRegisterExecutor = Executors.newFixedThreadPool(200, 
        r -> {
            Thread t = new Thread(r, "parallel-register-" + threadCounter.incrementAndGet());
            t.setDaemon(false);
            return t;
        });
    
    // 2. SSH命令执行线程池（用于SSH相关操作）
    private final ExecutorService sshCommandExecutor = Executors.newFixedThreadPool(100,
        r -> {
            Thread t = new Thread(r, "ssh-command-" + System.currentTimeMillis());
            t.setDaemon(false);
            return t;
        });
    
    // 3. 定时任务线程池（用于定时调度）
    private final ExecutorService scheduledTaskExecutor = Executors.newScheduledThreadPool(10,
        r -> {
            Thread t = new Thread(r, "scheduled-task-" + System.currentTimeMillis());
            t.setDaemon(false);
            return t;
        });
    
    // 4. ResetPhoneEnv接口调用并发控制信号量（按服务器分组，不同服务器可以并行）
    // 限制每个服务器同时调用ResetPhoneEnv的数量，避免服务器压力过大
    private static final int MAX_RESET_PHONE_ENV_CONCURRENCY_PER_SERVER = 10; // 每个服务器最多同时10个ResetPhoneEnv调用
    // 为每个服务器创建独立的信号量，不同服务器的调用可以并行
    private static final ConcurrentHashMap<String, Semaphore> resetPhoneEnvSemaphores = new ConcurrentHashMap<>();
    
    // 5. Appium服务器端口池（用于随机选择，避免单点瓶颈）
    // 根据实际运行的Appium服务器端口范围定义（4723-4781）
    private static final int[] APPIUM_PORTS = {
        4723, 4724, 4725, 4726, 4727, 4728, 4729, 4730, 4731, 4732, 4733, 4734, 4735, 4736, 4737, 4738, 4739,
        4740, 4741, 4742, 4743, 4744, 4745, 4746, 4747, 4748, 4749, 4750, 4751, 4752, 4753, 4754, 4755, 4756, 4757, 4758, 4759,
        4760, 4761, 4762, 4763, 4764, 4765, 4766, 4767, 4768, 4769, 4770, 4771, 4772, 4773, 4774, 4775, 4776, 4777, 4778, 4779, 4780, 4781
    };
    
    /**
     * 随机选择一个Appium服务器端口
     * @return Appium服务器地址，格式：127.0.0.1:端口
     */
    private String getRandomAppiumServer() {
        int randomIndex = ThreadLocalRandom.current().nextInt(APPIUM_PORTS.length);
        int port = APPIUM_PORTS[randomIndex];
        String appiumServer = "127.0.0.1:" + port;
        log.debug("随机选择Appium服务器端口: {}", appiumServer);
        return appiumServer;
    }
    
    // 5. 已停止的任务ID集合（内存中快速检查，避免数据库查询延迟）
    private static final Set<String> stoppedTaskIds = ConcurrentHashMap.newKeySet();
    
    // 6. 设备执行锁（按设备ID，防止同一设备被多个任务同时操作）
    private static final ConcurrentHashMap<String, Semaphore> deviceLocks = new ConcurrentHashMap<>();

    /**
     * 本机留存去重缓存：同一账号在 24 小时内只会被本 JVM 处理一次留存
     * key: account_register_id, value: 首次处理时间
     */
    private final Cache<Long, Instant> retentionProcessingCache =
            CacheBuilder.newBuilder()
                    .expireAfterWrite(24, TimeUnit.HOURS)
                    .build();

    /**
     * 若数据库中任务已被置为 STOPPED（如手动改库），则同步到内存并返回 true，调用方应停止执行。
     * 返回 true 时已设置 task.setStatus("STOPPED") 并加入 stoppedTaskIds，调用方应 updateById(task) 后退出。
     */
    private boolean syncStoppedFromDb(TtRegisterTask task) {
        TtRegisterTask db = ttRegisterTaskRepository.selectById(task.getId());
        if (db != null && "STOPPED".equals(db.getStatus())) {
            task.setStatus("STOPPED");
            task.setUpdatedAt(LocalDateTime.now());
            stoppedTaskIds.add(task.getTaskId());
            return true;
        }
        return false;
    }

    /**
     * 根据账号的安卓版本调整镜像路径中的 androidXX 段（例如 android13_cpu、android14_cpu），保持与备份时版本一致。
     * 只替换 android+数字+下划线 这一段，仓库、tag 等保持不变。
     */
    private String adjustImagePathForAndroidVersion(String imagePath, String androidVersion) {
        if (imagePath == null || imagePath.isEmpty() || androidVersion == null || androidVersion.isEmpty()) {
            return imagePath;
        }
        // 提取数字部分，例如 "14"、"15"；如果没有数字则不处理
        String digits = androidVersion.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return imagePath;
        }
        String target = "android" + digits + "_";
        // 将 androidXX_ 替换成目标版本
        return imagePath.replaceAll("android\\d+_", target);
    }
    
    /**
     * 获取指定服务器的ResetPhoneEnv调用信号量
     * @param serverIp 服务器IP
     * @return 信号量
     */
    private static Semaphore getResetPhoneEnvSemaphore(String serverIp) {
        return resetPhoneEnvSemaphores.computeIfAbsent(serverIp, 
            k -> new Semaphore(MAX_RESET_PHONE_ENV_CONCURRENCY_PER_SERVER, true));
    }
    
    /**
     * 定时任务：每1分钟查询并执行待执行的任务
     */
    @Scheduled(fixedRate = 60 * 1000) // 每60秒（1分钟）执行一次
    public void scheduledExecutePendingTasks() {
        try {
            // 查询 PENDING 状态的云手机注册任务（task_kind 为 REGISTER 或 null，device_type 为 CLOUD_PHONE 或 NULL）
            LambdaQueryWrapper<TtRegisterTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TtRegisterTask::getStatus, "PENDING");
            wrapper.and(w -> w.isNull(TtRegisterTask::getTaskKind).or().eq(TtRegisterTask::getTaskKind, "REGISTER"));
            wrapper.and(w -> w.isNull(TtRegisterTask::getDeviceType)
                    .or()
                    .eq(TtRegisterTask::getDeviceType, "CLOUD_PHONE"));
            wrapper.orderByDesc(TtRegisterTask::getCreatedAt);
            List<TtRegisterTask> pendingTasks = ttRegisterTaskRepository.selectList(wrapper);
            
            if (pendingTasks.isEmpty()) {
                log.debug("定时任务检查：没有待执行的云手机注册任务");
                return;
            }
            
            log.info("定时任务检查：发现 {} 个待执行的云手机注册任务，开始执行", pendingTasks.size());
            
            // 使用默认并发数 10
            int maxConcurrency = 10;
            Semaphore semaphore = new Semaphore(maxConcurrency);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (TtRegisterTask task : pendingTasks) {
                // 双重检查：确保任务状态仍然是 PENDING（可能被手动改为 STOPPED）
                TtRegisterTask currentTask = ttRegisterTaskRepository.selectById(task.getId());
                if (currentTask == null) {
                    log.debug("任务 {} 不存在，跳过", task.getTaskId());
                    continue;
                }
                
                // 如果任务状态不是 PENDING（可能被手动改为 STOPPED），则跳过
                if (!"PENDING".equals(currentTask.getStatus())) {
                    log.debug("任务 {} 当前状态为 {}，不是 PENDING，跳过执行", 
                            task.getTaskId(), currentTask.getStatus());
                    continue;
                }
                
                // 检查是否在停止集合中（如果用户调用了停止接口，但状态还没更新到数据库）
                if (stoppedTaskIds.contains(task.getTaskId())) {
                    log.debug("任务 {} 在停止集合中，跳过执行", task.getTaskId());
                    // 同步更新数据库状态
                    currentTask.setStatus("STOPPED");
                    currentTask.setUpdatedAt(LocalDateTime.now());
                    ttRegisterTaskRepository.updateById(currentTask);
                    continue;
                }
                
                // 检查设备是否正在被其他任务使用（防止同一设备被多个任务同时操作）
                String phoneId = task.getPhoneId();
                Semaphore deviceLock = deviceLocks.computeIfAbsent(phoneId, k -> new Semaphore(1, true));
                if (!deviceLock.tryAcquire()) {
                    log.warn("设备 {} 正在被其他任务使用，跳过任务 {} (taskId={})", phoneId, task.getTaskId(), task.getTaskId());
                    continue;
                }
                
                // 尝试获取信号量
                if (!semaphore.tryAcquire()) {
                    log.debug("并发数已达上限，等待执行任务: taskId={}", task.getTaskId());
                    deviceLock.release(); // 释放设备锁
                    continue;
                }
                
                // 更新任务状态为 RUNNING
                task.setStatus("RUNNING");
                task.setUpdatedAt(LocalDateTime.now());
                ttRegisterTaskRepository.updateById(task);
                
                final TtRegisterTask finalTask = task;
                final Semaphore finalDeviceLock = deviceLock; // 保存设备锁引用
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        log.info("开始执行任务: taskId={}, taskType={}, phoneId={}, targetCount={}", 
                                finalTask.getTaskId(), finalTask.getTaskType(), 
                                finalTask.getPhoneId(), finalTask.getTargetCount());
                        
                        // 构建 resetParams Map
                        Map<String, String> resetParams = new HashMap<>();
                        if (finalTask.getCountry() != null) resetParams.put("country", finalTask.getCountry());
                        if (finalTask.getSdk() != null) resetParams.put("sdk", finalTask.getSdk());
                        if (finalTask.getImagePath() != null) resetParams.put("imagePath", finalTask.getImagePath());
                        if (finalTask.getGaidTag() != null) resetParams.put("gaidTag", finalTask.getGaidTag());
                        if (finalTask.getDynamicIpChannel() != null) resetParams.put("dynamicIpChannel", finalTask.getDynamicIpChannel());
                        if (finalTask.getStaticIpChannel() != null) resetParams.put("staticIpChannel", finalTask.getStaticIpChannel());
                        if (finalTask.getBiz() != null) resetParams.put("biz", finalTask.getBiz());
                        if (finalTask.getAppiumServer() != null) resetParams.put("appiumServer", finalTask.getAppiumServer());
                        
                        // 根据任务类型确定 emailMode
                        String emailMode;
                        if ("FAKE_EMAIL".equals(finalTask.getTaskType())) {
                            emailMode = "random";
                        } else if ("REAL_EMAIL".equals(finalTask.getTaskType())) {
                            emailMode = "outlook";
                        } else {
                            log.error("未知的任务类型: taskId={}, taskType={}", finalTask.getTaskId(), finalTask.getTaskType());
                            finalTask.setUpdatedAt(LocalDateTime.now());
                            ttRegisterTaskRepository.updateById(finalTask);
                            return;
                        }
                        
                        // 统一执行任务（根据 taskType 传递对应的 emailMode）
                        executeTaskForDevice(finalTask, resetParams, emailMode);
                    } catch (Exception e) {
                        log.error("执行任务异常: taskId={}", finalTask.getTaskId(), e);
                        finalTask.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(finalTask);
                    } finally {
                        // 释放设备锁
                        finalDeviceLock.release();
                        // 释放任务并发信号量
                        semaphore.release();
                    }
                }, parallelRegisterExecutor);
                
                futures.add(future);
            }
            
            // 异步执行，不阻塞定时任务
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenRun(() -> {
                        log.info("定时任务：本次检查完成，共执行 {} 个任务", futures.size());
                    })
                    .exceptionally(e -> {
                        log.error("定时任务执行异常", e);
                        return null;
                    });
            
        } catch (Exception e) {
            log.error("定时任务检查待执行任务时出错", e);
        }
    }
    
    /**
     * 定时任务：每1分钟查询并执行主板机养号任务
     */
    // @Scheduled(fixedRate = 60 * 1000) // 每60秒（1分钟）执行一次 - 已注释（云手机部署）
    public void scheduledExecuteMainboardTasks() {
        try {
            // 查询 PENDING 状态的主板机任务
            List<TtRegisterTask> pendingTasks = ttRegisterTaskRepository.findByDeviceTypeAndStatus("MAINBOARD", "PENDING");
            
            if (pendingTasks.isEmpty()) {
                log.debug("定时任务检查：没有待执行的主板机养号任务");
                return;
            }
            
            log.info("定时任务检查：发现 {} 个待执行的主板机养号任务，开始执行", pendingTasks.size());
            
            // 使用默认并发数 10
            int maxConcurrency = 10;
            Semaphore semaphore = new Semaphore(maxConcurrency);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (TtRegisterTask task : pendingTasks) {
                // 双重检查：确保任务状态仍然是 PENDING（可能被手动改为 STOPPED）
                TtRegisterTask currentTask = ttRegisterTaskRepository.selectById(task.getId());
                if (currentTask == null) {
                    log.debug("主板机任务 {} 不存在，跳过", task.getTaskId());
                    continue;
                }
                
                // 如果任务状态不是 PENDING（可能被手动改为 STOPPED），则跳过
                if (!"PENDING".equals(currentTask.getStatus())) {
                    log.debug("主板机任务 {} 当前状态为 {}，不是 PENDING，跳过执行", 
                            task.getTaskId(), currentTask.getStatus());
                    continue;
                }
                
                // 检查是否在停止集合中
                if (stoppedTaskIds.contains(task.getTaskId())) {
                    log.debug("主板机任务 {} 在停止集合中，跳过执行", task.getTaskId());
                    currentTask.setStatus("STOPPED");
                    currentTask.setUpdatedAt(LocalDateTime.now());
                    ttRegisterTaskRepository.updateById(currentTask);
                    continue;
                }
                
                // 检查设备是否正在被其他任务使用
                String phoneId = task.getPhoneId();
                Semaphore deviceLock = deviceLocks.computeIfAbsent(phoneId, k -> new Semaphore(1, true));
                if (!deviceLock.tryAcquire()) {
                    log.warn("主板机设备 {} 正在被其他任务使用，跳过任务 {} (taskId={})", phoneId, task.getTaskId(), task.getTaskId());
                    continue;
                }
                
                // 尝试获取信号量
                if (!semaphore.tryAcquire()) {
                    log.debug("并发数已达上限，等待执行主板机任务: taskId={}", task.getTaskId());
                    deviceLock.release(); // 释放设备锁
                    continue;
                }
                
                // 更新任务状态为 RUNNING
                task.setStatus("RUNNING");
                task.setUpdatedAt(LocalDateTime.now());
                ttRegisterTaskRepository.updateById(task);
                
                final TtRegisterTask finalTask = task;
                final Semaphore finalDeviceLock = deviceLock;
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        log.info("开始执行主板机养号任务: taskId={}, taskType={}, phoneId={}, targetCount={}", 
                                finalTask.getTaskId(), finalTask.getTaskType(), 
                                finalTask.getPhoneId(), finalTask.getTargetCount());
                        
                        // 根据任务类型确定 emailMode
                        String emailMode;
                        if ("FAKE_EMAIL".equals(finalTask.getTaskType())) {
                            emailMode = "random";
                        } else if ("REAL_EMAIL".equals(finalTask.getTaskType())) {
                            emailMode = "outlook";
                        } else {
                            log.error("未知的主板机任务类型: taskId={}, taskType={}", finalTask.getTaskId(), finalTask.getTaskType());
                            finalTask.setUpdatedAt(LocalDateTime.now());
                            ttRegisterTaskRepository.updateById(finalTask);
                            return;
                        }
                        
                        // 执行主板机养号任务
                        executeMainboardTaskForDevice(finalTask, emailMode);
                    } catch (Exception e) {
                        log.error("执行主板机任务异常: taskId={}", finalTask.getTaskId(), e);
                        finalTask.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(finalTask);
                    } finally {
                        // 释放设备锁
                        finalDeviceLock.release();
                        // 释放任务并发信号量
                        semaphore.release();
                    }
                }, parallelRegisterExecutor);
                
                futures.add(future);
            }
            
            // 异步执行，不阻塞定时任务
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenRun(() -> {
                        log.info("定时任务：本次主板机养号任务检查完成，共执行 {} 个任务", futures.size());
                    })
                    .exceptionally(e -> {
                        log.error("定时任务执行主板机养号任务异常", e);
                        return null;
                    });
            
        } catch (Exception e) {
            log.error("定时任务检查主板机养号任务时出错", e);
        }
    }

    /**
     * 定时任务：每1分钟查询并执行待执行的留存任务（task_kind=RETENTION）
     */
    @Scheduled(fixedRate = 60 * 1000)
    public void scheduledExecuteRetentionTasks() {
        try {
            List<TtRegisterTask> pendingTasks = ttRegisterTaskRepository.findPendingRetentionTasks();
            if (pendingTasks.isEmpty()) {
                log.debug("定时任务检查：没有待执行的留存任务");
                return;
            }
            log.info("定时任务检查：发现 {} 个待执行的留存任务，开始执行", pendingTasks.size());
            int maxConcurrency = 10;
            Semaphore semaphore = new Semaphore(maxConcurrency);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (TtRegisterTask task : pendingTasks) {
                TtRegisterTask currentTask = ttRegisterTaskRepository.selectById(task.getId());
                if (currentTask == null || !"PENDING".equals(currentTask.getStatus())) continue;
                if (stoppedTaskIds.contains(task.getTaskId())) {
                    currentTask.setStatus("STOPPED");
                    currentTask.setUpdatedAt(LocalDateTime.now());
                    ttRegisterTaskRepository.updateById(currentTask);
                    continue;
                }
                String phoneId = task.getPhoneId();
                Semaphore deviceLock = deviceLocks.computeIfAbsent(phoneId, k -> new Semaphore(1, true));
                if (!deviceLock.tryAcquire()) continue;
                if (!semaphore.tryAcquire()) {
                    deviceLock.release();
                    continue;
                }
                task.setStatus("RUNNING");
                task.setUpdatedAt(LocalDateTime.now());
                ttRegisterTaskRepository.updateById(task);
                final TtRegisterTask finalTask = task;
                final Semaphore finalDeviceLock = deviceLock;
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        log.info("开始执行留存任务: taskId={}, phoneId={}", finalTask.getTaskId(), finalTask.getPhoneId());
                        executeRetentionTaskForDevice(finalTask);
                    } catch (Exception e) {
                        log.error("执行留存任务异常: taskId={}", finalTask.getTaskId(), e);
                        finalTask.setStatus("FAILED");
                        finalTask.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(finalTask);
                    } finally {
                        finalDeviceLock.release();
                        semaphore.release();
                    }
                }, parallelRegisterExecutor);
                futures.add(future);
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenRun(() -> log.info("定时任务：本次留存任务检查完成，共执行 {} 个任务", futures.size()))
                    .exceptionally(e -> { log.error("定时任务执行留存任务异常", e); return null; });
        } catch (Exception e) {
            log.error("定时任务检查留存任务时出错", e);
        }
    }
    
    /**
     * 应用启动时恢复未完成的任务并自动执行
     */
    @PostConstruct
    public void recoverTasks() {
        try {
            List<TtRegisterTask> pendingTasks = ttRegisterTaskRepository.findByStatusIn(
                Arrays.asList("PENDING", "RUNNING")
            );
            
            if (pendingTasks.isEmpty()) {
                log.info("没有需要恢复的任务");
                return;
            }
            
            log.info("发现 {} 个未完成的任务，准备恢复并执行", pendingTasks.size());
            
            // 将 RUNNING 状态的任务重置为 PENDING（因为应用重启了）
            for (TtRegisterTask task : pendingTasks) {
                if ("RUNNING".equals(task.getStatus())) {
                    task.setStatus("PENDING");
                    task.setUpdatedAt(LocalDateTime.now());
                    ttRegisterTaskRepository.updateById(task);
                    log.info("重置任务状态: taskId={}, phoneId={}, 从 RUNNING 改为 PENDING", 
                            task.getTaskId(), task.getPhoneId());
                }
            }
            
            // 延迟3秒后执行定时任务逻辑，确保应用完全启动，并统一使用定时任务的执行逻辑
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(3000); // 等待3秒，确保应用完全启动
                    log.info("应用启动后延迟执行待执行任务（使用定时任务逻辑）");
                    scheduledExecutePendingTasks(); // 执行云手机任务
                    // scheduledExecuteMainboardTasks(); // 执行主板机任务 - 已注释（云手机部署）
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("延迟执行任务被中断", e);
                } catch (Exception e) {
                    log.error("延迟执行任务时出错", e);
                }
            }, parallelRegisterExecutor);
            
            log.info("任务恢复完成，将在3秒后通过定时任务逻辑执行");
        } catch (Exception e) {
            log.error("恢复任务时出错", e);
        }
    }
    
    /**
     * 创建任务记录并保存到数据库
     */
    private TtRegisterTask createTaskRecord(String taskType, String serverIp, String phoneId, 
                                           Integer targetCount, String tiktokVersionDir, 
                                           Map<String, String> resetParams) {
        String taskId = String.format("%s_%s_%d", taskType, phoneId, System.currentTimeMillis());
        
        TtRegisterTask task = new TtRegisterTask();
        task.setTaskId(taskId);
        task.setTaskType(taskType);
        task.setTaskKind("REGISTER");
        task.setServerIp(serverIp);
        task.setPhoneId(phoneId);
        task.setTargetCount(targetCount != null ? targetCount : 1);
        task.setTiktokVersionDir(tiktokVersionDir);
        task.setStatus("PENDING");
        
        // 设置 ResetPhoneEnv 参数
        if (resetParams != null) {
            task.setCountry(resetParams.getOrDefault("country", ""));
            task.setSdk(resetParams.getOrDefault("sdk", "33"));
            task.setImagePath(resetParams.getOrDefault("imagePath", ""));
            task.setGaidTag(resetParams.getOrDefault("gaidTag", ""));
            task.setDynamicIpChannel(resetParams.getOrDefault("dynamicIpChannel", ""));
            task.setStaticIpChannel(resetParams.getOrDefault("staticIpChannel", ""));
            task.setBiz(resetParams.getOrDefault("biz", ""));
            // 设置device_type（如果提供）
            String deviceType = resetParams.getOrDefault("deviceType", "");
            if (deviceType != null && !deviceType.isEmpty()) {
                task.setDeviceType(deviceType);
            }
            // 设置adb_port（主板机使用）
            task.setAdbPort(resetParams.getOrDefault("adbPort", ""));
        }
        
        // 选择 Appium 服务器（负载均衡）
        String appiumServer = selectAppiumServerWithLoadBalance();
        task.setAppiumServer(appiumServer);
        log.info("为任务选择 Appium 服务器: {}", appiumServer);
        
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        
        // 保存到数据库
        ttRegisterTaskRepository.insert(task);
        log.info("创建任务记录: taskId={}, taskType={}, phoneId={}, targetCount={}", 
                taskId, taskType, phoneId, task.getTargetCount());
        
        return task;
    }

    /**
     * 创建留存任务并保存到数据库（task_kind=RETENTION）
     * @param phoneId 云手机ID
     * @param serverIp 服务器IP
     * @param targetCount 要处理的账号数量（从三天前符合条件的账号中随机取）
     * @param country 国家代码，默认 US
     * @param imagePath 镜像路径，可为空（执行时自动获取）
     */
    public TtRegisterTask createRetentionTask(String phoneId, String serverIp, Integer targetCount,
                                             String country, String imagePath) {
        String taskId = "RETENTION_" + phoneId + "_" + System.currentTimeMillis();
        TtRegisterTask task = new TtRegisterTask();
        task.setTaskId(taskId);
        task.setTaskType("RETENTION");
        task.setTaskKind("RETENTION");
        task.setServerIp(serverIp);
        task.setPhoneId(phoneId);
        task.setTargetCount(targetCount != null && targetCount > 0 ? targetCount : 50);
        task.setCountry(country != null && !country.isEmpty() ? country : "US");
        task.setImagePath(imagePath);
        task.setStatus("PENDING");
        task.setDeviceType("CLOUD_PHONE");
        String appiumServer = selectAppiumServerWithLoadBalance();
        task.setAppiumServer(appiumServer);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        ttRegisterTaskRepository.insert(task);
        log.info("创建留存任务: taskId={}, phoneId={}, targetCount={}, country={}", taskId, phoneId, task.getTargetCount(), task.getCountry());
        return task;
    }
    
    /**
     * 从数据库读取 PENDING 状态的任务并执行
     * @param maxConcurrency 最大并发数
     */
    @Async
    public void executeTasksFromDatabase(int maxConcurrency) {
        try {
            // 创建信号量控制并发数
            Semaphore semaphore = new Semaphore(maxConcurrency);
            
            // 持续从数据库读取并执行任务
            while (true) {
                // 查询 PENDING 状态的任务
                List<TtRegisterTask> pendingTasks = ttRegisterTaskRepository.findByStatus("PENDING");
                
                if (pendingTasks.isEmpty()) {
                    // 如果没有待执行的任务，等待一段时间后重试
                    Thread.sleep(5000); // 等待5秒
                    continue;
                }
                
                log.info("发现 {} 个待执行的任务，开始执行", pendingTasks.size());
                
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                
                for (TtRegisterTask task : pendingTasks) {
                    // 尝试获取信号量
                    if (!semaphore.tryAcquire()) {
                        log.debug("并发数已达上限，等待执行任务: taskId={}", task.getTaskId());
                        continue;
                    }
                    
                    // 更新任务状态为 RUNNING
                    task.setStatus("RUNNING");
                    task.setUpdatedAt(LocalDateTime.now());
                    ttRegisterTaskRepository.updateById(task);
                    
                    final TtRegisterTask finalTask = task;
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            log.info("开始执行任务: taskId={}, taskType={}, phoneId={}, targetCount={}", 
                                    finalTask.getTaskId(), finalTask.getTaskType(), 
                                    finalTask.getPhoneId(), finalTask.getTargetCount());
                            
                            // 构建 resetParams Map
                            Map<String, String> resetParams = new HashMap<>();
                            if (finalTask.getCountry() != null) resetParams.put("country", finalTask.getCountry());
                            if (finalTask.getSdk() != null) resetParams.put("sdk", finalTask.getSdk());
                            if (finalTask.getImagePath() != null) resetParams.put("imagePath", finalTask.getImagePath());
                            if (finalTask.getGaidTag() != null) resetParams.put("gaidTag", finalTask.getGaidTag());
                            if (finalTask.getDynamicIpChannel() != null) resetParams.put("dynamicIpChannel", finalTask.getDynamicIpChannel());
                            if (finalTask.getStaticIpChannel() != null) resetParams.put("staticIpChannel", finalTask.getStaticIpChannel());
                            if (finalTask.getBiz() != null) resetParams.put("biz", finalTask.getBiz());
                            if (finalTask.getAppiumServer() != null) resetParams.put("appiumServer", finalTask.getAppiumServer());
                            
                            // 根据任务类型确定 emailMode
                            String emailMode;
                            if ("FAKE_EMAIL".equals(finalTask.getTaskType())) {
                                emailMode = "random";
                            } else if ("REAL_EMAIL".equals(finalTask.getTaskType())) {
                                emailMode = "outlook";
                            } else {
                                log.error("未知的任务类型: taskId={}, taskType={}", finalTask.getTaskId(), finalTask.getTaskType());
                                finalTask.setUpdatedAt(LocalDateTime.now());
                                ttRegisterTaskRepository.updateById(finalTask);
                                return;
                            }
                            
                            // 统一执行任务（根据 taskType 传递对应的 emailMode）
                            executeTaskForDevice(finalTask, resetParams, emailMode);
                        } catch (Exception e) {
                            log.error("执行任务异常: taskId={}", finalTask.getTaskId(), e);
                            finalTask.setUpdatedAt(LocalDateTime.now());
                            ttRegisterTaskRepository.updateById(finalTask);
                        } finally {
                            semaphore.release();
                        }
                    });
                    
                    futures.add(future);
                }
                
                // 等待当前批次任务完成
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                
                // 短暂休息，避免频繁查询数据库
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            log.warn("任务执行线程被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("执行任务时出错", e);
        }
    }
    
    /**
     * 创建 RegisterContext 对象
     */
    private RegisterContext createRegisterContext(String phoneId, String serverIp, String tiktokVersionDir, Map<String, String> resetParams) {
        RegisterContext context = new RegisterContext();
        context.setPhoneId(phoneId);
        context.setServerIp(serverIp);
        context.setTiktokVersion(tiktokVersionDir);
        
        // 从 resetParams 中提取参数
        if (resetParams != null) {
            context.setCountry(resetParams.getOrDefault("country", ""));
            context.setSdk(resetParams.getOrDefault("sdk", "33"));
            context.setDynamicIpChannel(resetParams.getOrDefault("dynamicIpChannel", ""));
            context.setStaticIpChannel(resetParams.getOrDefault("staticIpChannel", ""));
        }
        
        return context;
    }
    
    /**
     * 执行主板机养号任务（单个设备）
     * 
     * @param task 任务信息
     * @param emailMode 邮箱模式："random"（假邮箱）或 "outlook"（真邮箱）
     */
    private void executeMainboardTaskForDevice(TtRegisterTask task, String emailMode) {
        String phoneId = task.getPhoneId();
        String serverIp = task.getServerIp();
        int targetCount = task.getTargetCount() != null ? task.getTargetCount() : 1;
        String tiktokVersionDir = task.getTiktokVersionDir();
        
        try {
            if (targetCount == 0) {
                // 无限循环注册
                log.info("主板机任务 {} - 设备 {} 开始无限循环注册", task.getTaskId(), phoneId);
                int round = 1;
                while (true) {
                    // 检查任务是否被停止
                    if (stoppedTaskIds.contains(task.getTaskId())) {
                        log.info("主板机任务 {} - 设备 {} 已被停止（内存检查）", task.getTaskId(), phoneId);
                        task.setStatus("STOPPED");
                        task.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(task);
                        break;
                    }
                    
                    // 检查数据库状态（双重检查）
                    TtRegisterTask currentTask = ttRegisterTaskRepository.selectById(task.getId());
                    if (currentTask == null || "STOPPED".equals(currentTask.getStatus())) {
                        log.info("主板机任务 {} - 设备 {} 已被停止（数据库检查）", task.getTaskId(), phoneId);
                        stoppedTaskIds.add(task.getTaskId());
                        task.setStatus("STOPPED");
                        task.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(task);
                        break;
                    }
                    
                    log.info("主板机任务 {} - 设备 {} 第 {} 轮注册", task.getTaskId(), phoneId, round);
                    
                    // 调用主板机注册流程
                    String result;
                    try {
                        result = registerMainboardDeviceWithoutStart(phoneId, serverIp, round, 0, tiktokVersionDir, task.getCountry(), emailMode, task.getAdbPort(), task.getAppiumServer());
                    } catch (Exception e) {
                        log.error("主板机任务 {} - 设备 {} 第 {} 轮注册时发生未捕获异常", task.getTaskId(), phoneId, round, e);
                        result = "FAILED: 注册流程异常 - " + e.getMessage();
                    }
                    
                    if (result != null && result.startsWith("SUCCESS")) {
                        log.info("主板机任务 {} - 设备 {} 第 {} 轮注册成功", task.getTaskId(), phoneId, round);
                    } else {
                        log.warn("主板机任务 {} - 设备 {} 第 {} 轮注册失败: {}", task.getTaskId(), phoneId, round, result);
                    }
                    
                    // 心跳更新
                    task.setUpdatedAt(LocalDateTime.now());
                    ttRegisterTaskRepository.updateById(task);
                    
                    // 注册完成后，再次检查是否被停止
                    if (stoppedTaskIds.contains(task.getTaskId())) {
                        log.info("主板机任务 {} - 设备 {} 在注册完成后检测到停止信号，退出循环", task.getTaskId(), phoneId);
                        task.setStatus("STOPPED");
                        task.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(task);
                        break;
                    }
                    
                    round++;
                    Thread.sleep(5000); // 每轮之间休息5秒
                }
            } else {
                // 注册指定数量的账号
                log.info("主板机任务 {} - 设备 {} 开始注册 {} 个账号", task.getTaskId(), phoneId, targetCount);
                int successCount = 0;
                int failCount = 0;
                
                for (int i = 1; i <= targetCount; i++) {
                    // 检查任务是否被停止
                    if (stoppedTaskIds.contains(task.getTaskId())) {
                        log.info("主板机任务 {} - 设备 {} 已被停止（内存检查），已完成 {}/{}", task.getTaskId(), phoneId, i - 1, targetCount);
                        task.setStatus("STOPPED");
                        task.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(task);
                        break;
                    }
                    
                    // 检查数据库状态（双重检查）
                    TtRegisterTask currentTask = ttRegisterTaskRepository.selectById(task.getId());
                    if (currentTask == null || "STOPPED".equals(currentTask.getStatus())) {
                        log.info("主板机任务 {} - 设备 {} 已被停止（数据库检查），已完成 {}/{}", task.getTaskId(), phoneId, i - 1, targetCount);
                        stoppedTaskIds.add(task.getTaskId());
                        task.setStatus("STOPPED");
                        task.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(task);
                        break;
                    }
                    
                    log.info("主板机任务 {} - 设备 {} 注册进度: {}/{}", task.getTaskId(), phoneId, i, targetCount);
                    
                    // 调用主板机注册流程
                    String result;
                    try {
                        result = registerMainboardDeviceWithoutStart(phoneId, serverIp, i, targetCount, tiktokVersionDir, task.getCountry(), emailMode, task.getAdbPort(), task.getAppiumServer());
                    } catch (Exception e) {
                        log.error("主板机任务 {} - 设备 {} 第 {} 个账号注册时发生未捕获异常", task.getTaskId(), phoneId, i, e);
                        result = "FAILED: 注册流程异常 - " + e.getMessage();
                    }
                    
                    if (result != null && result.startsWith("SUCCESS")) {
                        successCount++;
                        log.info("主板机任务 {} - 设备 {} 第 {} 个账号注册成功", task.getTaskId(), phoneId, i);
                    } else {
                        failCount++;
                        log.warn("主板机任务 {} - 设备 {} 第 {} 个账号注册失败: {}", task.getTaskId(), phoneId, i, result);
                    }
                    
                    // 心跳更新
                    task.setUpdatedAt(LocalDateTime.now());
                    ttRegisterTaskRepository.updateById(task);
                    
                    Thread.sleep(5000); // 每个账号之间休息5秒
                }
                
                // 更新任务状态为已完成
                task.setStatus("COMPLETED");
                task.setUpdatedAt(LocalDateTime.now());
                ttRegisterTaskRepository.updateById(task);
                log.info("主板机任务 {} - 设备 {} 完成，成功: {}, 失败: {}", task.getTaskId(), phoneId, successCount, failCount);
            }
        } catch (InterruptedException e) {
            log.warn("主板机任务 {} - 设备 {} 执行被中断", task.getTaskId(), phoneId);
            task.setStatus("STOPPED");
            task.setUpdatedAt(LocalDateTime.now());
            ttRegisterTaskRepository.updateById(task);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("主板机任务 {} - 设备 {} 执行异常", task.getTaskId(), phoneId, e);
            task.setStatus("FAILED");
            task.setUpdatedAt(LocalDateTime.now());
            ttRegisterTaskRepository.updateById(task);
        }
    }
    
    /**
     * 执行注册任务（单个设备，统一方法）
     * 
     * @param task 任务信息
     * @param resetParams ResetPhoneEnv参数
     * @param emailMode 邮箱模式："random"（假邮箱）或 "outlook"（真邮箱）
     */
    private void executeTaskForDevice(TtRegisterTask task, Map<String, String> resetParams, String emailMode) {
        String phoneId = task.getPhoneId();
        String serverIp = task.getServerIp();
        int targetCount = task.getTargetCount() != null ? task.getTargetCount() : 1;
        String tiktokVersionDir = task.getTiktokVersionDir();
        
        try {
            if (targetCount == 0) {
                // 无限循环注册
                log.info("任务 {} - 设备 {} 开始无限循环注册", task.getTaskId(), phoneId);
                int round = 1;
                while (true) {
                    // 检查任务是否被停止（先检查内存集合，快速响应）
                    if (stoppedTaskIds.contains(task.getTaskId())) {
                        log.info("任务 {} - 设备 {} 已被停止（内存检查）", task.getTaskId(), phoneId);
                        task.setStatus("STOPPED");
                        task.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(task);
                        break;
                    }
                    
                    // 检查数据库状态（双重检查）
                    TtRegisterTask currentTask = ttRegisterTaskRepository.selectById(task.getId());
                    if (currentTask == null || "STOPPED".equals(currentTask.getStatus())) {
                        log.info("任务 {} - 设备 {} 已被停止（数据库检查）", task.getTaskId(), phoneId);
                        stoppedTaskIds.add(task.getTaskId()); // 同步到内存集合
                        task.setStatus("STOPPED");
                        task.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(task);
                        break;
                    }
                    
                    log.info("任务 {} - 设备 {} 第 {} 轮注册", task.getTaskId(), phoneId, round);
                    
                    // 调用注册流程（包含 ResetPhoneEnv + 安装APK + 执行注册脚本）
                    String result;
                    try {
                        result = registerSingleDeviceWithoutStart(phoneId, serverIp, round, 0, tiktokVersionDir, resetParams, emailMode);
                    } catch (Exception e) {
                        log.error("任务 {} - 设备 {} 第 {} 轮注册时发生未捕获异常", task.getTaskId(), phoneId, round, e);
                        result = "FAILED: 注册流程异常 - " + e.getMessage();
                    }
                    
                    if (result != null && result.startsWith("SUCCESS")) {
                        log.info("任务 {} - 设备 {} 第 {} 轮注册成功", task.getTaskId(), phoneId, round);
                    } else {
                        log.warn("任务 {} - 设备 {} 第 {} 轮注册失败: {}", task.getTaskId(), phoneId, round, result);
                    }
                    
                    // 心跳前先检查是否被手动在库中改为 STOPPED，避免覆盖
                    if (syncStoppedFromDb(task)) {
                        log.info("任务 {} - 设备 {} 检测到数据库已 STOPPED，退出", task.getTaskId(), phoneId);
                        ttRegisterTaskRepository.updateById(task);
                        break;
                    }
                    task.setUpdatedAt(LocalDateTime.now());
                    ttRegisterTaskRepository.updateById(task);
                    
                    // 注册完成后，再次检查是否被停止（避免继续下一轮）
                    if (stoppedTaskIds.contains(task.getTaskId())) {
                        log.info("任务 {} - 设备 {} 在注册完成后检测到停止信号，退出循环", task.getTaskId(), phoneId);
                        task.setStatus("STOPPED");
                        task.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(task);
                        break;
                    }
                    
                    round++;
                    Thread.sleep(5000); // 每轮之间休息5秒
                }
            } else {
                // 注册指定数量的账号
                log.info("任务 {} - 设备 {} 开始注册 {} 个账号", task.getTaskId(), phoneId, targetCount);
                int successCount = 0;
                int failCount = 0;
                
                for (int i = 1; i <= targetCount; i++) {
                    // 检查任务是否被停止（先检查内存集合，快速响应）
                    if (stoppedTaskIds.contains(task.getTaskId())) {
                        log.info("任务 {} - 设备 {} 已被停止（内存检查），已完成 {}/{}", task.getTaskId(), phoneId, i - 1, targetCount);
                        task.setStatus("STOPPED");
                        task.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(task);
                        break;
                    }
                    
                    // 检查数据库状态（双重检查）
                    TtRegisterTask currentTask = ttRegisterTaskRepository.selectById(task.getId());
                    if (currentTask == null || "STOPPED".equals(currentTask.getStatus())) {
                        log.info("任务 {} - 设备 {} 已被停止（数据库检查），已完成 {}/{}", task.getTaskId(), phoneId, i - 1, targetCount);
                        stoppedTaskIds.add(task.getTaskId()); // 同步到内存集合
                        task.setStatus("STOPPED");
                        task.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(task);
                        break;
                    }
                    
                    log.info("任务 {} - 设备 {} 注册进度: {}/{}", task.getTaskId(), phoneId, i, targetCount);
                    
                    // 调用注册流程（包含 ResetPhoneEnv + 安装APK + 执行注册脚本）
                    String result;
                    try {
                        result = registerSingleDeviceWithoutStart(phoneId, serverIp, i, targetCount, tiktokVersionDir, resetParams, emailMode);
                    } catch (Exception e) {
                        log.error("任务 {} - 设备 {} 第 {} 个账号注册时发生未捕获异常", task.getTaskId(), phoneId, i, e);
                        result = "FAILED: 注册流程异常 - " + e.getMessage();
                    }
                    
                    if (result != null && result.startsWith("SUCCESS")) {
                        successCount++;
                        log.info("任务 {} - 设备 {} 第 {} 个账号注册成功", task.getTaskId(), phoneId, i);
                    } else {
                        failCount++;
                        log.warn("任务 {} - 设备 {} 第 {} 个账号注册失败: {}", task.getTaskId(), phoneId, i, result);
                    }
                    
                    // 心跳前先检查是否被手动在库中改为 STOPPED，避免覆盖
                    if (syncStoppedFromDb(task)) {
                        log.info("任务 {} - 设备 {} 检测到数据库已 STOPPED，退出，已完成 {}/{}", task.getTaskId(), phoneId, i, targetCount);
                        ttRegisterTaskRepository.updateById(task);
                        break;
                    }
                    task.setUpdatedAt(LocalDateTime.now());
                    ttRegisterTaskRepository.updateById(task);
                    
                    Thread.sleep(5000); // 每个账号之间休息5秒
                }
                
                // 完成前再检查一次，避免最后一步被手动改为 STOPPED 后仍写成 COMPLETED
                if (syncStoppedFromDb(task)) {
                    ttRegisterTaskRepository.updateById(task);
                    return;
                }
                // 更新任务状态为已完成
                task.setStatus("COMPLETED");
                task.setUpdatedAt(LocalDateTime.now());
                ttRegisterTaskRepository.updateById(task);
                log.info("任务 {} - 设备 {} 完成，成功: {}, 失败: {}", task.getTaskId(), phoneId, successCount, failCount);
            }
        } catch (InterruptedException e) {
            log.warn("任务 {} - 设备 {} 执行被中断", task.getTaskId(), phoneId);
            task.setStatus("STOPPED");
            task.setUpdatedAt(LocalDateTime.now());
            ttRegisterTaskRepository.updateById(task);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("任务 {} - 设备 {} 执行异常", task.getTaskId(), phoneId, e);
            // 无限循环任务，异常时不设置FAILED状态；若库中已为 STOPPED 则不覆盖
            if (syncStoppedFromDb(task)) {
                ttRegisterTaskRepository.updateById(task);
            } else {
                task.setUpdatedAt(LocalDateTime.now());
                ttRegisterTaskRepository.updateById(task);
            }
        }
    }

    /**
     * 执行留存任务（单设备）：取三天前符合条件的账号，逐个调用 RestoreApp + 留存脚本。
     * targetCount=0 表示无限循环：每批最多 50 条，跑完再取下一批，直到任务被停止。
     * targetCount>0 表示只跑一批（该条数），跑完即 COMPLETED。
     */
    private void executeRetentionTaskForDevice(TtRegisterTask task) {
        String phoneId = task.getPhoneId();
        String serverIp = task.getServerIp();
        String country = (task.getCountry() != null && !task.getCountry().isEmpty()) ? task.getCountry() : "US";
        boolean infinite = task.getTargetCount() != null && task.getTargetCount() == 0;
        int batchLimit = (task.getTargetCount() != null && task.getTargetCount() > 0) ? task.getTargetCount() : 50;

        String imagePath = task.getImagePath();
        if (imagePath == null || imagePath.isEmpty()) {
            imagePath = getDeviceImageName(phoneId, serverIp);
            if (imagePath == null || imagePath.isEmpty()) {
                log.error("留存任务 {} - 获取设备镜像失败", task.getTaskId());
                task.setStatus("FAILED");
                task.setUpdatedAt(LocalDateTime.now());
                ttRegisterTaskRepository.updateById(task);
                return;
            }
        }
        String appiumServer = task.getAppiumServer();
        if (appiumServer == null || appiumServer.isEmpty()) {
            appiumServer = "10.13.55.85";
        }
        String packageName = "com.zhiliaoapp.musically";
        int totalSuccess = 0;
        int totalFail = 0;

        while (true) {
            if (stoppedTaskIds.contains(task.getTaskId())) {
                task.setStatus("STOPPED");
                task.setUpdatedAt(LocalDateTime.now());
                ttRegisterTaskRepository.updateById(task);
                log.info("留存任务 {} - 已停止，累计成功: {}, 失败: {}", task.getTaskId(), totalSuccess, totalFail);
                return;
            }
            if (syncStoppedFromDb(task)) {
                log.info("留存任务 {} - 检测到数据库已 STOPPED，退出", task.getTaskId());
                ttRegisterTaskRepository.updateById(task);
                return;
            }
            LocalDateTime cutoff = LocalDateTime.now().minusDays(3);
            // 24小时内做过留存的账号不再进入本次留存
            LocalDateTime retentionCutoff = LocalDateTime.now().minusHours(24);
            List<TtAccountRegister> accounts = ttAccountRegisterRepository.listForRetention(cutoff, country, batchLimit, retentionCutoff);
            if (accounts == null || accounts.isEmpty()) {
                if (infinite) {
                    log.info("留存任务 {} - 本批无符合条件账号（cutoff={}, country={}），60秒后重试", task.getTaskId(), cutoff, country);
                    if (syncStoppedFromDb(task)) {
                        ttRegisterTaskRepository.updateById(task);
                        return;
                    }
                    task.setUpdatedAt(LocalDateTime.now());
                    ttRegisterTaskRepository.updateById(task);
                    try {
                        Thread.sleep(60000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        task.setStatus("STOPPED");
                        task.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(task);
                        return;
                    }
                    continue;
                } else {
                    log.info("留存任务 {} - 无符合条件账号，任务结束", task.getTaskId());
                    task.setStatus("COMPLETED");
                    task.setUpdatedAt(LocalDateTime.now());
                    ttRegisterTaskRepository.updateById(task);
                    log.info("留存任务 {} - 完成，成功: {}, 失败: {}", task.getTaskId(), totalSuccess, totalFail);
                    return;
                }
            }
            int batchSuccess = 0;
            int batchFail = 0;
            for (int i = 0; i < accounts.size(); i++) {
                if (stoppedTaskIds.contains(task.getTaskId())) {
                    task.setStatus("STOPPED");
                    task.setUpdatedAt(LocalDateTime.now());
                    ttRegisterTaskRepository.updateById(task);
                    log.info("留存任务 {} - 已停止，累计成功: {}, 失败: {}", task.getTaskId(), totalSuccess + batchSuccess, totalFail + batchFail);
                    return;
                }
                TtAccountRegister account = accounts.get(i);
                // 本机级别的留存去重：同一账号在 24 小时内只处理一次
                Instant existing = retentionProcessingCache.asMap()
                        .putIfAbsent(account.getId(), Instant.now());
                if (existing != null) {
                    log.info("留存任务 {} - 账号 id={} 已在本机缓存中标记为处理中/已处理，跳过本次留存", 
                            task.getTaskId(), account.getId());
                    continue;
                }
                if (account.getGaid() == null || account.getGaid().isEmpty()) {
                    log.warn("留存任务 {} - 账号 id={} 无 gaid，跳过", task.getTaskId(), account.getId());
                    batchFail++;
                    continue;
                }
                if (syncStoppedFromDb(task)) {
                    ttRegisterTaskRepository.updateById(task);
                    return;
                }
                task.setUpdatedAt(LocalDateTime.now());
                ttRegisterTaskRepository.updateById(task);
                // 根据账号注册时的安卓版本调整镜像中的 androidXX 段，确保与备份时一致
                String imagePathForRestore = adjustImagePathForAndroidVersion(imagePath, account.getAndroidVersion());
                try {
                    apiService.restoreApp(phoneId, serverIp, packageName, imagePathForRestore, account.getGaid());
                } catch (Exception e) {
                    log.error("留存任务 {} - RestoreApp 失败: gaid={}, imagePath={}", task.getTaskId(), account.getGaid(), imagePathForRestore, e);
                    batchFail++;
                    continue;
                }
                // 恢复成功后调用 TTFarmSetupNetwork 设置网络/IP
                try {
                    boolean setupOk = apiService.ttFarmSetupNetwork(phoneId, serverIp, country, true);
                    if (!setupOk) {
                        log.warn("留存任务 {} - TTFarmSetupNetwork 调用失败，但继续执行留存脚本: phoneId={}, gaid={}",
                                task.getTaskId(), phoneId, account.getGaid());
                    } else {
                        log.info("留存任务 {} - TTFarmSetupNetwork 调用成功: phoneId={}, gaid={}",
                                task.getTaskId(), phoneId, account.getGaid());
                    }
                } catch (Exception e) {
                    log.error("留存任务 {} - 调用 TTFarmSetupNetwork 接口时出错: gaid={}", task.getTaskId(), account.getGaid(), e);
                }
                // 留存脚本参数：offerid, phoneId, phoneServerIp, country, retention_hours
                int retentionExitCode = executeRetentionScript(phoneId, serverIp, appiumServer, country);
                // 只有 exitCode=0 视为脚本成功；8 视为失败（但需要更新 need_retention）
                boolean scriptOk = (retentionExitCode == 0);
                if (retentionExitCode == 8) {
                    // 留存脚本返回8：账号登出，标记 need_retention=2，后续不再进入留存筛选
                    try {
                        ttAccountRegisterRepository.updateNeedRetention(account.getId(), 2);
                        log.info("留存任务 {} - 账号登出（exitCode=8），已更新need_retention=2: accountId={}, gaid={}",
                                task.getTaskId(), account.getId(), account.getGaid());
                    } catch (Exception e) {
                        log.warn("留存任务 {} - 更新need_retention=2失败: accountId={}, gaid={}",
                                task.getTaskId(), account.getId(), account.getGaid(), e);
                    }
                }
                if (scriptOk) batchSuccess++;
                else batchFail++;
                // 无论脚本成功或失败都调用备份接口，并写入留存记录表（含 script_success、backup_success）
                boolean backupOk = false;
                try {
                    backupOk = apiService.backupApp(phoneId, serverIp, "com.zhiliaoapp.musically");
                    if (backupOk) {
                        log.info("留存任务 {} - 备份接口调用成功: phoneId={}, gaid={}", task.getTaskId(), phoneId, account.getGaid());
                    } else {
                        log.warn("留存任务 {} - 备份接口调用失败: phoneId={}, gaid={}", task.getTaskId(), phoneId, account.getGaid());
                    }
                } catch (Exception e) {
                    log.error("留存任务 {} - 调用备份接口时出错: gaid={}", task.getTaskId(), account.getGaid(), e);
                }
                TtRetentionRecord record = new TtRetentionRecord();
                record.setTaskId(task.getTaskId());
                record.setPhoneId(phoneId);
                record.setPhoneServerIp(serverIp);
                record.setAccountRegisterId(account.getId());
                record.setGaid(account.getGaid());
                record.setScriptSuccess(scriptOk);
                record.setBackupSuccess(backupOk);
                record.setCreatedAt(LocalDateTime.now());
                try {
                    ttRetentionRecordRepository.insert(record);
                } catch (Exception ex) {
                    log.warn("留存任务 {} - 写入留存记录表失败: gaid={}", task.getTaskId(), account.getGaid(), ex);
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    task.setStatus("STOPPED");
                    task.setUpdatedAt(LocalDateTime.now());
                    ttRegisterTaskRepository.updateById(task);
                    return;
                }
            }
            totalSuccess += batchSuccess;
            totalFail += batchFail;
            log.info("留存任务 {} - 本批完成，本批成功: {}, 失败: {}，累计成功: {}, 失败: {}", task.getTaskId(), batchSuccess, batchFail, totalSuccess, totalFail);

            if (!infinite) {
                if (syncStoppedFromDb(task)) {
                    ttRegisterTaskRepository.updateById(task);
                    return;
                }
                task.setStatus("COMPLETED");
                task.setUpdatedAt(LocalDateTime.now());
                ttRegisterTaskRepository.updateById(task);
                log.info("留存任务 {} - 完成，成功: {}, 失败: {}", task.getTaskId(), totalSuccess, totalFail);
                return;
            }
            if (syncStoppedFromDb(task)) {
                ttRegisterTaskRepository.updateById(task);
                return;
            }
            task.setUpdatedAt(LocalDateTime.now());
            ttRegisterTaskRepository.updateById(task);
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                task.setStatus("STOPPED");
                task.setUpdatedAt(LocalDateTime.now());
                ttRegisterTaskRepository.updateById(task);
                return;
            }
        }
    }

    /**
     * 执行留存脚本（与注册脚本同目录，脚本名可配置）
     */
    /**
     * 执行留存脚本
     * 返回脚本退出码：0=正常成功，8=账号登出（视为失败，仅用于更新need_retention），其它=失败
     */
    private int executeRetentionScript(String phoneId, String serverIp, String scriptHost, String country) {
        String scriptPath = "/data/appium/com_zhiliaoapp_musically/" + retentionScriptName;
        String offerid = "test";
        int scriptTimeoutMinutes = 20;
        String retentionHours = "1";
        String command = String.format(
                "cd /data/appium/com_zhiliaoapp_musically && timeout %dm stdbuf -o0 -e0 python3 -u %s %s %s %s %s %s",
                scriptTimeoutMinutes, scriptPath, offerid, phoneId, serverIp, country, retentionHours);
        log.info("{} - 执行留存脚本: {} 在服务器 {}", phoneId, scriptPath, scriptHost);
        SshUtil.SshResult result = sshCommand(scriptHost, command);
        Integer exitCode = result.getExitCode();
        if (exitCode != null) {
            if (exitCode == 0) {
                log.info("{} - 留存脚本执行成功 (exitCode=0)", phoneId);
            } else if (exitCode == 8) {
                // 8 表示账号登出，这里仅记录日志，业务上视为“脚本失败但需要做善后处理”
                log.info("{} - 留存脚本返回账号登出 (exitCode=8)", phoneId);
            } else {
                log.warn("{} - 留存脚本执行失败, exitCode={}", phoneId, exitCode);
            }
        } else {
            log.warn("{} - 留存脚本执行失败, exitCode=null", phoneId);
        }
        return exitCode != null ? exitCode : -1;
    }
    
    /**
     * 定时任务：检查长时间未更新的 RUNNING 任务，重置为 PENDING 以便重新执行
     * 每5分钟执行一次
     * 注意：不会重置 STOPPED 状态的任务，即使它们长时间未更新
     */
    @Scheduled(fixedRate = 5 * 60 * 1000) // 每5分钟执行一次
    public void scheduledCheckStuckTasks() {
        try {
            // 计算1小时前的时间点
            LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
            
            // 查询超过1小时未更新的 RUNNING 状态任务
            List<TtRegisterTask> stuckTasks = ttRegisterTaskRepository.findRunningTasksNotUpdatedSince("RUNNING", oneHourAgo);
            
            if (stuckTasks.isEmpty()) {
                log.debug("定时任务检查：没有发现长时间未更新的 RUNNING 任务");
                return;
            }
            
            log.warn("定时任务检查：发现 {} 个长时间未更新的 RUNNING 任务，将检查并重置为 PENDING", stuckTasks.size());
            
            int resetCount = 0;
            int skippedCount = 0;
            for (TtRegisterTask task : stuckTasks) {
                try {
                    // 双重检查：再次从数据库查询最新状态，确保任务状态没有被手动修改为 STOPPED
                    TtRegisterTask currentTask = ttRegisterTaskRepository.selectById(task.getId());
                    if (currentTask == null) {
                        log.debug("任务 {} 不存在，跳过", task.getTaskId());
                        skippedCount++;
                        continue;
                    }
                    
                    // 如果任务状态不是 RUNNING（可能被手动改为 STOPPED），则跳过
                    if (!"RUNNING".equals(currentTask.getStatus())) {
                        log.debug("任务 {} 当前状态为 {}，不是 RUNNING，跳过重置", 
                                task.getTaskId(), currentTask.getStatus());
                        skippedCount++;
                        continue;
                    }
                    
                    // 检查是否在停止集合中（如果用户调用了停止接口，但状态还没更新到数据库）
                    if (stoppedTaskIds.contains(task.getTaskId())) {
                        log.debug("任务 {} 在停止集合中，跳过重置", task.getTaskId());
                        skippedCount++;
                        continue;
                    }
                    
                    // 重置任务状态为 PENDING，以便重新执行
                    task.setStatus("PENDING");
                    task.setUpdatedAt(LocalDateTime.now());
                    ttRegisterTaskRepository.updateById(task);
                    
                    // 从停止集合中移除（如果存在）
                    stoppedTaskIds.remove(task.getTaskId());
                    
                    resetCount++;
                    log.info("任务 {} - 设备 {} 因超过1小时未更新，已重置为 PENDING 状态", 
                            task.getTaskId(), task.getPhoneId());
                } catch (Exception e) {
                    log.error("重置任务状态失败: taskId={}", task.getTaskId(), e);
                }
            }
            
            log.info("定时任务检查：成功重置 {} 个长时间未更新的任务为 PENDING，跳过 {} 个任务（状态已变更或已停止）", 
                    resetCount, skippedCount);
            
        } catch (Exception e) {
            log.error("定时任务检查长时间未更新的任务时出错", e);
        }
    }
    
    /**
     * 任务信息
     */
    public static class TaskInfo {
        private String taskId;
        private String serverIp;
        private List<String> phoneIds;
        private String pid;
        private String logFile;
        private String status; // RUNNING, COMPLETED, FAILED
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private int successCount;
        private int failCount;
        private int totalCount; // 总目标数量（用于单设备多次注册）
        private int progress; // 进度（0-100）
        private List<RoundResult> roundResults = new ArrayList<>(); // 轮次结果（用于单设备多次注册）
        private List<Map<String, Object>> deviceResults = new ArrayList<>(); // 设备结果（用于多设备并行注册）
        private String message; // 任务消息
        private volatile boolean stopped = false; // 是否已停止
        
        /**
         * 轮次结果（用于单设备多次注册）
         */
        public static class RoundResult {
            private int roundNumber;
            private String status;
            private boolean success;
            private LocalDateTime startTime;
            private LocalDateTime endTime;
            private String errorMessage;
            
            // Getters and Setters
            public int getRoundNumber() { return roundNumber; }
            public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }
            public String getStatus() { return status; }
            public void setStatus(String status) { this.status = status; }
            public boolean isSuccess() { return success; }
            public void setSuccess(boolean success) { this.success = success; }
            public LocalDateTime getStartTime() { return startTime; }
            public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
            public LocalDateTime getEndTime() { return endTime; }
            public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
            public String getErrorMessage() { return errorMessage; }
            public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        }
        
        // getters and setters
        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public String getServerIp() { return serverIp; }
        public void setServerIp(String serverIp) { this.serverIp = serverIp; }
        public List<String> getPhoneIds() { return phoneIds; }
        public void setPhoneIds(List<String> phoneIds) { this.phoneIds = phoneIds; }
        public String getPid() { return pid; }
        public void setPid(String pid) { this.pid = pid; }
        public String getLogFile() { return logFile; }
        public void setLogFile(String logFile) { this.logFile = logFile; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
        public int getSuccessCount() { return successCount; }
        public void setSuccessCount(int successCount) { this.successCount = successCount; }
        public int getFailCount() { return failCount; }
        public void setFailCount(int failCount) { this.failCount = failCount; }
        public int getTotalCount() { return totalCount; }
        public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
        public int getProgress() { return progress; }
        public void setProgress(int progress) { this.progress = progress; }
        public List<RoundResult> getRoundResults() { 
            if (roundResults == null) {
                roundResults = new ArrayList<>();
            }
            return roundResults; 
        }
        public void setRoundResults(List<RoundResult> roundResults) { this.roundResults = roundResults; }
        public List<Map<String, Object>> getDeviceResults() { 
            if (deviceResults == null) {
                deviceResults = new ArrayList<>();
            }
            return deviceResults; 
        }
        public void setDeviceResults(List<Map<String, Object>> deviceResults) { this.deviceResults = deviceResults; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public boolean isStopped() { return stopped; }
        public void setStopped(boolean stopped) { this.stopped = stopped; }
    }

    /**
     * 批量注册TT账号（异步执行，返回任务ID）
     * 
     * @param phoneIds 云手机ID列表
     * @param serverIp 服务器IP
     * @param resetParams ResetPhoneEnv参数（可选，为空时使用默认值）
     * @return 任务ID和相关信息
     */
    public Map<String, Object> batchRegisterTtAccounts(List<String> phoneIds, String serverIp, Map<String, String> resetParams) {
        String taskId = "TT_REGISTER_" + System.currentTimeMillis();
        
        log.info("=== 启动批量注册TT账号任务: {} ===", taskId);
        log.info("设备数量: {}, 服务器: {}", phoneIds.size(), serverIp);
        
        // 创建任务信息
        TaskInfo taskInfo = new TaskInfo();
        taskInfo.setTaskId(taskId);
        taskInfo.setServerIp(serverIp);
        taskInfo.setPhoneIds(new ArrayList<>(phoneIds));
        taskInfo.setStatus("RUNNING");
        taskInfo.setStartTime(LocalDateTime.now());
        taskInfo.setSuccessCount(0);
        taskInfo.setFailCount(0);
        taskInfo.setPid(String.valueOf(Thread.currentThread().getId())); // 使用线程ID作为PID标识
        
        // 在服务器上创建日志文件路径（用于将来可能的日志收集）
        String logFile = String.format("/tmp/tt_register_%s.log", taskId);
        taskInfo.setLogFile(logFile);
        
        // 保存任务信息
        taskInfoMap.put(taskId, taskInfo);
        
        // 异步执行批量注册
        Map<String, String> finalResetParams = resetParams != null ? resetParams : new HashMap<>();
        executeBatchRegisterAsync(phoneIds, serverIp, taskId, logFile, finalResetParams);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("taskId", taskId);
        result.put("pid", taskInfo.getPid());
        result.put("logFile", logFile);
        result.put("message", "批量注册任务已启动");
        return result;
    }
    
    /**
     * 多设备并行注册（多个设备同时注册，每个设备可注册多个账号）
     * 
     * @param phoneIds 云手机ID列表
     * @param serverIp 服务器IP
     * @param maxConcurrency 最大并发数（可选，默认10）
     * @param targetCountPerDevice 每个设备目标账号数量（可选，默认1）
     * @param tiktokVersionDir TikTok版本目录（必填，例如：com.zhiliaoapp.musically_us_43.1.4）
     * @param resetParams ResetPhoneEnv参数（可选）
     * @return 任务信息
     */
    public Map<String, Object> parallelRegisterMultipleDevices(List<String> phoneIds, String serverIp, Integer maxConcurrency, Integer targetCountPerDevice, String tiktokVersionDir, Map<String, String> resetParams) {
        String taskId = "TT_PARALLEL_REGISTER_" + System.currentTimeMillis();
        
        // 设置默认并发数
        int concurrency = (maxConcurrency != null && maxConcurrency > 0) ? maxConcurrency : 10;
        if (concurrency > 100) {
            concurrency = 100; // 限制最大并发数为100
        }
        
        // 设置每个设备目标账号数量（如果为0或负数，表示无限循环）
        int perDeviceCount = (targetCountPerDevice != null && targetCountPerDevice > 0) ? targetCountPerDevice : 0;
        // 0 表示无限循环，不再限制最大数量
        
        // 计算总目标账号数（如果perDeviceCount为0，表示无限循环，总数为-1）
        int totalTargetCount = (perDeviceCount == 0) ? -1 : phoneIds.size() * perDeviceCount;
        
        log.info("=== 启动多设备并行注册任务: {} ===", taskId);
        if (perDeviceCount == 0) {
            log.info("设备数量: {}, 服务器: {}, 最大并发数: {}, 每个设备: 无限循环, 总目标账号数: 无限", 
                    phoneIds.size(), serverIp, concurrency);
        } else {
            log.info("设备数量: {}, 服务器: {}, 最大并发数: {}, 每个设备目标账号数: {}, 总目标账号数: {}", 
                    phoneIds.size(), serverIp, concurrency, perDeviceCount, totalTargetCount);
        }
        
        // 为每个设备创建任务记录并保存到数据库
        List<String> taskIds = new ArrayList<>();
        Map<String, String> finalResetParams = resetParams != null ? resetParams : new HashMap<>();
        
        for (String phoneId : phoneIds) {
            TtRegisterTask task = createTaskRecord("FAKE_EMAIL", serverIp, phoneId, 
                    perDeviceCount == 0 ? 0 : perDeviceCount, tiktokVersionDir, finalResetParams);
            taskIds.add(task.getTaskId());
        }
        
        // 创建任务信息（保持与原有逻辑一致）
        TaskInfo taskInfo = new TaskInfo();
        taskInfo.setTaskId(taskId);
        taskInfo.setServerIp(serverIp);
        taskInfo.setPhoneIds(new ArrayList<>(phoneIds));
        taskInfo.setStatus("RUNNING");
        taskInfo.setStartTime(LocalDateTime.now());
        taskInfo.setSuccessCount(0);
        taskInfo.setFailCount(0);
        taskInfo.setTotalCount(totalTargetCount);
        taskInfo.setPid(String.valueOf(Thread.currentThread().getId()));
        
        String logFile = String.format("/tmp/tt_parallel_register_%s.log", taskId);
        taskInfo.setLogFile(logFile);
        
        // 保存任务信息（内存中，保持兼容）
        taskInfoMap.put(taskId, taskInfo);
        
        // 异步执行并行注册（保持原有逻辑完全一致）
        executeParallelRegisterAsync(phoneIds, serverIp, concurrency, perDeviceCount, taskId, logFile, tiktokVersionDir, finalResetParams);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("taskId", taskId);
        result.put("pid", taskInfo.getPid());
        result.put("logFile", logFile);
        result.put("message", "多设备并行注册任务已启动");
        result.put("concurrency", concurrency);
        result.put("taskIds", taskIds); // 新增：返回数据库任务ID列表
        return result;
    }
    
    /**
     * 多设备并行注册Outlook邮箱账号（多个设备同时注册，每个设备可注册多个账号）
     * 
     * @param phoneIds 云手机ID列表
     * @param serverIp 服务器IP
     * @param maxConcurrency 最大并发数（可选，默认10）
     * @param targetCountPerDevice 每个设备目标账号数量（可选，默认1）
     * @param tiktokVersionDir TikTok版本目录（必填，例如：com.zhiliaoapp.musically_us_43.1.4）
     * @param resetParams ResetPhoneEnv参数（可选）
     * @return 任务信息
     */
    public Map<String, Object> parallelRegisterOutlookAccounts(List<String> phoneIds, String serverIp, Integer maxConcurrency, Integer targetCountPerDevice, String tiktokVersionDir, Map<String, String> resetParams) {
        String taskId = "OUTLOOK_PARALLEL_REGISTER_" + System.currentTimeMillis();
        
        // 设置默认并发数
        int concurrency = (maxConcurrency != null && maxConcurrency > 0) ? maxConcurrency : 10;
        if (concurrency > 100) {
            concurrency = 100;
        }
        
        // 设置每个设备目标账号数量（如果为0或负数，表示无限循环）
        int perDeviceCount = (targetCountPerDevice != null && targetCountPerDevice > 0) ? targetCountPerDevice : 0;
        
        // 计算总目标账号数（如果perDeviceCount为0，表示无限循环，总数为-1）
        int totalTargetCount = (perDeviceCount == 0) ? -1 : phoneIds.size() * perDeviceCount;
        
        log.info("=== 启动多设备并行注册Outlook账号任务: {} ===", taskId);
        if (perDeviceCount == 0) {
            log.info("设备数量: {}, 服务器: {}, 最大并发数: {}, 每个设备: 无限循环, 总目标账号数: 无限", 
                    phoneIds.size(), serverIp, concurrency);
        } else {
            log.info("设备数量: {}, 服务器: {}, 最大并发数: {}, 每个设备目标账号数: {}, 总目标账号数: {}", 
                    phoneIds.size(), serverIp, concurrency, perDeviceCount, totalTargetCount);
        }
        
        // 为每个设备创建任务记录并保存到数据库
        List<String> taskIds = new ArrayList<>();
        Map<String, String> finalResetParams = resetParams != null ? resetParams : new HashMap<>();
        
        for (String phoneId : phoneIds) {
            TtRegisterTask task = createTaskRecord("REAL_EMAIL", serverIp, phoneId, 
                    perDeviceCount == 0 ? 0 : perDeviceCount, tiktokVersionDir, finalResetParams);
            taskIds.add(task.getTaskId());
        }
        
        // 创建任务信息（保持与原有逻辑一致）
        TaskInfo taskInfo = new TaskInfo();
        taskInfo.setTaskId(taskId);
        taskInfo.setServerIp(serverIp);
        taskInfo.setPhoneIds(new ArrayList<>(phoneIds));
        taskInfo.setStatus("RUNNING");
        taskInfo.setStartTime(LocalDateTime.now());
        taskInfo.setSuccessCount(0);
        taskInfo.setFailCount(0);
        taskInfo.setTotalCount(totalTargetCount);
        taskInfo.setPid(String.valueOf(Thread.currentThread().getId()));
        
        String logFile = String.format("/tmp/outlook_parallel_register_%s.log", taskId);
        taskInfo.setLogFile(logFile);
        
        // 保存任务信息（内存中，保持兼容）
        taskInfoMap.put(taskId, taskInfo);
        
        // 异步执行并行注册（保持原有逻辑完全一致）
        executeParallelOutlookRegisterAsync(phoneIds, serverIp, concurrency, perDeviceCount, taskId, logFile, tiktokVersionDir, finalResetParams);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("taskId", taskId);
        result.put("pid", taskInfo.getPid());
        result.put("logFile", logFile);
        result.put("message", "多设备并行注册Outlook账号任务已启动");
        result.put("concurrency", concurrency);
        result.put("taskIds", taskIds); // 新增：返回数据库任务ID列表
        return result;
    }
    
    /**
     * 异步执行多设备并行注册
     */
    @Async
    public void executeParallelRegisterAsync(List<String> phoneIds, String serverIp, int maxConcurrency, int targetCountPerDevice, String taskId, String logFile, String tiktokVersionDir, Map<String, String> resetParams) {
        TaskInfo taskInfo = taskInfoMap.get(taskId);
        if (taskInfo == null) {
            log.error("任务信息不存在: {}", taskId);
            return;
        }
        
        // 创建信号量控制并发数
        Semaphore semaphore = new Semaphore(maxConcurrency);
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        
        try {
            log.info("=== 开始创建 {} 个设备的并行任务，最大并发数: {} ===", phoneIds.size(), maxConcurrency);
            // 为每个设备创建异步任务
            for (int i = 0; i < phoneIds.size(); i++) {
                final int index = i;
                final String phoneId = phoneIds.get(i);
                
                // 检查是否被停止
                if (taskInfo.isStopped()) {
                    log.info("任务 {} 已被停止，停止在第 {} 个设备", taskId, index + 1);
                    break;
                }
                
                log.debug("创建设备 {} 的异步任务 ({}/{})", phoneId, index + 1, phoneIds.size());
                final int deviceIndex = index; // 保存设备索引，避免闭包问题
                final String devicePhoneId = phoneId; // 保存设备ID，避免闭包问题
                CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
                    Map<String, Object> deviceResult = new HashMap<>();
                    deviceResult.put("phoneId", phoneId);
                    deviceResult.put("index", index + 1);
                    deviceResult.put("startTime", LocalDateTime.now().toString());
                    
                    try {
                        // 获取信号量（控制并发数）
                        semaphore.acquire();
                        
                        try {
                            // 再次检查是否被停止
                            if (taskInfo.isStopped()) {
                                deviceResult.put("status", "STOPPED");
                                deviceResult.put("success", false);
                                return deviceResult;
                            }
                            
                            log.info("[{}/{}] 开始并行处理设备: {}, 目标账号数: {} (线程: {})", 
                                    index + 1, phoneIds.size(), phoneId, targetCountPerDevice, Thread.currentThread().getName());
                            
                            // 循环执行注册（如果targetCountPerDevice为0，则无限循环）
                            int deviceSuccessCount = 0;
                            int deviceFailCount = 0;
                            List<String> deviceStatuses = new ArrayList<>();
                            
                            int round = 1;
                            boolean isInfinite = (targetCountPerDevice == 0);
                            
                            while (true) {
                                // 检查是否被停止
                                if (taskInfo.isStopped()) {
                                    log.info("任务 {} 已被停止，设备 {} 停止在第 {} 轮", taskId, phoneId, round);
                                    break;
                                }
                                
                                // 如果不是无限循环，检查是否达到目标数量
                                if (!isInfinite && round > targetCountPerDevice) {
                                    break;
                                }
                                
                                String roundInfo = isInfinite ? String.format("第%d轮", round) : String.format("第%d/%d轮", round, targetCountPerDevice);
                                log.info("[{}/{}] 设备 {} {} 注册 (线程: {})", 
                                        deviceIndex + 1, phoneIds.size(), devicePhoneId, roundInfo, Thread.currentThread().getName());
                                // ResetPhoneEnv接口会重启设备，所以不需要单独启动步骤
                                String status = registerSingleDeviceWithoutStart(devicePhoneId, serverIp, round, isInfinite ? 0 : targetCountPerDevice, tiktokVersionDir, resetParams, "random");
                                deviceStatuses.add(String.format("第%d轮: %s", round, status));
                                
                                if ("SUCCESS".equals(status)) {
                                    deviceSuccessCount++;
                                    log.info("[{}/{}] 设备 {} {} 注册成功", deviceIndex + 1, phoneIds.size(), devicePhoneId, roundInfo);
                                } else {
                                    deviceFailCount++;
                                    log.warn("[{}/{}] 设备 {} {} 注册失败: {}", deviceIndex + 1, phoneIds.size(), devicePhoneId, roundInfo, status);
                                }
                                
                                round++;
                            }
                            
                            // 汇总设备结果
                            String finalStatus;
                            if (isInfinite) {
                                finalStatus = String.format("RUNNING: %d成功/%d失败 (已停止)", deviceSuccessCount, deviceFailCount);
                            } else if (deviceSuccessCount == targetCountPerDevice) {
                                finalStatus = "SUCCESS";
                            } else if (deviceSuccessCount > 0) {
                                finalStatus = String.format("PARTIAL: %d成功/%d失败", deviceSuccessCount, deviceFailCount);
                            } else {
                                finalStatus = String.format("FAILED: %d失败", deviceFailCount);
                            }
                            
                            deviceResult.put("status", finalStatus);
                            deviceResult.put("success", !isInfinite && deviceSuccessCount == targetCountPerDevice);
                            deviceResult.put("successCount", deviceSuccessCount);
                            deviceResult.put("failCount", deviceFailCount);
                            deviceResult.put("targetCount", isInfinite ? -1 : targetCountPerDevice); // -1 表示无限循环
                            deviceResult.put("roundStatuses", deviceStatuses);
                            
                            if (isInfinite) {
                                log.info("[{}/{}] 设备 {} 注册已停止: 成功 {}, 失败 {}", deviceIndex + 1, phoneIds.size(), devicePhoneId, deviceSuccessCount, deviceFailCount);
                            } else if (deviceSuccessCount == targetCountPerDevice) {
                                log.info("[{}/{}] 设备 {} 全部注册成功: {}/{}", deviceIndex + 1, phoneIds.size(), devicePhoneId, deviceSuccessCount, targetCountPerDevice);
                            } else {
                                log.warn("[{}/{}] 设备 {} 注册完成: 成功 {}/{}, 失败 {}", deviceIndex + 1, phoneIds.size(), devicePhoneId, deviceSuccessCount, targetCountPerDevice, deviceFailCount);
                            }
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("[{}/{}] 设备 {} 注册被中断", index + 1, phoneIds.size(), phoneId, e);
                        deviceResult.put("status", "INTERRUPTED: " + e.getMessage());
                        deviceResult.put("success", false);
                    } catch (Exception e) {
                        log.error("[{}/{}] 设备 {} 注册异常", index + 1, phoneIds.size(), phoneId, e);
                        deviceResult.put("status", "ERROR: " + e.getMessage());
                        deviceResult.put("success", false);
                    }
                    
                    deviceResult.put("endTime", LocalDateTime.now().toString());
                    return deviceResult;
                }, parallelRegisterExecutor);
                
                futures.add(future);
            }
            
            log.info("=== 已创建 {} 个设备的并行任务，开始等待执行完成 ===", futures.size());
            
            // 等待所有任务完成并收集结果
            int successCount = 0;
            int failCount = 0;
            List<Map<String, Object>> deviceResults = new ArrayList<>();
            
            log.info("=== 开始收集 {} 个设备的执行结果 ===", futures.size());
            for (CompletableFuture<Map<String, Object>> future : futures) {
                try {
                    Map<String, Object> deviceResult = future.get();
                    deviceResults.add(deviceResult);
                    
                    // 按账号数统计（不是按设备数）
                    Integer deviceSuccessCount = (Integer) deviceResult.getOrDefault("successCount", 0);
                    Integer deviceFailCount = (Integer) deviceResult.getOrDefault("failCount", 0);
                    
                    successCount += deviceSuccessCount;
                    failCount += deviceFailCount;
                    
                    // 更新任务信息
                    synchronized (taskInfo) {
                        taskInfo.setSuccessCount(successCount);
                        taskInfo.setFailCount(failCount);
                        int completedCount = successCount + failCount;
                        if (taskInfo.getTotalCount() > 0) {
                            taskInfo.setProgress((int) ((completedCount * 100.0) / taskInfo.getTotalCount()));
                        }
                    }
                } catch (Exception e) {
                    log.error("获取设备注册结果异常", e);
                    // 如果获取结果失败，按每个设备的目标账号数计算失败数
                    failCount += targetCountPerDevice;
                }
            }
            
            // 任务完成
            taskInfo.setStatus("COMPLETED");
            taskInfo.setEndTime(LocalDateTime.now());
            taskInfo.setSuccessCount(successCount);
            taskInfo.setFailCount(failCount);
            log.info("=== 多设备并行注册任务完成: {} === 成功: {}, 失败: {}", taskId, successCount, failCount);
            
        } catch (Exception e) {
            log.error("多设备并行注册任务执行异常: {}", taskId, e);
            taskInfo.setStatus("FAILED");
            taskInfo.setEndTime(LocalDateTime.now());
        }
    }
    
    /**
     * 异步执行多设备并行注册Outlook账号
     */
    @Async
    public void executeParallelOutlookRegisterAsync(List<String> phoneIds, String serverIp, int maxConcurrency, int targetCountPerDevice, String taskId, String logFile, String tiktokVersionDir, Map<String, String> resetParams) {
        TaskInfo taskInfo = taskInfoMap.get(taskId);
        if (taskInfo == null) {
            log.error("任务信息不存在: {}", taskId);
            return;
        }
        
        Semaphore semaphore = new Semaphore(maxConcurrency);
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        
        try {
            log.info("=== 开始创建 {} 个设备的并行任务，最大并发数: {} ===", phoneIds.size(), maxConcurrency);
            for (int i = 0; i < phoneIds.size(); i++) {
                final int index = i;
                final String phoneId = phoneIds.get(i);
                
                if (taskInfo.isStopped()) {
                    log.info("任务 {} 已被停止，停止在第 {} 个设备", taskId, index + 1);
                    break;
                }
                
                final int deviceIndex = index;
                final String devicePhoneId = phoneId;
                CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
                    Map<String, Object> deviceResult = new HashMap<>();
                    deviceResult.put("phoneId", phoneId);
                    deviceResult.put("index", index + 1);
                    deviceResult.put("startTime", LocalDateTime.now().toString());
                    
                    try {
                        semaphore.acquire();
                        try {
                            if (taskInfo.isStopped()) {
                                deviceResult.put("status", "STOPPED");
                                deviceResult.put("success", false);
                                return deviceResult;
                            }
                            
                            boolean isInfinite = (targetCountPerDevice == 0);
                            int deviceSuccessCount = 0;
                            int deviceFailCount = 0;
                            List<String> deviceStatuses = new ArrayList<>();
                            int round = 1;
                            
                            while (true) {
                                if (taskInfo.isStopped()) {
                                    break;
                                }
                                if (!isInfinite && round > targetCountPerDevice) {
                                    break;
                                }
                                
                                String roundInfo = isInfinite ? String.format("第%d轮", round) : String.format("第%d/%d轮", round, targetCountPerDevice);
                                log.info("[{}/{}] 设备 {} {} Outlook注册", deviceIndex + 1, phoneIds.size(), devicePhoneId, roundInfo);
                                String status = registerSingleDeviceWithoutStart(devicePhoneId, serverIp, round, isInfinite ? 0 : targetCountPerDevice, tiktokVersionDir, resetParams, "outlook");
                                deviceStatuses.add(String.format("第%d轮: %s", round, status));
                                
                                if ("SUCCESS".equals(status)) {
                                    deviceSuccessCount++;
                                } else {
                                    deviceFailCount++;
                                }
                                round++;
                            }
                            
                            String finalStatus;
                            if (isInfinite) {
                                finalStatus = String.format("RUNNING: %d成功/%d失败 (已停止)", deviceSuccessCount, deviceFailCount);
                            } else if (deviceSuccessCount == targetCountPerDevice) {
                                finalStatus = "SUCCESS";
                            } else if (deviceSuccessCount > 0) {
                                finalStatus = String.format("PARTIAL: %d成功/%d失败", deviceSuccessCount, deviceFailCount);
                            } else {
                                finalStatus = String.format("FAILED: %d失败", deviceFailCount);
                            }
                            
                            deviceResult.put("status", finalStatus);
                            deviceResult.put("success", !isInfinite && deviceSuccessCount == targetCountPerDevice);
                            deviceResult.put("successCount", deviceSuccessCount);
                            deviceResult.put("failCount", deviceFailCount);
                            deviceResult.put("targetCount", isInfinite ? -1 : targetCountPerDevice);
                            deviceResult.put("roundStatuses", deviceStatuses);
                        } finally {
                            semaphore.release();
                        }
                    } catch (Exception e) {
                        log.error("[{}/{}] 设备 {} Outlook注册异常", index + 1, phoneIds.size(), phoneId, e);
                        deviceResult.put("status", "ERROR: " + e.getMessage());
                        deviceResult.put("success", false);
                    }
                    
                    deviceResult.put("endTime", LocalDateTime.now().toString());
                    return deviceResult;
                }, parallelRegisterExecutor);
                
                futures.add(future);
            }
            
            int successCount = 0;
            int failCount = 0;
            List<Map<String, Object>> deviceResults = new ArrayList<>();
            
            for (CompletableFuture<Map<String, Object>> future : futures) {
                try {
                    Map<String, Object> deviceResult = future.get();
                    deviceResults.add(deviceResult);
                    Integer deviceSuccessCount = (Integer) deviceResult.getOrDefault("successCount", 0);
                    Integer deviceFailCount = (Integer) deviceResult.getOrDefault("failCount", 0);
                    successCount += deviceSuccessCount;
                    failCount += deviceFailCount;
                    
                    synchronized (taskInfo) {
                        taskInfo.setSuccessCount(successCount);
                        taskInfo.setFailCount(failCount);
                        int completedCount = successCount + failCount;
                        if (taskInfo.getTotalCount() > 0) {
                            taskInfo.setProgress((int) ((completedCount * 100.0) / taskInfo.getTotalCount()));
                        }
                    }
                } catch (Exception e) {
                    log.error("获取设备Outlook注册结果异常", e);
                    failCount += targetCountPerDevice;
                }
            }
            
            synchronized (taskInfo) {
                taskInfo.setSuccessCount(successCount);
                taskInfo.setFailCount(failCount);
                taskInfo.setDeviceResults(deviceResults);
                if (taskInfo.isStopped()) {
                    taskInfo.setStatus("STOPPED");
                    taskInfo.setMessage(String.format("已停止: 成功 %d, 失败 %d", successCount, failCount));
                } else {
                    taskInfo.setStatus("COMPLETED");
                    taskInfo.setMessage(String.format("完成: 成功 %d, 失败 %d", successCount, failCount));
                }
                taskInfo.setEndTime(LocalDateTime.now());
            }
            
            log.info("=== 多设备并行注册Outlook账号任务完成: {} ===", taskId);
        } catch (Exception e) {
            log.error("执行多设备并行注册Outlook账号任务异常: {}", taskId, e);
            if (taskInfo != null) {
                taskInfo.setStatus("ERROR");
                taskInfo.setMessage("执行异常: " + e.getMessage());
            }
        }
    }
    
    /**
     * 异步执行批量注册（在Java代码中执行）
     */
    @Async
    public void executeBatchRegisterAsync(List<String> phoneIds, String serverIp, String taskId, String logFile, Map<String, String> resetParams) {
        TaskInfo taskInfo = taskInfoMap.get(taskId);
        if (taskInfo == null) {
            log.error("任务信息不存在: {}", taskId);
            return;
        }
        
        List<Map<String, Object>> deviceResults = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;
        
        try {
            for (int i = 0; i < phoneIds.size(); i++) {
                // 检查是否被停止
                if (taskInfo.isStopped()) {
                    log.info("任务 {} 已被停止，停止在第 {} 个设备", taskId, i + 1);
                    taskInfo.setStatus("STOPPED");
                    taskInfo.setEndTime(LocalDateTime.now());
                    return;
                }
                
                String phoneId = phoneIds.get(i);
                Map<String, Object> deviceResult = new HashMap<>();
                deviceResult.put("phoneId", phoneId);
                deviceResult.put("index", i + 1);
                
                try {
                    log.info("[{}/{}] 开始处理设备: {}", i + 1, phoneIds.size(), phoneId);
                    // ResetPhoneEnv接口会重启设备，所以不需要单独启动步骤
                    // executeBatchRegisterAsync方法已废弃，使用默认的TikTok版本目录
                    String status = registerSingleDeviceWithoutStart(phoneId, serverIp, i + 1, phoneIds.size(), "com.zhiliaoapp.musically_us_43.1.4", resetParams, "random");
                    deviceResult.put("status", status);
                    deviceResult.put("success", "SUCCESS".equals(status));
                    
                    if ("SUCCESS".equals(status)) {
                        successCount++;
                        log.info("[{}/{}] 设备 {} 注册成功", i + 1, phoneIds.size(), phoneId);
                    } else {
                        failCount++;
                        log.warn("[{}/{}] 设备 {} 注册失败: {}", i + 1, phoneIds.size(), phoneId, status);
                    }
                } catch (Exception e) {
                    log.error("[{}/{}] 设备 {} 注册失败", i + 1, phoneIds.size(), phoneId, e);
                    deviceResult.put("status", "ERROR: " + e.getMessage());
                    deviceResult.put("success", false);
                    failCount++;
                }
                
                deviceResults.add(deviceResult);
                
                // 更新任务信息
                taskInfo.setSuccessCount(successCount);
                taskInfo.setFailCount(failCount);
            }
            
            // 任务完成
            taskInfo.setStatus("COMPLETED");
            taskInfo.setEndTime(LocalDateTime.now());
            taskInfo.setSuccessCount(successCount);
            taskInfo.setFailCount(failCount);
            log.info("=== 批量注册任务完成: {} === 成功: {}, 失败: {}", taskId, successCount, failCount);
            
        } catch (Exception e) {
            log.error("批量注册任务执行异常: {}", taskId, e);
            taskInfo.setStatus("FAILED");
            taskInfo.setEndTime(LocalDateTime.now());
            taskInfo.setSuccessCount(successCount);
            taskInfo.setFailCount(failCount);
        }
    }
    
    
    /**
     * 停止任务
     * 
     * @param taskId 任务ID
     * @return 停止结果
     */
    public Map<String, Object> stopTask(String taskId) {
        TaskInfo taskInfo = taskInfoMap.get(taskId);

        // 1) 内存中有正在执行的任务：先标记停止，便于当前线程立刻退出
        if (taskInfo != null) {
            if ("RUNNING".equals(taskInfo.getStatus())) {
                taskInfo.setStopped(true);
                taskInfo.setStatus("STOPPED");
                taskInfo.setEndTime(LocalDateTime.now());
                log.info("任务 {} 已在内存中标记为停止", taskId);
            } else {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "任务当前状态为 " + taskInfo.getStatus() + "，无法停止");
                return result;
            }
        }

        // 2) 任务列表来自数据库，多数任务不在 taskInfoMap；必须同步 DB + stoppedTaskIds，否则报「任务不存在」
        Map<String, Object> dbResult = stopTaskById(taskId);
        if (Boolean.TRUE.equals(dbResult.get("success"))) {
            return dbResult;
        }

        // 3) 仅存在于内存、库中无记录（或库状态已非 RUNNING/PENDING）时，若内存已停成功仍返回成功
        if (taskInfo != null && "STOPPED".equals(taskInfo.getStatus())) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "任务已停止");
            result.put("taskId", taskId);
            result.put("status", "STOPPED");
            return result;
        }

        return dbResult;
    }
    
    /**
     * 查询任务状态
     */
    public Map<String, Object> getTaskStatus(String taskId) {
        TaskInfo taskInfo = taskInfoMap.get(taskId);
        
        if (taskInfo == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "任务不存在");
            return result;
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("taskId", taskInfo.getTaskId());
        result.put("status", taskInfo.getStatus());
        result.put("serverIp", taskInfo.getServerIp());
        result.put("pid", taskInfo.getPid());
        result.put("logFile", taskInfo.getLogFile());
        result.put("startTime", taskInfo.getStartTime() != null ? taskInfo.getStartTime().toString() : null);
        result.put("endTime", taskInfo.getEndTime() != null ? taskInfo.getEndTime().toString() : null);
        // 优先使用totalCount（单设备重复注册），否则使用phoneIds数量（批量注册）
        int totalCount = taskInfo.getTotalCount() > 0 
            ? taskInfo.getTotalCount() 
            : (taskInfo.getPhoneIds() != null ? taskInfo.getPhoneIds().size() : 0);
        result.put("totalCount", totalCount);
        result.put("successCount", taskInfo.getSuccessCount());
        result.put("failCount", taskInfo.getFailCount());
        
        // 计算进度
        if (totalCount > 0) {
            int completedCount = taskInfo.getSuccessCount() + taskInfo.getFailCount();
            double progress = (completedCount * 100.0) / totalCount;
            result.put("progress", progress);
            result.put("completedCount", completedCount);
        } else {
            result.put("progress", 0);
            result.put("completedCount", 0);
        }
        
        // 如果是单设备重复注册任务，返回轮次结果
        if (taskInfo.getRoundResults() != null && !taskInfo.getRoundResults().isEmpty()) {
            result.put("roundResults", taskInfo.getRoundResults());
        }
        
        return result;
    }
    
    /**
     * 获取任务日志
     * 注意：由于任务在Java应用内异步执行，日志在应用日志中
     * 这里返回提示信息，建议通过应用日志查看
     */
    public Map<String, Object> getTaskLog(String taskId, int lines) {
        TaskInfo taskInfo = taskInfoMap.get(taskId);
        
        if (taskInfo == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "任务不存在");
            return result;
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "任务日志在应用日志中，请查看应用日志文件");
        result.put("taskId", taskId);
        result.put("status", taskInfo.getStatus());
        result.put("logs", "日志请查看应用日志文件，搜索任务ID: " + taskId);
        return result;
    }
    
    /**
     * 获取任务列表（分页）
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     */
    public Map<String, Object> getAllTasks(int page, int size) {
        List<Map<String, Object>> taskList = new ArrayList<>();

        if (page < 1) {
            page = 1;
        }
        if (size <= 0) {
            size = 20;
        }

        // 1) 先取数据库里的任务（包含历史），按 created_at 倒序，分页
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TtRegisterTask> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.orderByDesc(TtRegisterTask::getCreatedAt);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<TtRegisterTask> pageReq =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size);
        com.baomidou.mybatisplus.core.metadata.IPage<TtRegisterTask> pageResult =
                ttRegisterTaskRepository.selectPage(pageReq, wrapper);

        for (TtRegisterTask task : pageResult.getRecords()) {
            Map<String, Object> m = new HashMap<>();
            m.put("taskId", task.getTaskId());
            m.put("status", task.getStatus());
            m.put("serverIp", task.getServerIp());
            m.put("pid", null);
            m.put("logFile", null);
            m.put("startTime", task.getCreatedAt() != null ? task.getCreatedAt().toString() : null);
            m.put("endTime", task.getUpdatedAt() != null ? task.getUpdatedAt().toString() : null);
            m.put("successCount", null);
            m.put("failCount", null);
            m.put("totalCount", task.getTargetCount());
            m.put("phoneIds", java.util.Collections.singletonList(task.getPhoneId()));
            taskList.add(m);
        }

        // 2) 再把当前内存中运行的任务信息覆盖进去（保证状态实时）
        for (TaskInfo taskInfo : taskInfoMap.values()) {
            Map<String, Object> override = new HashMap<>();
            override.put("taskId", taskInfo.getTaskId());
            override.put("status", taskInfo.getStatus());
            override.put("serverIp", taskInfo.getServerIp());
            override.put("pid", taskInfo.getPid());
            override.put("logFile", taskInfo.getLogFile());
            override.put("startTime", taskInfo.getStartTime() != null ? taskInfo.getStartTime().toString() : null);
            override.put("endTime", taskInfo.getEndTime() != null ? taskInfo.getEndTime().toString() : null);
            override.put("successCount", taskInfo.getSuccessCount());
            override.put("failCount", taskInfo.getFailCount());
            override.put("totalCount", taskInfo.getTotalCount());
            override.put("phoneIds", taskInfo.getPhoneIds());

            // 如果列表中已存在相同 taskId，则覆盖；否则不追加，避免打破分页大小
            boolean replaced = false;
            for (int i = 0; i < taskList.size(); i++) {
                if (override.get("taskId").equals(taskList.get(i).get("taskId"))) {
                    taskList.set(i, override);
                    replaced = true;
                    break;
                }
            }
            // 如果当前页没有这个 taskId（比如它在其他页），这里不再追加，保持单页条数不超过 size
        }

        // 按开始时间倒序
        taskList.sort((a, b) -> {
            String timeA = (String) a.get("startTime");
            String timeB = (String) b.get("startTime");
            if (timeA == null && timeB == null) return 0;
            if (timeA == null) return 1;
            if (timeB == null) return -1;
            return timeB.compareTo(timeA);
        });

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", taskList);
        result.put("total", pageResult.getTotal());
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    /**
     * 更新任务配置（写入 tt_register_task 表，可从任务小窝编辑）
     */
    public Map<String, Object> updateTaskConfig(Map<String, Object> req) {
        String taskId = (String) req.get("taskId");
        if (taskId == null || taskId.isEmpty()) {
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("message", "taskId 不能为空");
            return res;
        }
        TtRegisterTask task = ttRegisterTaskRepository.findByTaskId(taskId);
        if (task == null) {
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("message", "任务不存在");
            return res;
        }

        if (req.containsKey("country")) task.setCountry((String) req.get("country"));
        if (req.containsKey("sdk")) task.setSdk((String) req.get("sdk"));
        if (req.containsKey("imagePath")) task.setImagePath((String) req.get("imagePath"));
        if (req.containsKey("gaidTag")) task.setGaidTag((String) req.get("gaidTag"));
        if (req.containsKey("dynamicIpChannel")) task.setDynamicIpChannel((String) req.get("dynamicIpChannel"));
        if (req.containsKey("staticIpChannel")) task.setStaticIpChannel((String) req.get("staticIpChannel"));
        if (req.containsKey("biz")) task.setBiz((String) req.get("biz"));
        if (req.containsKey("targetCount")) {
            Object v = req.get("targetCount");
            Integer tc = null;
            if (v instanceof Number) tc = ((Number) v).intValue();
            else if (v instanceof String && !((String) v).isEmpty()) {
                try { tc = Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
            }
            task.setTargetCount(tc);
        }
        task.setUpdatedAt(LocalDateTime.now());
        ttRegisterTaskRepository.updateById(task);

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("message", "任务配置已更新");
        res.put("taskId", taskId);
        return res;
    }

    /**
     * 恢复任务：将任务状态改为 PENDING，交给调度线程重新捞任务执行
     */
    public Map<String, Object> resumeTask(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("message", "taskId 不能为空");
            return res;
        }
        TtRegisterTask task = ttRegisterTaskRepository.findByTaskId(taskId);
        if (task == null) {
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("message", "任务不存在");
            return res;
        }
        task.setStatus("PENDING");
        task.setUpdatedAt(LocalDateTime.now());
        ttRegisterTaskRepository.updateById(task);

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("message", "任务已恢复为待执行状态");
        res.put("taskId", taskId);
        res.put("status", "PENDING");
        return res;
    }

    /**
     * 格式化日志前缀（处理无限循环的情况）
     */
    private String formatLogPrefix(int currentIndex, int totalCount) {
        if (totalCount == 0) {
            return String.format("[%d]", currentIndex);
        } else {
            return String.format("[%d/%d]", currentIndex, totalCount);
        }
    }
    
    /**
     * 从tiktokVersionDir中提取TikTok版本号
     * 支持格式：com.zhiliaoapp.musically_us_43.1.15 -> 43.1.15
     * 
     * @param tiktokVersionDir TikTok版本目录（如：com.zhiliaoapp.musically_us_43.1.15）
     * @return 提取的版本号（如43.1.15），如果无法提取返回null
     */
    private String extractTiktokVersion(String tiktokVersionDir) {
        if (tiktokVersionDir == null || tiktokVersionDir.isEmpty()) {
            return null;
        }
        
        // 如果已经是纯版本号格式（如 43.1.15），直接返回
        if (tiktokVersionDir.matches("^\\d+(\\.\\d+)+$")) {
            return tiktokVersionDir;
        }
        
        // 格式：com.zhiliaoapp.musically_us_43.1.15
        // 找到最后一个下划线的位置，版本号在下划线之后
        int lastUnderscoreIndex = tiktokVersionDir.lastIndexOf('_');
        if (lastUnderscoreIndex >= 0 && lastUnderscoreIndex < tiktokVersionDir.length() - 1) {
            String versionPart = tiktokVersionDir.substring(lastUnderscoreIndex + 1);
            // 验证版本号格式（数字.数字.数字...）
            if (versionPart.matches("^\\d+(\\.\\d+)+$")) {
                return versionPart;
            }
        }
        
        log.warn("无法从tiktokVersionDir中提取版本号: {}", tiktokVersionDir);
        return null;
    }
    
    /**
     * 注册单个设备（统一方法，支持 emailMode）
     * 
     * @param phoneId 设备ID
     * @param serverIp 服务器IP
     * @param currentIndex 当前索引
     * @param totalCount 总数
     * @param tiktokVersionDir TikTok版本目录
     * @param resetParams ResetPhoneEnv参数
     * @param emailMode 邮箱模式："random"（假邮箱）或 "outlook"（真邮箱）
     * @return 注册结果
     */
    private String registerSingleDeviceWithoutStart(String phoneId, String serverIp, int currentIndex, int totalCount, String tiktokVersionDir, Map<String, String> resetParams, String emailMode) {
        try {
            // 跳过启动步骤，直接从ResetPhoneEnv开始（设备已经在运行）
            String logPrefix = formatLogPrefix(currentIndex, totalCount);
            
            // 1. 准备ResetPhoneEnv参数
            String country = resetParams.getOrDefault("country", "");
            if (country == null || country.isEmpty()) {
                country = extractCountryFromPhoneId(phoneId);
            }
            
            String sdk = resetParams.getOrDefault("sdk", "33");
            if (sdk == null || sdk.isEmpty()) {
                sdk = "33";
            }
            
            String imagePath = resetParams.getOrDefault("imagePath", "");
            if (imagePath == null || imagePath.isEmpty()) {
                log.info("[{}/{}] {} - 获取设备镜像名称", currentIndex, totalCount, phoneId);
                imagePath = getDeviceImageName(phoneId, serverIp);
                if (imagePath == null || imagePath.isEmpty()) {
                    log.error("[{}/{}] {} - 获取设备镜像名称失败", currentIndex, totalCount, phoneId);
                    return "FAILED: 获取设备镜像名称失败";
                }
                log.info("[{}/{}] {} - 设备镜像名称: {}", currentIndex, totalCount, phoneId, imagePath);
            }
            
            String gaidTag = resetParams.getOrDefault("gaidTag", "");
            if (gaidTag == null || gaidTag.isEmpty()) {
                gaidTag = extractGaidTagFromPhoneId(phoneId);
            }
            
            // 动态/静态 IP 渠道固定写死为后端约定值，忽略前端传入及随机逻辑
            String dynamicIpChannel = "netnut_biu";
            String staticIpChannel = "ipidea";
            log.info("{} {} - 使用固定IP渠道: dynamicIpChannel={}, staticIpChannel={}", 
                    logPrefix, phoneId, dynamicIpChannel, staticIpChannel);
            
            String biz = resetParams.getOrDefault("biz", "");
            
            // 2. 调用ResetPhoneEnv接口（合并reset和换机功能），带重试逻辑
            log.info("{} {} - 步骤1: 调用ResetPhoneEnv接口（reset+换机）", logPrefix, phoneId);
            log.info("{} {} - ResetPhoneEnv参数: country={}, sdk={}, imagePath={}, gaidTag={}, dynamicIpChannel={}, staticIpChannel={}, biz={}", 
                    logPrefix, phoneId, country, sdk, imagePath, gaidTag, dynamicIpChannel, staticIpChannel, biz);
            
            Map<String, Object> resetResult = null;
            String realIp = null;
            String gaid = null;
            int maxRetries = 3;  // 统一降为3次重试，减少连接数放大
            int retryCount = 0;
            boolean resetSuccess = false;
            
            // 重试逻辑：最多重试5次
            while (retryCount <= maxRetries) {
                try {
                    if (retryCount > 0) {
                        log.warn("{} {} - ResetPhoneEnv重试第 {}/{} 次", logPrefix, phoneId, retryCount, maxRetries);
                        // 重试时等待3秒
                        Thread.sleep(3000);
                    } else {
                        // 首次调用时，添加随机延迟（0-3秒），进一步分散调用时间
                        int randomDelay = ThreadLocalRandom.current().nextInt(0, 3000); // 0-3000毫秒
                        if (randomDelay > 0) {
                            log.debug("{} {} - ResetPhoneEnv首次调用前随机延迟 {}ms，避免并发冲突", logPrefix, phoneId, randomDelay);
                            Thread.sleep(randomDelay);
                        }
                    }
                    
                    // 获取信号量（按服务器分组，控制并发数）
                    Semaphore semaphore = getResetPhoneEnvSemaphore(serverIp);
                    log.debug("{} {} - 等待ResetPhoneEnv调用许可（服务器: {}，当前可用: {}）", logPrefix, phoneId, serverIp, semaphore.availablePermits());
                    semaphore.acquire();
                    long apiCallStartTime = System.currentTimeMillis();
                    try {
                        int currentConcurrency = MAX_RESET_PHONE_ENV_CONCURRENCY_PER_SERVER - semaphore.availablePermits();
                        log.info("{} {} - 开始调用ResetPhoneEnv接口（服务器: {}，当前并发: {}/{}）", 
                                logPrefix, phoneId, serverIp, currentConcurrency, MAX_RESET_PHONE_ENV_CONCURRENCY_PER_SERVER);
                        
                        try {
                            // 10.7 网段仍调用 ResetPhoneEnv；10.13 网段改为调用 TTFarmResetPhone（xray_server_ip 暂写死）
                            if (serverIp != null && serverIp.startsWith("10.13.")) {
                                resetResult = apiService.ttFarmResetPhone(
                                        phoneId,
                                        serverIp,
                                        "192.168.41.84",
                                        country,
                                        sdk,
                                        imagePath,
                                        dynamicIpChannel,
                                        false
                                );
                            } else {
                                resetResult = apiService.resetPhoneEnv(
                                        phoneId,
                                        serverIp,
                                        country,
                                        sdk,
                                        imagePath,
                                        gaidTag,
                                        dynamicIpChannel,
                                        staticIpChannel,
                                        biz
                                );
                            }
                            long apiCallDuration = System.currentTimeMillis() - apiCallStartTime;
                            log.info("{} {} - ResetPhoneEnv接口调用完成，耗时: {}ms", logPrefix, phoneId, apiCallDuration);
                        } catch (Exception e) {
                            long apiCallDuration = System.currentTimeMillis() - apiCallStartTime;
                            log.error("{} {} - ResetPhoneEnv接口调用异常，耗时: {}ms，异常: {}", 
                                    logPrefix, phoneId, apiCallDuration, e.getMessage(), e);
                            // 重新抛出异常，让外层重试逻辑处理
                            throw e;
                        }
                    } finally {
                        // 释放信号量（确保无论是否异常都会释放）
                        semaphore.release();
                        log.debug("{} {} - 释放ResetPhoneEnv调用许可（服务器: {}，当前可用: {}）", logPrefix, phoneId, serverIp, semaphore.availablePermits());
                    }
                    
                    if (resetResult == null) {
                        log.error("{} {} - ResetPhoneEnv调用返回null", logPrefix, phoneId);
                        if (retryCount < maxRetries) {
                            retryCount++;
                            continue;
                        } else {
                            return "FAILED: ResetPhoneEnv调用返回null";
                        }
                    }
                
                @SuppressWarnings("unchecked")
                    Map<String, Object> responseStatus = (Map<String, Object>) resetResult.get("responseStatus");
                if (responseStatus != null) {
                    Object codeObj = responseStatus.get("code");
                    if (codeObj != null) {
                        String codeStr = codeObj.toString();
                        int code = 0;
                        try {
                            code = Integer.parseInt(codeStr);
                        } catch (NumberFormatException e) {
                            if (!codeStr.equals("0")) {
                                String message = (String) responseStatus.get("message");
                                    log.warn("{} {} - ResetPhoneEnv返回非0状态码: code={}, message={}, 重试次数: {}/{}", 
                                            logPrefix, phoneId, codeStr, message, retryCount, maxRetries);
                                    if (retryCount < maxRetries) {
                                        retryCount++;
                                        continue; // 继续重试
                                    } else {
                                        log.error("{} {} - ResetPhoneEnv重试{}次后仍失败: code={}, message={}", 
                                                logPrefix, phoneId, maxRetries, codeStr, message);
                                return "FAILED: ResetPhoneEnv失败 - " + message;
                            }
                        }
                            }
                            
                        if (code != 0) {
                            String message = (String) responseStatus.get("message");
                                log.warn("{} {} - ResetPhoneEnv返回非0状态码: code={}, message={}, 重试次数: {}/{}", 
                                        logPrefix, phoneId, code, message, retryCount, maxRetries);
                                if (retryCount < maxRetries) {
                                    retryCount++;
                                    continue; // 继续重试
                                } else {
                                    log.error("{} {} - ResetPhoneEnv重试{}次后仍失败: code={}, message={}", 
                                            logPrefix, phoneId, maxRetries, code, message);
                            return "FAILED: ResetPhoneEnv失败 (code=" + code + ") - " + message;
                        }
                    }
                            
                            // code == 0，成功
                            resetSuccess = true;
                            break;
                        }
                    }
                    
                    // 如果没有response_status或code，也认为成功（兼容性处理）
                    resetSuccess = true;
                    break;
                    
                } catch (Exception e) {
                    // 记录异常详情，确保异常信息被完整记录
                    log.error("{} {} - ResetPhoneEnv调用发生异常，重试次数: {}/{}，异常类型: {}，异常消息: {}", 
                            logPrefix, phoneId, retryCount, maxRetries, e.getClass().getSimpleName(), e.getMessage(), e);
                    
                    // 判断是否为网关错误（502, 503, 504）
                    boolean isGatewayError = false;
                    String errorMessage = e.getMessage();
                    if (errorMessage != null) {
                        isGatewayError = errorMessage.contains("502") || 
                                        errorMessage.contains("503") || 
                                        errorMessage.contains("504") ||
                                        errorMessage.contains("Bad Gateway") ||
                                        errorMessage.contains("Service Unavailable") ||
                                        errorMessage.contains("Gateway Timeout");
                    }
                    
                    // 判断是否为超时错误
                    boolean isTimeoutError = errorMessage != null && (
                        errorMessage.contains("timeout") || 
                        errorMessage.contains("Timeout") ||
                        errorMessage.contains("Read timed out") ||
                        errorMessage.contains("Connect timed out")
                    );
                    
                    // 计算等待时间：网关错误使用更长的等待时间，并使用指数退避
                    long waitTime;
                    if (isGatewayError) {
                        // 网关错误：5秒基础等待 + 指数退避（最多30秒）
                        waitTime = Math.min(5000 + (long)(Math.pow(2, retryCount) * 1000), 30000);
                        log.warn("{} {} - ResetPhoneEnv调用网关错误，已重试: {}/{}，等待 {}ms 后重试", 
                                logPrefix, phoneId, retryCount, maxRetries, waitTime);
                    } else if (isTimeoutError) {
                        // 超时错误：固定5秒等待
                        waitTime = 5000;
                        log.warn("{} {} - ResetPhoneEnv调用超时，已重试: {}/{}，等待 {}ms 后重试", 
                                logPrefix, phoneId, retryCount, maxRetries, waitTime);
                    } else {
                        // 其他错误：固定3秒等待
                        waitTime = 3000;
                        log.warn("{} {} - ResetPhoneEnv调用异常，已重试: {}/{}，等待 {}ms 后重试", 
                                logPrefix, phoneId, retryCount, maxRetries, waitTime);
                    }
                    
                    if (retryCount < maxRetries) {
                        retryCount++;
                        try {
                            Thread.sleep(waitTime);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            log.error("{} {} - ResetPhoneEnv重试等待被中断", logPrefix, phoneId);
                            return "FAILED: ResetPhoneEnv调用失败 - 重试等待被中断";
                        }
                        continue; // 继续重试
                    } else {
                        log.error("{} {} - ResetPhoneEnv重试{}次后仍异常，最后一次异常: {}", 
                                logPrefix, phoneId, maxRetries, e.getMessage(), e);
                        return "FAILED: ResetPhoneEnv调用失败 - " + e.getMessage();
                    }
                }
            }
            
            if (!resetSuccess || resetResult == null) {
                log.error("{} {} - ResetPhoneEnv最终失败", logPrefix, phoneId);
                return "FAILED: ResetPhoneEnv失败";
            }
            
            // 尝试从响应中获取realIp（兼容realIp和real_ip两种字段名）
            Object realIpObj = resetResult.get("realIp");
            if (realIpObj == null) {
                realIpObj = resetResult.get("real_ip");
            }
            if (realIpObj != null) {
                realIp = realIpObj.toString();
            }
                Object gaidObj = resetResult.get("gaid");
                if (gaidObj != null) {
                    gaid = gaidObj.toString();
                }
            log.info("{} {} - ResetPhoneEnv成功, real_ip={}, gaid={}, 重试次数: {}", logPrefix, phoneId, realIp, gaid, retryCount);

            Thread.sleep(10000); // 等待reset和换机完成

            // 2.4 如果是 MX 国家，调整该设备的 xray 动态代理为 gate1.ipweb.cc:7778 且随机 user
            if ("MX".equalsIgnoreCase(country)) {
                log.info("{} {} - 步骤2.4: 调整 MX 国家设备的 xray 动态代理配置", logPrefix, phoneId);
                adjustXrayDynamicForMx(phoneId, serverIp);
            }

            // 2.5 可选：执行 change_after_reset.sh（如果存在）
            log.info("{} {} - 步骤2.5: 检查并执行 change_after_reset.sh（如存在）", logPrefix, phoneId);
            runChangeAfterResetIfExists(phoneId, serverIp);

            // 3. 安装TikTok APK
            log.info("{} {} - 步骤2: 安装TikTok APK", logPrefix, phoneId);
            boolean installSuccess = installTikTokApk(phoneId, serverIp, tiktokVersionDir);
            if (!installSuccess) {
                log.error("{} {} - 安装TikTok APK失败", logPrefix, phoneId);
                return "FAILED: 安装TikTok APK失败";
            }
            log.info("{} {} - 安装TikTok APK成功", logPrefix, phoneId);
            Thread.sleep(5000);
            
            // 3.5. 添加hook配置
            log.info("{} {} - 步骤2.6: 添加hook配置", logPrefix, phoneId);
            boolean hookConfigSuccess = addAppHookConfig(phoneId, serverIp);
            if (!hookConfigSuccess) {
                log.warn("{} {} - 添加hook配置失败，但继续执行", logPrefix, phoneId);
                // 不因为hook配置失败而中断流程，只记录警告
            } else {
                log.info("{} {} - 添加hook配置成功", logPrefix, phoneId);
            }
            
            // 4. 处理TikTok版本（直接使用tiktokVersionDir参数）
            log.info("{} {} - 步骤3: 处理TikTok版本", logPrefix, phoneId);
                // 如果tiktokVersionDir包含点号，说明已经是版本号格式，直接使用
                // 否则将下划线替换为点（如：43_1_15 -> 43.1.15）
            // 从tiktokVersionDir中提取版本号（支持com.zhiliaoapp.musically_us_43.1.4格式）
            String tiktokVersion = extractTiktokVersion(tiktokVersionDir);
            if (tiktokVersion == null || tiktokVersion.isEmpty()) {
                log.error("{} {} - 无法从tiktokVersionDir中提取版本号: {}", logPrefix, phoneId, tiktokVersionDir);
                return "FAILED: 无法提取TikTok版本号";
            }
            log.info("{} {} - TikTok版本: {} (从 {} 提取)", logPrefix, phoneId, tiktokVersion, tiktokVersionDir);
            
            // 5. 调用注册脚本
            log.info("{} {} - 步骤4: 调用注册脚本", logPrefix, phoneId);
            RegisterContext context = new RegisterContext();
            context.setPhoneId(phoneId);
            context.setServerIp(serverIp);
            context.setSdk(sdk);
            context.setDynamicIpChannel(dynamicIpChannel);
            context.setStaticIpChannel(staticIpChannel);
            context.setRealIp(realIp);
            context.setGaid(gaid);
            context.setTiktokVersion(tiktokVersion);
            context.setCountry(country);
            // 从 resetParams 中获取 Appium 服务器地址
            String appiumServer = resetParams != null ? resetParams.getOrDefault("appiumServer", null) : null;
            if (appiumServer != null && !appiumServer.isEmpty()) {
                context.setAppiumServer(appiumServer);
                log.info("{} {} - 使用任务指定的 Appium 服务器: {}", logPrefix, phoneId, appiumServer);
            }
            
            boolean registerSuccess = executeRegisterScript(context, emailMode);
            if (!registerSuccess) {
                log.error("{} {} - 调用注册脚本失败", logPrefix, phoneId);
                return "FAILED: 调用注册脚本失败";
            }
            log.info("{} {} - 注册脚本执行成功", logPrefix, phoneId);
            
            return "SUCCESS";
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{} - 注册过程被中断", phoneId, e);
            return "FAILED: 注册过程被中断";
        } catch (Exception e) {
            log.error("{} - 注册过程异常", phoneId, e);
            return "FAILED: " + e.getMessage();
        }
    }
    
    /**
     * 主板机注册单个设备（不使用ResetPhoneEnv，使用主板机API）
     * 
     * @param phoneId 主板机ID
     * @param serverIp 服务器IP（主板机不需要，但保留参数以保持接口一致性）
     * @param currentIndex 当前索引
     * @param totalCount 总数
     * @param tiktokVersionDir TikTok版本目录（如43.2.1）
     * @param countryCode 国家代码（如US）
     * @param emailMode 邮箱模式："random"（假邮箱）或 "outlook"（真邮箱）
     * @return 注册结果
     */
    private String registerMainboardDeviceWithoutStart(String phoneId, String serverIp, int currentIndex, int totalCount, 
                                                       String tiktokVersionDir, String countryCode, String emailMode, String adbPort, String appiumServer) {
        try {
            String logPrefix = formatLogPrefix(currentIndex, totalCount);
            
            // 1. 处理TikTok版本（从tiktokVersionDir中提取版本号）
            log.info("{} {} - 步骤1: 处理TikTok版本", logPrefix, phoneId);
            String tiktokVersion = extractTiktokVersion(tiktokVersionDir);
            if (tiktokVersion == null || tiktokVersion.isEmpty()) {
                log.error("{} {} - 无法从tiktokVersionDir中提取版本号: {}", logPrefix, phoneId, tiktokVersionDir);
                return "FAILED: 无法提取TikTok版本号";
            }
            log.info("{} {} - TikTok版本: {} (从 {} 提取)", logPrefix, phoneId, tiktokVersion, tiktokVersionDir);
            
            // 2. 调用主板机换机接口
            log.info("{} {} - 步骤2: 调用主板机换机接口", logPrefix, phoneId);
            if (countryCode == null || countryCode.isEmpty()) {
                countryCode = "US"; // 默认国家代码
            }
            
            String taskId = apiService.switchPhoneTask(phoneId, countryCode, tiktokVersion);
            if (taskId == null || taskId.isEmpty()) {
                log.error("{} {} - 主板机换机接口调用失败，返回的taskId为空", logPrefix, phoneId);
                return "FAILED: 主板机换机接口调用失败";
            }
            log.info("{} {} - 主板机换机接口调用成功，taskId: {}", logPrefix, phoneId, taskId);
            
            // 3. 异步查询任务状态，直到完成
            log.info("{} {} - 步骤3: 查询主板机换机任务状态，taskId: {}", logPrefix, phoneId, taskId);
            boolean taskCompleted = false;
            boolean taskSuccess = false;
            int maxStatusCheckAttempts = 20; // 最多查询10次（5分钟，每次30秒）
            int statusCheckCount = 0;
            
            while (!taskCompleted && statusCheckCount < maxStatusCheckAttempts) {
                Thread.sleep(30000); // 每30秒查询一次
                statusCheckCount++;
                
                Map<String, Object> statusResult = apiService.getMainboardTaskStatus(taskId);
                if (statusResult == null) {
                    log.warn("{} {} - 查询主板机任务状态失败（第{}次），继续重试", logPrefix, phoneId, statusCheckCount);
                            continue;
                    }
                    
                // 解析任务状态
                    @SuppressWarnings("unchecked")
                Map<String, Object> response = (Map<String, Object>) statusResult.get("response");
                if (response != null) {
                    Integer code = (Integer) response.get("code");
                    String status = (String) statusResult.get("status");
                    
                    log.info("{} {} - 主板机任务状态查询（第{}次）: code={}, status={}", logPrefix, phoneId, statusCheckCount, code, status);
                    
                    if (code != null && code == 0) {
                        // 检查任务状态
                        if ("completed".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status)) {
                            taskCompleted = true;
                            taskSuccess = true;
                            log.info("{} {} - 主板机换机任务完成，状态: {}", logPrefix, phoneId, status);
                        } else if ("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status)) {
                            taskCompleted = true;
                            taskSuccess = false;
                            log.error("{} {} - 主板机换机任务失败，状态: {}", logPrefix, phoneId, status);
                                } else {
                            // 任务还在进行中（running, pending等）
                            log.debug("{} {} - 主板机换机任务进行中，状态: {}", logPrefix, phoneId, status);
                        }
                    } else {
                        String errMsg = (String) response.get("err_msg");
                        log.warn("{} {} - 主板机任务状态查询返回非0状态码: code={}, err_msg={}", logPrefix, phoneId, code, errMsg);
                    }
                    } else {
                    log.warn("{} {} - 主板机任务状态查询响应格式错误，缺少response字段", logPrefix, phoneId);
                }
            }
            
            if (!taskCompleted) {
                log.error("{} {} - 主板机换机任务查询超时（{}次查询后仍未完成）", logPrefix, phoneId, maxStatusCheckAttempts);
                return "FAILED: 主板机换机任务查询超时";
            }
            
            if (!taskSuccess) {
                log.error("{} {} - 主板机换机任务失败", logPrefix, phoneId);
                return "FAILED: 主板机换机任务失败";
            }
            
            log.info("{} {} - 主板机换机成功，等待设备就绪", logPrefix, phoneId);
            Thread.sleep(10000); // 等待设备就绪
            
            // 4. 调用注册脚本（不需要安装APK，主板机接口会自动安装）
            log.info("{} {} - 步骤4: 调用注册脚本", logPrefix, phoneId);
            RegisterContext context = new RegisterContext();
            context.setPhoneId(phoneId);
            context.setServerIp(serverIp);
            context.setTiktokVersion(tiktokVersion);
            context.setCountry(countryCode);
            
            // 设置adb_port（如果提供）
            if (adbPort != null && !adbPort.isEmpty()) {
                context.setAdbPort(adbPort);
                log.info("{} {} - 使用adb_port: {}", logPrefix, phoneId, adbPort);
            }
            
            // 使用任务中指定的 Appium 服务器
            if (appiumServer != null && !appiumServer.isEmpty()) {
                context.setAppiumServer(appiumServer);
                log.info("{} {} - 使用任务指定的 Appium 服务器: {}", logPrefix, phoneId, appiumServer);
            } else {
                // 如果没有指定，使用默认服务器
                context.setAppiumServer("10.13.55.85");
                log.warn("{} {} - 任务中未指定 Appium 服务器，使用默认值: 10.13.55.85", logPrefix, phoneId);
            }
            
            boolean registerSuccess = executeRegisterScript(context, emailMode);
            if (!registerSuccess) {
                log.error("{} {} - 调用注册脚本失败", logPrefix, phoneId);
                return "FAILED: 调用注册脚本失败";
            }
            log.info("{} {} - 注册脚本执行成功", logPrefix, phoneId);
            
            return "SUCCESS";
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{} - 主板机注册过程被中断", phoneId, e);
            return "FAILED: 注册过程被中断";
        } catch (Exception e) {
            log.error("{} - 主板机注册过程异常", phoneId, e);
            return "FAILED: " + e.getMessage();
        }
    }

    /**
     * 获取设备镜像名称
     * 通过docker inspect命令获取容器的镜像名称
     * 
     * @param phoneId 云手机ID
     * @param serverIp 服务器IP
     * @return 镜像名称，如果获取失败返回null
     */
    private String getDeviceImageName(String phoneId, String serverIp) {
        try {
            // 使用docker inspect获取容器的镜像名称
            SshUtil.SshResult imageResult = sshCommand(serverIp, 
                String.format("docker inspect --format='{{.Config.Image}}' %s 2>/dev/null || docker ps --filter name=^%s$ --format '{{.Image}}' 2>/dev/null", 
                    phoneId, phoneId));
            
            if (imageResult.isSuccess() && imageResult.getOutput() != null) {
                String imageName = imageResult.getOutput().trim();
                if (!imageName.isEmpty() && !imageName.contains("Error") && !imageName.contains("No such")) {
                    log.info("{} - 获取到镜像名称: {}", phoneId, imageName);
                    return imageName;
                }
            }
            
            log.warn("{} - 无法通过docker命令获取镜像名称", phoneId);
            // 如果配置了默认镜像，使用默认镜像
            if (defaultImage != null && !defaultImage.isEmpty()) {
                log.info("{} - 使用配置的默认镜像: {}", phoneId, defaultImage);
                return defaultImage;
            }
            log.error("{} - 无法获取镜像名称且未配置默认镜像", phoneId);
            return null;
        } catch (Exception e) {
            log.error("{} - 获取设备镜像名称异常", phoneId, e);
            return null;
        }
    }
    
    /**
     * 执行换机操作（使用phone_switch.sh脚本）
     * 
     * @deprecated 已废弃，现在使用ResetPhoneEnv接口来合并reset和换机功能
     * @param phoneId 云手机ID
     * @param serverIp 服务器IP
     * @param changeDeviceJson 换机json
     * @return 是否成功
     */
    @Deprecated
    private boolean changeDevice(String phoneId, String serverIp, String changeDeviceJson) {
        try {
            // phone_switch.sh脚本需要model_json_file参数，需要将json写入文件
            String jsonFile = String.format("/tmp/change_device_%s_%d.json", phoneId, System.currentTimeMillis());
            
            // 将json写入临时文件（使用base64编码避免特殊字符问题）
            String base64Json = java.util.Base64.getEncoder().encodeToString(changeDeviceJson.getBytes("UTF-8"));
            String writeJsonCmd = String.format(
                "echo '%s' | base64 -d > %s && chmod 644 %s", 
                base64Json, jsonFile, jsonFile);
            
            SshUtil.SshResult writeResult = sshCommand(serverIp, writeJsonCmd);
            if (!writeResult.isSuccess()) {
                // 如果base64不可用，使用Python写入（更可靠）
                log.warn("{} - base64方式写入失败，尝试使用Python写入", phoneId);
                String pythonWriteCmd = String.format(
                    "python3 -c \"import json, sys; f=open('%s', 'w', encoding='utf-8'); f.write(json.dumps(json.loads(sys.stdin.read()))); f.close()\" << 'EOFJSON'\n%s\nEOFJSON",
                    jsonFile, changeDeviceJson);
                writeResult = sshCommand(serverIp, pythonWriteCmd);
                
                if (!writeResult.isSuccess()) {
                    log.error("{} - Python方式写入也失败，尝试使用heredoc", phoneId);
                    // 最后尝试使用heredoc，但需要对特殊字符进行转义
                    String heredocDelimiter = "JSONEOF" + System.currentTimeMillis();
                    writeJsonCmd = String.format(
                        "cat > %s << '%s'\n%s\n%s",
                        jsonFile, heredocDelimiter, changeDeviceJson, heredocDelimiter);
                    writeResult = sshCommand(serverIp, writeJsonCmd);
                }
            }
            
            if (!writeResult.isSuccess()) {
                log.error("{} - 写入换机json文件失败: {}", phoneId, writeResult.getErrorMessage());
                return false;
            }
            
            // 调用phone_switch.sh脚本
            // 参数：--id <id> --model_json_file <file> [--check] [--fast]
            // 这里使用normal模式（默认），不使用--fast和--check
            String switchCmd = String.format(
                "bash /home/ubuntu/agent/scripts/phone_switch.sh --id %s --model_json_file %s", 
                phoneId, jsonFile);
            
            SshUtil.SshResult changeResult = sshCommand(serverIp, switchCmd);
            
            // 清理临时文件
            sshCommand(serverIp, String.format("rm -f %s", jsonFile));
            
            if (changeResult.isSuccess()) {
                log.info("{} - 换机脚本执行成功", phoneId);
                // 检查输出中是否有错误信息
                String output = changeResult.getOutput();
                if (output != null && (output.contains("Error") || output.contains("Failed") || output.contains("失败"))) {
                    log.warn("{} - 换机脚本执行完成，但可能有警告: {}", phoneId, output);
                }
                return true;
            } else {
                log.error("{} - 换机脚本执行失败: {}", phoneId, changeResult.getErrorMessage());
                // 检查退出码
                if (changeResult.getExitCode() == 10 || changeResult.getExitCode() == 11) {
                    log.error("{} - 换机脚本参数错误", phoneId);
                } else if (changeResult.getExitCode() == 12) {
                    log.error("{} - 容器未运行", phoneId);
                }
                return false;
            }
        } catch (Exception e) {
            log.error("{} - 换机操作异常", phoneId, e);
            return false;
        }
    }

    /**
     * 安装TikTok APK
     * 使用云手机服务器上的install.sh脚本安装APK
     * 
     * @param phoneId 云手机ID
     * @param serverIp 服务器IP
     * @param tiktokVersionDir TikTok版本目录（例如：com.zhiliaoapp.musically_us_43.1.4）
     * @return 是否成功
     */
    private boolean installTikTokApk(String phoneId, String serverIp, String tiktokVersionDir) {
        try {
            String hostApkDir = "/apk_repo/" + tiktokVersionDir;
            
            // 1. 检查是否存在install.sh脚本
            String checkInstallScriptCmd = String.format(
                "test -f %s/install.sh && echo 'exists' || echo 'not_exists'",
                hostApkDir
            );
            
            log.info("{} - 检查install.sh脚本是否存在: {}", phoneId, hostApkDir);
            SshUtil.SshResult checkResult = sshCommand(serverIp, checkInstallScriptCmd);
            
            boolean hasInstallScript = false;
            if (checkResult.isSuccess() && checkResult.getOutput() != null) {
                hasInstallScript = checkResult.getOutput().trim().equals("exists");
            }
            
            if (hasInstallScript) {
                // 方式1：使用install.sh脚本安装（原有方式）
                log.info("{} - 检测到install.sh脚本，使用脚本方式安装", phoneId);
                return installTikTokApkWithScript(phoneId, serverIp, tiktokVersionDir);
            } else {
                // 方式2：直接安装APK文件
                log.info("{} - 未检测到install.sh脚本，使用直接安装APK方式", phoneId);
                return installTikTokApkDirectly(phoneId, serverIp, tiktokVersionDir);
            }
        } catch (Exception e) {
            log.error("{} - 安装APK异常", phoneId, e);
            return false;
        }
    }
    
    /**
     * 使用install.sh脚本安装APK（原有方式）
     */
    private boolean installTikTokApkWithScript(String phoneId, String serverIp, String tiktokVersionDir) {
        try {
            String hostApkDir = "/apk_repo/" + tiktokVersionDir;
            String containerApkDir = "/" + tiktokVersionDir;
            
            // 将所有操作合并为一个命令，减少SSH连接次数
            // 1. 复制整个目录到容器根目录（使用 / 作为目标） 2. 在容器内进入目录执行安装脚本
            String combinedCommand = String.format(
                "docker cp %s %s:/ && " +
                "docker exec %s sh -c 'cd %s && sh install.sh %s' 2>&1",
                hostApkDir, phoneId,
                phoneId, containerApkDir, phoneId
            );
            
            log.info("{} - 执行APK安装（复制目录并在容器内执行，单次SSH连接）", phoneId);
            SshUtil.SshResult installResult = sshCommand(serverIp, combinedCommand);
            
            // 检查输出中是否包含"install success"或"success"关键字
            String output = installResult.getOutput();
            boolean hasSuccess = output != null && (output.contains("install success") || output.contains("success"));
            
            if (installResult.isSuccess() || hasSuccess) {
                log.info("{} - APK安装脚本执行成功: {}", phoneId, output);
                
                if (hasSuccess) {
                    log.info("{} - APK安装成功（基于脚本输出确认）", phoneId);
                    return true;
                }
                
                // 如果脚本执行成功但没有明确的success标识，进行验证
                SshUtil.SshResult checkResult = sshCommand(serverIp, 
                    String.format("docker exec %s adb shell pm list packages 2>/dev/null | grep com.zhiliaoapp.musically || echo ''", phoneId));
                if (checkResult.isSuccess() && checkResult.getOutput().contains("com.zhiliaoapp.musically")) {
                    log.info("{} - APK安装验证成功", phoneId);
                    return true;
                } else {
                    log.warn("{} - APK安装脚本执行成功（退出码: {}），验证时未找到应用包，但视为成功", phoneId, installResult.getExitCode());
                    return true;
                }
            } else {
                log.error("{} - APK安装脚本执行失败: {}", phoneId, installResult.getErrorMessage());
                Integer exitCode = installResult.getExitCode();
                if (exitCode != null) {
                    log.error("{} - 安装脚本退出码: {}", phoneId, exitCode);
                }
                return false;
            }
        } catch (Exception e) {
            log.error("{} - 使用脚本安装APK异常", phoneId, e);
            return false;
        }
    }
    
    /**
     * 直接安装APK文件（当目录下没有install.sh时）
     */
    private boolean installTikTokApkDirectly(String phoneId, String serverIp, String tiktokVersionDir) {
        try {
            String hostApkDir = "/apk_repo/" + tiktokVersionDir;
            
            // 1. 查找目录下的APK文件（查找第一个.apk文件）
            String findApkCmd = String.format(
                "ls %s/*.apk 2>/dev/null | head -1",
                hostApkDir
            );
            
            log.info("{} - 查找APK文件: {}", phoneId, hostApkDir);
            SshUtil.SshResult findResult = sshCommand(serverIp, findApkCmd);
            
            if (!findResult.isSuccess() || findResult.getOutput() == null || findResult.getOutput().trim().isEmpty()) {
                log.error("{} - 未找到APK文件，目录: {}", phoneId, hostApkDir);
                return false;
            }
            
            String apkFilePath = findResult.getOutput().trim();
            log.info("{} - 找到APK文件: {}", phoneId, apkFilePath);
            
            // 2. 将APK文件复制到容器根目录
            String apkFileName = new java.io.File(apkFilePath).getName();
            String containerApkPath = "/" + apkFileName;
            String copyCommand = String.format(
                "docker cp %s %s:%s",
                apkFilePath, phoneId, containerApkPath
            );
            
            log.info("{} - 复制APK文件到容器: {} -> {}", phoneId, apkFilePath, containerApkPath);
            SshUtil.SshResult copyResult = sshCommand(serverIp, copyCommand);
            
            if (!copyResult.isSuccess()) {
                log.error("{} - 复制APK文件失败: {}", phoneId, copyResult.getErrorMessage());
                return false;
            }
            
            // 3. 在容器内使用pm install安装APK（尝试使用根目录）
            String installCommand = String.format(
                "docker exec %s sh -c \"pm install -r %s\" 2>&1",
                phoneId, containerApkPath
            );
            
            log.info("{} - 执行pm install安装APK: {}", phoneId, containerApkPath);
            SshUtil.SshResult installResult = sshCommand(serverIp, installCommand);
            
            String output = installResult.getOutput();
            boolean installSuccess = installResult.isSuccess() || 
                (output != null && (output.contains("Success") || output.contains("success")));
            
            if (installSuccess) {
                log.info("{} - APK安装成功: {}", phoneId, output);
                
                // 验证安装是否成功
                SshUtil.SshResult checkResult = sshCommand(serverIp, 
                    String.format("docker exec %s adb shell pm list packages 2>/dev/null | grep com.zhiliaoapp.musically || echo ''", phoneId));
                if (checkResult.isSuccess() && checkResult.getOutput().contains("com.zhiliaoapp.musically")) {
                    log.info("{} - APK安装验证成功", phoneId);
                    return true;
                } else {
                    log.warn("{} - APK安装成功但验证时未找到应用包，视为成功", phoneId);
                    return true;
                }
            } else {
                log.error("{} - APK安装失败: {}", phoneId, output != null ? output : installResult.getErrorMessage());
                return false;
            }
        } catch (Exception e) {
            log.error("{} - 直接安装APK异常", phoneId, e);
            return false;
        }
    }
    
    /**
     * 添加应用hook配置
     * 执行以下命令：
     * 1. docker cp /home/ubuntu/agent/scripts/add_app_hook_config.sh "$PHONE_ID:/system/etc/add_app_hook_config.sh"
     * 2. docker exec "$PHONE_ID" chmod +x /system/etc/add_app_hook_config.sh
     * 3. docker exec "$PHONE_ID" sh /system/etc/add_app_hook_config.sh "$PACKAGE_NAME" 2>&1
     * 
     * @param phoneId 云手机ID
     * @param serverIp 服务器IP
     * @return 是否成功
     */
    private boolean addAppHookConfig(String phoneId, String serverIp) {
        try {
            String packageName = "com.zhiliaoapp.musically";
            String hookScriptPath = "/home/ubuntu/agent/scripts/add_app_hook_config.sh";
            String containerHookPath = "/system/etc/add_app_hook_config.sh";
            
            // 1. 复制hook脚本到容器
            log.info("{} - 复制hook脚本到容器: {} -> {}", phoneId, hookScriptPath, containerHookPath);
            String copyCmd = String.format(
                "docker cp %s %s:%s",
                hookScriptPath, phoneId, containerHookPath
            );
            SshUtil.SshResult copyResult = sshCommand(serverIp, copyCmd);
            if (!copyResult.isSuccess()) {
                log.error("{} - 复制hook脚本失败: {}", phoneId, copyResult.getErrorMessage());
                return false;
            }
            
            // 2. 添加执行权限
            log.info("{} - 添加hook脚本执行权限", phoneId);
            String chmodCmd = String.format(
                "docker exec %s chmod +x %s",
                phoneId, containerHookPath
            );
            SshUtil.SshResult chmodResult = sshCommand(serverIp, chmodCmd);
            if (!chmodResult.isSuccess()) {
                log.error("{} - 添加hook脚本执行权限失败: {}", phoneId, chmodResult.getErrorMessage());
                return false;
            }
            
            // 3. 执行hook脚本
            log.info("{} - 执行hook脚本: {} {}", phoneId, containerHookPath, packageName);
            String execCmd = String.format(
                "docker exec %s sh %s %s 2>&1",
                phoneId, containerHookPath, packageName
            );
            SshUtil.SshResult execResult = sshCommand(serverIp, execCmd);
            
            String output = execResult.getOutput();
            if (execResult.isSuccess()) {
                log.info("{} - hook脚本执行成功: {}", phoneId, output);
                return true;
            } else {
                log.error("{} - hook脚本执行失败: {}, 输出: {}", phoneId, execResult.getErrorMessage(), output);
                return false;
            }
        } catch (Exception e) {
            log.error("{} - 添加hook配置异常", phoneId, e);
            return false;
        }
    }

    /**
     * 注册上下文信息
     * 用于在注册过程中传递各种信息
     */
    private static class RegisterContext {
        private String phoneId;
        private String serverIp;
        private String sdk;
        private String dynamicIpChannel;
        private String staticIpChannel;
        private String realIp;
        private String gaid;
        private String tiktokVersion;
        private String country;
        private String adbPort;
        private String appiumServer;  // Appium服务器地址
        private LocalDateTime scriptStartTime;
        
        // Getters and Setters
        public String getPhoneId() { return phoneId; }
        public void setPhoneId(String phoneId) { this.phoneId = phoneId; }
        public String getServerIp() { return serverIp; }
        public void setServerIp(String serverIp) { this.serverIp = serverIp; }
        public String getSdk() { return sdk; }
        public void setSdk(String sdk) { this.sdk = sdk; }
        public String getDynamicIpChannel() { return dynamicIpChannel; }
        public void setDynamicIpChannel(String dynamicIpChannel) { this.dynamicIpChannel = dynamicIpChannel; }
        public String getStaticIpChannel() { return staticIpChannel; }
        public void setStaticIpChannel(String staticIpChannel) { this.staticIpChannel = staticIpChannel; }
        public String getRealIp() { return realIp; }
        public void setRealIp(String realIp) { this.realIp = realIp; }
        public String getGaid() { return gaid; }
        public void setGaid(String gaid) { this.gaid = gaid; }
        public String getTiktokVersion() { return tiktokVersion; }
        public void setTiktokVersion(String tiktokVersion) { this.tiktokVersion = tiktokVersion; }
        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }
        public String getAdbPort() { return adbPort; }
        public void setAdbPort(String adbPort) { this.adbPort = adbPort; }
        public String getAppiumServer() { return appiumServer; }
        public void setAppiumServer(String appiumServer) { this.appiumServer = appiumServer; }
        public LocalDateTime getScriptStartTime() { return scriptStartTime; }
        public void setScriptStartTime(LocalDateTime scriptStartTime) { this.scriptStartTime = scriptStartTime; }
    }
    
    /**
     * 执行注册脚本（统一方法，支持 email_mode 参数）
     * 使用跳板机连接ssh root@10.7.124.25执行/data/appium/com_zhiliaoapp_musically/tiktok_register_us_motherboard.py脚本
     * 解析脚本输出中的账号信息并保存到数据库
     * 
     * @param context 注册上下文信息
     * @param emailMode 邮箱模式："random"（假邮箱）或 "outlook"（真邮箱）
     * @return 是否成功
     */
    @Transactional
    private boolean executeRegisterScript(RegisterContext context, String emailMode) {
        String phoneId = context.getPhoneId();
        String serverIp = context.getServerIp();
        try {
            // 注册脚本在Appium服务器上，使用root用户
            // 优先使用任务中指定的 Appium 服务器，如果没有则使用默认值
            String scriptHost = context.getAppiumServer();
            if (scriptHost == null || scriptHost.isEmpty()) {
                scriptHost = "10.13.55.85"; // 默认 Appium 服务器地址（向后兼容）
                log.warn("{} - 任务中未指定 Appium 服务器，使用默认值: {}", phoneId, scriptHost);
            }

            // 根据国家选择对应的注册脚本
            String country = context.getCountry();
            if (country == null || country.isEmpty()) {
                country = "US"; // 默认国家代码
            }
            String scriptPath;
            if ("BR".equalsIgnoreCase(country)) {
                scriptPath = "/data/appium/com_zhiliaoapp_musically/tiktok_register_br_test_account.py";
            } else {
                scriptPath = "/data/appium/com_zhiliaoapp_musically/tiktok_register_us_test_account.py";
            }
            
            // 构建命令参数：传递所有5个参数
            // 参数顺序：offerid phoneId phoneServerIp appiumServer country
            String offerid = "test";
            String appiumServer = getRandomAppiumServer(); // 随机选择Appium服务器端口，避免单点瓶颈
            
            // 云手机直接使用serverIp，不需要IP映射
            String phoneServerIpForScript = serverIp;
            
            // 使用 python3 -u 禁用缓冲，确保输出实时发送
            // 使用 stdbuf -o0 进一步确保无缓冲输出
            // 使用 timeout 命令包装，确保超时后自动终止（20分钟超时，比SSH超时15分钟稍长）
            // 如果脚本卡住，timeout 会在20分钟后自动终止进程并返回退出码124
            int scriptTimeoutMinutes = 20; // 脚本超时时间（分钟）
            
            // 统一命令格式：无论真假邮箱，都传递 country、emailMode 和 adbPort 参数
            // 参数顺序：offerid phoneId phoneServerIp appiumServer country email_mode adb_port
            String adbPortParam = context.getAdbPort();
            if (adbPortParam == null || adbPortParam.isEmpty()) {
                adbPortParam = ""; // 如果未提供adb_port，传递空字符串
            }
            
            String command = String.format(
                "cd /data/appium/com_zhiliaoapp_musically && timeout %dm stdbuf -o0 -e0 python3 -u %s %s %s %s %s %s %s %s",
                scriptTimeoutMinutes, scriptPath, offerid, phoneId, phoneServerIpForScript, appiumServer, country, emailMode, adbPortParam);
            
            log.info("{} - 执行注册脚本: {} 在服务器 {} (超时: {}分钟, emailMode: {})", phoneId, scriptPath, scriptHost, scriptTimeoutMinutes, emailMode);
            log.info("{} - 脚本参数: offerid={}, phoneId={}, phoneServerIp={}(原始serverIp={}), appiumServer={}, country={}, emailMode={}, adbPort={}", 
                    phoneId, offerid, phoneId, phoneServerIpForScript, serverIp, appiumServer, country, emailMode, adbPortParam);
            
            // 记录脚本开始执行时间
            long startTime = System.currentTimeMillis();
            LocalDateTime scriptStartTime = LocalDateTime.now();
            context.setScriptStartTime(scriptStartTime); // 设置到上下文
            
            // 使用普通SSH执行方法
            SshUtil.SshResult registerResult = sshCommand(scriptHost, command);
            
            // 记录脚本执行耗时
            long executionTime = System.currentTimeMillis() - startTime;
            log.info("{} - 注册脚本执行耗时: {} 秒", phoneId, executionTime / 1000);
            
                String output = registerResult.getOutput();
            String errorOutput = registerResult.getErrorOutput();
            Integer exitCode = registerResult.getExitCode();
            
            log.info("{} - 注册脚本执行完成，退出码: {}, 输出长度: {}", phoneId, exitCode, 
                    output != null ? output.length() : 0);
            
            // 重要：验证脚本是否真的完成了
            // 如果退出码是0，需要验证：
            // 1. 输出中是否包含完整的账号数据（"完整账号数据"关键字）
            // 2. 脚本进程是否还在运行
            if (exitCode != null && exitCode == 0) {
                boolean hasCompleteData = output != null && output.contains("完整账号数据");
                
                // 检查脚本进程是否还在运行
                String checkProcessCmd = String.format(
                    "ps aux | grep -E 'python3.*%s.*%s' | grep -v grep | wc -l",
                    scriptPath, phoneId
                );
                SshUtil.SshResult processCheck = sshCommand(scriptHost, checkProcessCmd);
                
                boolean scriptStillRunning = false;
                if (processCheck.isSuccess() && processCheck.getOutput() != null) {
                    try {
                        int processCount = Integer.parseInt(processCheck.getOutput().trim());
                        scriptStillRunning = processCount > 0;
                    } catch (NumberFormatException e) {
                        log.warn("{} - 无法解析进程检查结果: {}", phoneId, processCheck.getOutput());
                    }
                }
                
                // 如果脚本还在运行，说明SSH通道提前关闭了
                if (scriptStillRunning) {
                    log.error("{} - 检测到脚本进程仍在运行，但SSH通道已关闭（退出码0），这是异常情况！", phoneId);
                    log.error("{} - 脚本可能还在执行中，但Java代码已经认为执行完成了", phoneId);
                    // 将退出码设置为-1，表示异常情况
                    exitCode = -1;
                    registerResult.setExitCode(-1);
                    registerResult.setSuccess(false);
                } else if (!hasCompleteData && executionTime < 60000) {
                    // 如果执行时间少于1分钟且没有完整数据，可能是提前返回
                    log.warn("{} - 脚本退出码为0但执行时间很短（{}秒）且未找到完整账号数据，可能脚本还在运行", 
                            phoneId, executionTime / 1000);
                }
            }
            
            // 检查是否是超时退出（timeout 命令退出码为124）
            if (exitCode != null && exitCode == 124) {
                log.error("{} - 注册脚本执行超时（{}分钟），脚本可能卡住，已自动终止", phoneId, scriptTimeoutMinutes);
                
                // 尝试检查并清理可能的僵尸进程
                try {
                    String checkProcessCmd = String.format(
                        "ps aux | grep -E 'python3.*%s.*%s' | grep -v grep | awk '{print $2}' | xargs -r kill -9 2>/dev/null || echo 'no_process'",
                        scriptPath, phoneId
                    );
                    SshUtil.SshResult processCheck = sshCommand(scriptHost, checkProcessCmd);
                    if (processCheck.isSuccess() && processCheck.getOutput() != null && 
                        !processCheck.getOutput().trim().equals("no_process")) {
                        log.info("{} - 已清理可能的僵尸进程", phoneId);
                    }
                } catch (Exception e) {
                    log.warn("{} - 清理僵尸进程时出错: {}", phoneId, e.getMessage());
                }
                
                // 超时时也要尝试解析输出中的outlook_info等信息（真实邮箱已花费成本，需要保存）
                if ("outlook".equals(emailMode) && output != null && !output.trim().isEmpty()) {
                    log.info("{} - 脚本超时，尝试解析已获取的Outlook邮箱信息", phoneId);
                    TtAccountRegister partialRecord = parseRegisterOutputPartial(output, context);
                    if (partialRecord != null) {
                        // 解析成功，保存部分记录
                        partialRecord.setRegisterSuccess(false); // 标记为失败（因为超时）
                        partialRecord.setRegisterType("REAL_EMAIL");
                        partialRecord.setCreatedAt(LocalDateTime.now());
                        partialRecord.setUpdatedAt(LocalDateTime.now());
                        
                        int insertResult = ttAccountRegisterRepository.insert(partialRecord);
                        if (insertResult > 0) {
                            log.info("{} - 超时情况下已保存Outlook邮箱信息: email={}, outlookInfo长度={}", 
                                    phoneId, partialRecord.getEmail(), 
                                    partialRecord.getOutlookInfo() != null ? partialRecord.getOutlookInfo().length() : 0);
                        } else {
                            log.warn("{} - 超时情况下保存Outlook邮箱信息失败", phoneId);
                        }
                    } else {
                        log.warn("{} - 脚本超时且无法从输出中解析到Outlook邮箱信息", phoneId);
                        // 保存失败记录（没有邮箱信息）
                        saveFailureRecord(context, "REAL_EMAIL");
                    }
                } else {
                    // 假邮箱超时场景：尽量解析并落库 email/password，失败再写普通失败记录
                    boolean saved = trySaveFailedFakeEmailRecord(output, context);
                    if (!saved) {
                        saveFailureRecord(context, "outlook".equals(emailMode) ? "REAL_EMAIL" : "FAKE_EMAIL");
                    }
                }
                
                return false;
            }
            
            // 判断是否成功：
            // 1. 退出码为0表示成功
            // 2. 如果退出码为-1但通道已关闭（可能是跳板机SSH命令的退出状态未正确传递），检查输出内容判断是否成功
            // 脚本成功时会输出："注册成功！账号信息：" 和 "end do register ."
            // 脚本失败时会输出："failed and exit(2) from chen"
            boolean isSuccess = false;
            if (exitCode != null && exitCode == 0) {
                isSuccess = true;
            } else if (exitCode != null && exitCode == -1) {
                // 退出码为-1，可能是跳板机SSH命令的退出状态未正确传递
                // 检查输出中是否有成功标识（脚本实际输出的关键词）
                if (output != null) {
                    // 检查失败标识（优先级更高）
                    if (output.contains("failed and exit(2) from chen")) {
                        log.warn("{} - 退出码为-1但输出中包含失败标识，认为执行失败", phoneId);
                        isSuccess = false;
                    } 
                    // 检查成功标识
                    else if (output.contains("注册成功！账号信息：") || output.contains("end do register .")) {
                        log.info("{} - 退出码为-1但输出中包含成功标识，认为执行成功", phoneId);
                        isSuccess = true;
                    } 
                    // 兼容旧的关键词（以防万一）
                    else if (output.contains("register tiktok success") || output.contains("register success")) {
                        log.info("{} - 退出码为-1但输出中包含成功标识（兼容关键词），认为执行成功", phoneId);
                        isSuccess = true;
                    } else {
                        log.warn("{} - 退出码为-1且输出中无明确标识，认为执行失败。输出最后50字符: {}", 
                                phoneId, output.length() > 50 ? output.substring(output.length() - 50) : output);
                        isSuccess = false;
                    }
                } else {
                    log.warn("{} - 退出码为-1且输出为空，认为执行失败", phoneId);
                    isSuccess = false;
                }
            } else {
                isSuccess = false;
            }
            
            if (isSuccess) {
                log.info("{} - 注册脚本执行成功", phoneId);
                
                // 解析脚本输出中的账号信息
                TtAccountRegister accountRegister = parseRegisterOutput(output, context);
                if (accountRegister != null) {
                    // 保存到数据库
                    accountRegister.setCreatedAt(LocalDateTime.now());
                    accountRegister.setUpdatedAt(LocalDateTime.now());
                    accountRegister.setRegisterSuccess(true); // 注册成功
                    // 根据 emailMode 设置注册类型
                    accountRegister.setRegisterType("outlook".equals(emailMode) ? "REAL_EMAIL" : "FAKE_EMAIL");
                    int insertResult = ttAccountRegisterRepository.insert(accountRegister);
                    if (insertResult > 0) {
                        log.info("{} - 账号注册信息已保存到数据库: email={}, username={}, behavior={}", 
                                phoneId, accountRegister.getEmail(), accountRegister.getUsername(), accountRegister.getBehavior());
                        
                        // 注册成功后，调用GetTrafficData API获取流量数据并更新数据库
                        try {
                            String trafficData = apiService.getTrafficData(phoneId);
                            if (trafficData != null && !trafficData.trim().isEmpty()) {
                                // 更新流量数据到数据库
                                accountRegister.setTrafficData(trafficData);
                                accountRegister.setUpdatedAt(LocalDateTime.now());
                                int updateResult = ttAccountRegisterRepository.updateById(accountRegister);
                                if (updateResult > 0) {
                                    log.info("{} - 流量数据已更新到数据库: trafficData={}", phoneId, trafficData);
                                } else {
                                    log.warn("{} - 流量数据更新失败: trafficData={}", phoneId, trafficData);
                                }
                            } else {
                                log.warn("{} - 获取流量数据失败或为空", phoneId);
                            }
                        } catch (Exception e) {
                            log.error("{} - 获取或更新流量数据时出错", phoneId, e);
                            // 流量数据获取失败不影响注册成功状态
                        }
                        
                        // 根据条件调用备份接口
                        // 假邮箱：is_2fa_setup_success为true且registerSuccess为true
                        // 真邮箱：registerSuccess为true
                        boolean shouldBackup = false;
                        String registerType = accountRegister.getRegisterType();
                        
                        if ("FAKE_EMAIL".equals(registerType)) {
                            // 假邮箱：只要registerSuccess为true且is_2fa_setup_success为1(true)或2(DELAYED)，都调用备份
                            Integer is2faSetupSuccess = accountRegister.getIs2faSetupSuccess();
                            boolean is2faEligible = (is2faSetupSuccess != null &&
                                    (is2faSetupSuccess == 1 || is2faSetupSuccess == 2));
                            if (is2faEligible && Boolean.TRUE.equals(accountRegister.getRegisterSuccess())) {
                                shouldBackup = true;
                                log.info("{} - 假邮箱注册成功且2FA状态为 {}，需要调用备份接口", phoneId, 
                                        is2faSetupSuccess == 1 ? "TRUE" : "DELAYED");
                            } else {
                                String statusDesc = is2faSetupSuccess == null ? "null" :
                                    (is2faSetupSuccess == 1 ? "true" : (is2faSetupSuccess == 2 ? "DELAYED" : "false"));
                                log.info("{} - 假邮箱注册成功但不满足备份条件: is_2fa_setup_success={}({}), registerSuccess={}", 
                                        phoneId, is2faSetupSuccess, statusDesc, accountRegister.getRegisterSuccess());
                            }
                        } else if ("REAL_EMAIL".equals(registerType)) {
                            // 真邮箱：只需要registerSuccess为true
                            if (Boolean.TRUE.equals(accountRegister.getRegisterSuccess())) {
                                shouldBackup = true;
                                log.info("{} - 真邮箱注册成功，需要调用备份接口", phoneId);
                            } else {
                                log.info("{} - 真邮箱注册未成功，不调用备份接口: registerSuccess={}", 
                                        phoneId, accountRegister.getRegisterSuccess());
                            }
                        }
                        
                        // 调用备份接口
                        if (shouldBackup) {
                            try {
                                boolean backupResult = apiService.backupApp(phoneId, serverIp, "com.zhiliaoapp.musically");
                                // 保存备份接口调用结果到数据库
                                accountRegister.setBackupSuccess(backupResult);
                                // 更新数据库记录
                                ttAccountRegisterRepository.updateById(accountRegister);
                                
                                if (backupResult) {
                                    log.info("{} - 备份接口调用成功: phoneId={}, serverIp={}, packageName=com.zhiliaoapp.musically", 
                                            phoneId, serverIp);
                                } else {
                                    log.warn("{} - 备份接口调用失败: phoneId={}, serverIp={}, packageName=com.zhiliaoapp.musically", 
                                            phoneId, serverIp);
                                }
                            } catch (Exception e) {
                                log.error("{} - 调用备份接口时出错", phoneId, e);
                                // 备份接口调用失败，记录为false
                                accountRegister.setBackupSuccess(false);
                                try {
                                    ttAccountRegisterRepository.updateById(accountRegister);
                                } catch (Exception updateEx) {
                                    log.error("{} - 更新备份接口调用结果失败", phoneId, updateEx);
                                }
                                // 备份接口调用失败不影响注册成功状态
                            }
                        } else {
                            // 不需要调用备份接口，设置为null
                            accountRegister.setBackupSuccess(null);
                            try {
                                ttAccountRegisterRepository.updateById(accountRegister);
                            } catch (Exception updateEx) {
                                log.error("{} - 更新备份接口调用结果失败", phoneId, updateEx);
                            }
                        }
                    } else {
                        log.warn("{} - 账号注册信息保存失败", phoneId);
                    }
                    return true;
                } else {
                    log.warn("{} - 注册脚本执行成功，但无法解析账号信息", phoneId);
                    // 即使无法解析，脚本执行成功也返回true
                    return true;
                }
            } else {
                log.error("{} - 注册脚本执行失败，退出码: {}", phoneId, exitCode);
                
                // 如果退出码不是124（超时），检查脚本进程是否还在运行（可能是SSH超时但脚本仍在运行）
                if (exitCode != null && exitCode != 124) {
                    try {
                        String checkProcessCmd = String.format(
                            "ps aux | grep -E 'python3.*%s.*%s' | grep -v grep | awk '{print $2}' | head -1",
                            scriptPath, phoneId
                        );
                        SshUtil.SshResult processCheck = sshCommand(scriptHost, checkProcessCmd);
                        if (processCheck.isSuccess() && processCheck.getOutput() != null && 
                            !processCheck.getOutput().trim().isEmpty()) {
                            String pid = processCheck.getOutput().trim();
                            log.warn("{} - 检测到脚本进程仍在运行（PID: {}，退出码: {}），尝试强制终止", phoneId, pid, exitCode);
                            // 强制终止脚本进程
                            String killCmd = String.format("kill -9 %s 2>/dev/null || true", pid);
                            sshCommand(scriptHost, killCmd);
                            log.info("{} - 已尝试强制终止脚本进程 PID: {}", phoneId, pid);
                        }
                    } catch (Exception e) {
                        log.warn("{} - 检查/清理脚本进程时出错: {}", phoneId, e.getMessage());
                    }
                }
                
                // 输出详细的错误信息（仅输出最后几行，避免日志过长）
                if (output != null && !output.trim().isEmpty()) {
                    String[] lines = output.split("\n");
                    int showLines = Math.min(10, lines.length);
                    String lastLines = String.join("\n", 
                        java.util.Arrays.copyOfRange(lines, lines.length - showLines, lines.length));
                    log.error("{} - 注册脚本标准输出（最后{}行）: {}", phoneId, showLines, lastLines);
                }
                
                if (errorOutput != null && !errorOutput.trim().isEmpty()) {
                    String[] lines = errorOutput.split("\n");
                    int showLines = Math.min(10, lines.length);
                    String lastLines = String.join("\n", 
                        java.util.Arrays.copyOfRange(lines, lines.length - showLines, lines.length));
                    log.error("{} - 注册脚本错误输出（最后{}行）: {}", phoneId, showLines, lastLines);
                }
                
                // 根据退出码提供更详细的错误信息（仅用于日志）
                if (exitCode != null) {
                    if (exitCode == 124) {
                        log.error("{} - 注册脚本执行超时（20分钟）", phoneId);
                    } else if (exitCode == 1) {
                        log.error("{} - 注册脚本执行失败，可能是Python环境问题或资源文件缺失", phoneId);
                        if (errorOutput != null && errorOutput.contains("FileNotFoundError")) {
                            log.error("{} - 检测到文件未找到错误，请检查Python依赖包和资源文件是否正确安装", phoneId);
                        }
                    } else if (exitCode == -1) {
                        log.error("{} - SSH连接异常，退出码为-1", phoneId);
                    } else {
                        log.error("{} - 注册脚本执行失败，退出码: {}", phoneId, exitCode);
                    }
                } else {
                    log.error("{} - 注册脚本执行失败，无法获取退出码", phoneId);
                }
                
                // 对于真实邮箱模式，即使失败也要尝试解析输出中的outlook_info等信息（已花费成本）
                if ("outlook".equals(emailMode) && output != null && !output.trim().isEmpty()) {
                    log.info("{} - 脚本执行失败，但尝试解析已获取的Outlook邮箱信息", phoneId);
                    TtAccountRegister partialRecord = parseRegisterOutputPartial(output, context);
                    if (partialRecord != null) {
                        // 解析成功，保存部分记录
                        partialRecord.setRegisterSuccess(false); // 标记为失败
                        partialRecord.setRegisterType("REAL_EMAIL");
                        partialRecord.setCreatedAt(LocalDateTime.now());
                        partialRecord.setUpdatedAt(LocalDateTime.now());
                        
                        int insertResult = ttAccountRegisterRepository.insert(partialRecord);
                        if (insertResult > 0) {
                            log.info("{} - 失败情况下已保存Outlook邮箱信息: email={}, outlookInfo长度={}", 
                                    phoneId, partialRecord.getEmail(), 
                                    partialRecord.getOutlookInfo() != null ? partialRecord.getOutlookInfo().length() : 0);
                        } else {
                            log.warn("{} - 失败情况下保存Outlook邮箱信息失败", phoneId);
                            // 保存失败记录（没有邮箱信息）
                            saveFailureRecord(context, "REAL_EMAIL");
                        }
                    } else {
                        log.warn("{} - 脚本执行失败且无法从输出中解析到Outlook邮箱信息", phoneId);
                        // 保存失败记录（没有邮箱信息）
                        saveFailureRecord(context, "REAL_EMAIL");
                    }
                } else {
                    // 假邮箱失败场景：尽量解析并落库 email/password，失败再写普通失败记录
                    boolean saved = trySaveFailedFakeEmailRecord(output, context);
                    if (!saved) {
                        saveFailureRecord(context, "outlook".equals(emailMode) ? "REAL_EMAIL" : "FAKE_EMAIL");
                    }
                }
                
                return false;
            }
        } catch (Exception e) {
            log.error("{} - 执行注册脚本异常", phoneId, e);
            
            // 保存异常失败记录到数据库（根据 emailMode 设置注册类型）
            try {
                saveFailureRecord(context, "outlook".equals(emailMode) ? "REAL_EMAIL" : "FAKE_EMAIL");
            } catch (Exception saveException) {
                log.warn("{} - 保存失败记录时出错: {}", phoneId, saveException.getMessage());
            }
            
            // 异常情况下也尝试清理可能的僵尸进程
            try {
                // 根据国家选择对应的脚本路径进行清理
                String country = context.getCountry();
                if (country == null || country.isEmpty()) {
                    country = "US";
                }
                String scriptPathForCleanup;
                if ("BR".equalsIgnoreCase(country)) {
                    scriptPathForCleanup = "/data/appium/com_zhiliaoapp_musically/tiktok_register_br_test_account.py";
                } else {
                    scriptPathForCleanup = "/data/appium/com_zhiliaoapp_musically/tiktok_register_us_test_account.py";
                }
                String scriptHostForCleanup = "10.13.55.85"; // 云手机Appium服务器地址
                String killCmd = String.format(
                    "ps aux | grep -E 'python3.*%s.*%s' | grep -v grep | awk '{print $2}' | xargs -r kill -9 2>/dev/null || echo 'no_process'",
                    scriptPathForCleanup, phoneId
                );
                SshUtil.SshResult killResult = sshCommand(scriptHostForCleanup, killCmd);
                if (killResult.isSuccess() && killResult.getOutput() != null && 
                    !killResult.getOutput().trim().equals("no_process")) {
                    log.info("{} - 异常情况下已清理可能的僵尸进程", phoneId);
                }
            } catch (Exception cleanupException) {
                log.warn("{} - 清理僵尸进程时出错", phoneId, cleanupException);
            }
            
            return false;
        }
    }
    
    /**
     * 解析注册脚本输出中的账号信息
     * 脚本输出格式示例：
     *   email: test@example.com
     *   password: password123
     *   username: testuser
     *   nickname_behavior_result: result
     * 
     * 注意：IP地址直接从ResetPhoneEnv API返回的realIp获取，不从脚本输出中读取
     * 
     * @param output 脚本输出
     * @param context 注册上下文信息
     * @return 解析后的账号信息，如果解析失败返回null
     */
    private TtAccountRegister parseRegisterOutput(String output, RegisterContext context) {
        String phoneId = context.getPhoneId();
        try {
            if (output == null || output.trim().isEmpty()) {
                log.warn("{} - 脚本输出为空", phoneId);
                return null;
            }
            
            TtAccountRegister accountRegister = new TtAccountRegister();
            accountRegister.setPhoneId(phoneId);
            accountRegister.setPhoneServerIp(context.getServerIp()); // 设置云手机服务器IP
            accountRegister.setRegisterTime(LocalDateTime.now()); // 设置注册时间为当前时间
            accountRegister.setScriptStartTime(context.getScriptStartTime()); // 设置注册脚本开始执行时间
            
            // 设置固定字段
            accountRegister.setNurtureJsonSource("ResetPhoneEnv"); // 写死为ResetPhoneEnv
            
            // 设置从上下文获取的字段
            accountRegister.setGaid(context.getGaid());
            accountRegister.setAndroidVersion(getAndroidVersionFromSdk(context.getSdk()));
            
            // 设置IP渠道（优先使用dynamicIpChannel，如果为空则使用staticIpChannel）
            String ipChannel = context.getDynamicIpChannel();
            if (ipChannel == null || ipChannel.isEmpty()) {
                ipChannel = context.getStaticIpChannel();
            }
            accountRegister.setIpChannel(ipChannel);
            
            // 设置IP地址（直接使用ResetPhoneEnv API返回的realIp）
            accountRegister.setIp(context.getRealIp());
            
            accountRegister.setTiktokVersion(context.getTiktokVersion());
            // 设置国家代码
            accountRegister.setCountry(context.getCountry());
            
            // 复用公共解析逻辑
            boolean foundAny = parseOutputToAccountRegister(output, accountRegister);
            
            if (!foundAny) {
                log.warn("{} - 无法在输出中找到账号信息，输出内容: {}", phoneId, output);
                return null;
            }
            
            // 尝试从outlook_info JSON中提取email（最后的后备方案）
            // 注意：不提取password，因为outlook_info中的password是Outlook邮箱密码，不是TikTok注册密码
            extractInfoFromOutlookInfo(accountRegister);
            
            // 验证必要字段（email和password是必填的）
            if (accountRegister.getEmail() == null || accountRegister.getEmail().isEmpty()) {
                log.warn("{} - 解析到的账号信息缺少email字段", phoneId);
                return null;
            }
            
            if (accountRegister.getPassword() == null || accountRegister.getPassword().isEmpty()) {
                log.warn("{} - 解析到的账号信息缺少password字段", phoneId);
                return null;
            }
            
            // 记录最终解析结果和password来源（用于问题定位）
            // 注意：password只从脚本输出获取，不从outlook_info提取（outlook_info中的password是Outlook邮箱密码）
            log.info("{} - [解析完成] 最终password值: {} (来源: 仅从脚本输出解析)", 
                    phoneId, accountRegister.getPassword());
            
            // 格式化is2faSetupSuccess显示（1=true, 0=false, 2=DELAYED）
            Integer is2faValue = accountRegister.getIs2faSetupSuccess();
            String is2faDisplay = is2faValue == null ? "null" : 
                (is2faValue == 1 ? "1(true)" : (is2faValue == 2 ? "2(DELAYED)" : "0(false)"));
            log.info("{} - 成功解析账号信息: email={}, username={}, behavior={}, ip={}, gaid={}, androidVersion={}, ipChannel={}, tiktokVersion={}, authenticatorKey={}, outlookInfo={}, is2faSetupSuccess={}", 
                    phoneId, accountRegister.getEmail(), accountRegister.getUsername(), accountRegister.getBehavior(),
                    accountRegister.getIp(), accountRegister.getGaid(), accountRegister.getAndroidVersion(),
                    accountRegister.getIpChannel(), accountRegister.getTiktokVersion(), accountRegister.getAuthenticatorKey(),
                    accountRegister.getOutlookInfo(), is2faDisplay);
            return accountRegister;
            
        } catch (Exception e) {
            log.error("{} - 解析注册脚本输出异常", phoneId, e);
            return null;
        }
    }
    
    /**
     * 解析注册脚本输出中的账号信息（部分解析，用于超时/失败场景）
     * 放宽验证条件：只要有outlook_info或email即可保存（不要求password）
     * 用于保存真实邮箱场景下已生成但未完成注册的邮箱信息
     * 
     * @param output 脚本输出
     * @param context 注册上下文信息
     * @return 解析后的账号信息，如果解析失败返回null
     */
    private TtAccountRegister parseRegisterOutputPartial(String output, RegisterContext context) {
        String phoneId = context.getPhoneId();
        try {
            if (output == null || output.trim().isEmpty()) {
                log.warn("{} - 脚本输出为空（部分解析）", phoneId);
                return null;
            }
            
            // 复用公共解析逻辑，但不验证必填字段
            TtAccountRegister accountRegister = new TtAccountRegister();
            accountRegister.setPhoneId(phoneId);
            accountRegister.setPhoneServerIp(context.getServerIp());
            accountRegister.setRegisterTime(LocalDateTime.now());
            accountRegister.setScriptStartTime(context.getScriptStartTime());
            accountRegister.setNurtureJsonSource("ResetPhoneEnv");
            accountRegister.setGaid(context.getGaid());
            accountRegister.setAndroidVersion(getAndroidVersionFromSdk(context.getSdk()));
            
            String ipChannel = context.getDynamicIpChannel();
            if (ipChannel == null || ipChannel.isEmpty()) {
                ipChannel = context.getStaticIpChannel();
            }
            accountRegister.setIpChannel(ipChannel);
            accountRegister.setIp(context.getRealIp());
            accountRegister.setTiktokVersion(context.getTiktokVersion());
            
            // 复用公共解析逻辑
            boolean foundAny = parseOutputToAccountRegister(output, accountRegister);
            
            if (!foundAny) {
                log.warn("{} - 无法在输出中找到账号信息（部分解析）", phoneId);
                return null;
            }
            
            // 尝试从outlook_info JSON中提取email（最后的后备方案）
            // 注意：不提取password，因为outlook_info中的password是Outlook邮箱密码，不是TikTok注册密码
            extractInfoFromOutlookInfo(accountRegister);
            
            // 放宽验证条件：只要有outlook_info或email即可保存（不要求password）
            if ((accountRegister.getOutlookInfo() == null || accountRegister.getOutlookInfo().isEmpty() || accountRegister.getOutlookInfo().equals("{}"))
                && (accountRegister.getEmail() == null || accountRegister.getEmail().isEmpty())) {
                log.warn("{} - 部分解析：既没有outlook_info也没有email，无法保存", phoneId);
                return null;
            }
            
            log.info("{} - 部分解析成功（超时场景）: email={}, outlookInfo长度={}", 
                    phoneId, accountRegister.getEmail(), 
                    accountRegister.getOutlookInfo() != null ? accountRegister.getOutlookInfo().length() : 0);
            return accountRegister;
            
        } catch (Exception e) {
            log.error("{} - 部分解析注册脚本输出异常", phoneId, e);
            return null;
        }
    }
    
    /**
     * 将输出解析到accountRegister对象（提取的公共解析逻辑）
     * 
     * @param output 脚本输出
     * @param accountRegister 账号注册对象（会被填充）
     * @return 是否找到任何字段
     */
    private boolean parseOutputToAccountRegister(String output, TtAccountRegister accountRegister) {
        String phoneId = accountRegister.getPhoneId();
        boolean foundAny = false;
        
        // 先尝试解析多行格式的outlook_info（格式：=== Outlook邮箱信息 === ... === Outlook邮箱信息结束 ===）
        Pattern outlookInfoMultiLinePattern = Pattern.compile(
            "===\\s*Outlook邮箱信息[^=]*===\\s*\\n(.*?)\\n===\\s*Outlook邮箱信息结束\\s*===", 
            Pattern.MULTILINE | Pattern.DOTALL
        );
        Matcher outlookInfoMultiLineMatcher = outlookInfoMultiLinePattern.matcher(output);
        if (outlookInfoMultiLineMatcher.find()) {
            String outlookInfoJson = outlookInfoMultiLineMatcher.group(1).trim();
            accountRegister.setOutlookInfo(outlookInfoJson);
            foundAny = true;
            log.debug("{} - 解析到outlook_info（多行格式）", phoneId);
        }
        
        // 先尝试解析 key: value 格式（单行格式）
        Pattern pattern1 = Pattern.compile("(?:^|\\n)\\s*(email|password|username|nickname_behavior_result|authenticator_key|outlook_info|is_2fa_setup_success|need_retention)\\s*:\\s*(.+?)(?:\\n|$)", Pattern.MULTILINE);
        Matcher matcher1 = pattern1.matcher(output);
        
        while (matcher1.find()) {
            String key = matcher1.group(1);
            String value = matcher1.group(2).trim();
            foundAny = true;
            
            switch (key) {
                case "email":
                    if (accountRegister.getEmail() == null || accountRegister.getEmail().isEmpty()) {
                        accountRegister.setEmail(value);
                        log.info("{} - [key:value格式] 解析到email: {}", phoneId, value);
                    } else {
                        log.debug("{} - [key:value格式] email已存在，跳过: 已有值={}, 新值={}", phoneId, accountRegister.getEmail(), value);
                    }
                    break;
                case "password":
                    if (accountRegister.getPassword() == null || accountRegister.getPassword().isEmpty()) {
                        accountRegister.setPassword(value);
                        log.info("{} - [key:value格式] 解析到password: {}", phoneId, value);
                    } else {
                        log.warn("{} - [key:value格式] password已存在，跳过覆盖: 已有值={}, 新值={}", phoneId, accountRegister.getPassword(), value);
                    }
                    break;
                case "username":
                    if (accountRegister.getUsername() == null || accountRegister.getUsername().isEmpty()) {
                        accountRegister.setUsername(value);
                        log.debug("{} - [key:value格式] 解析到username: {}", phoneId, value);
                    }
                    break;
                case "nickname_behavior_result":
                    if (accountRegister.getBehavior() == null || accountRegister.getBehavior().isEmpty()) {
                        accountRegister.setBehavior(value);
                        log.debug("{} - [key:value格式] 解析到nickname_behavior_result: {}", phoneId, value);
                    }
                    break;
                case "authenticator_key":
                    if (accountRegister.getAuthenticatorKey() == null || accountRegister.getAuthenticatorKey().isEmpty()) {
                        accountRegister.setAuthenticatorKey(value);
                        log.debug("{} - [key:value格式] 解析到authenticator_key: {}", phoneId, value);
                    }
                    break;
                case "outlook_info":
                    // 如果之前没有从多行格式解析到，则使用单行格式的值
                    if (accountRegister.getOutlookInfo() == null || accountRegister.getOutlookInfo().isEmpty()) {
                        accountRegister.setOutlookInfo(value);
                        log.debug("{} - [key:value格式] 解析到outlook_info", phoneId);
                    }
                    break;
                case "is_2fa_setup_success":
                    // 支持 true→1, false→0, DELAYED→2 三种值
                    Integer is2faValue = parseIs2faSetupSuccessValue(value);
                    accountRegister.setIs2faSetupSuccess(is2faValue);
                    log.debug("{} - [key:value格式] 解析到is_2fa_setup_success: {}", phoneId, is2faValue);
                    break;
                case "need_retention":
                    // 支持 need_retention: 0/1/2（留存脚本 exitCode=8 会写入 2）
                    Integer needRetention = parseNeedRetentionValue(value);
                    accountRegister.setNeedRetention(needRetention);
                    log.debug("{} - [key:value格式] 解析到need_retention: {}", phoneId, needRetention);
                    break;
            }
        }
        
        // 尝试解析Python字典格式（优化正则，支持包含特殊字符的密码）
        // 注意：使用非贪婪匹配，但确保能匹配到完整的值（包括特殊字符如 # @ 等）
        Pattern pattern2 = Pattern.compile("['\"](email|password|username|nickname_behavior_result|authenticator_key|outlook_info|is_2fa_setup_success|need_retention)['\"]\\s*:\\s*(?:['\"]([^'\"]*?)['\"]|(True|False|true|false|1|0|\\{\\}))", Pattern.MULTILINE | Pattern.DOTALL);
        Matcher matcher2 = pattern2.matcher(output);
        
        while (matcher2.find()) {
            String key = matcher2.group(1);
            String value = matcher2.group(2) != null ? matcher2.group(2).trim() : (matcher2.group(3) != null ? matcher2.group(3).trim() : "");
            foundAny = true;
            
            switch (key) {
                case "email":
                    // 只在字段为空时才设置，确保不被覆盖
                    if (accountRegister.getEmail() == null || accountRegister.getEmail().isEmpty()) {
                        if (!value.isEmpty()) {
                            accountRegister.setEmail(value);
                            log.info("{} - [字典格式] 解析到email: {}", phoneId, value);
                        }
                    } else {
                        log.debug("{} - [字典格式] email已存在，跳过: 已有值={}, 新值={}", phoneId, accountRegister.getEmail(), value);
                    }
                    break;
                case "password":
                    // 只在字段为空时才设置，确保不被覆盖（这是关键修复）
                    if (accountRegister.getPassword() == null || accountRegister.getPassword().isEmpty()) {
                        if (!value.isEmpty()) {
                            accountRegister.setPassword(value);
                            log.info("{} - [字典格式] 解析到password: {}", phoneId, value);
                        }
                    } else {
                        log.warn("{} - [字典格式] password已存在，跳过覆盖: 已有值={}, 新值={}", phoneId, accountRegister.getPassword(), value);
                    }
                    break;
                case "username":
                    if (accountRegister.getUsername() == null || accountRegister.getUsername().isEmpty()) {
                        if (!value.isEmpty()) {
                            accountRegister.setUsername(value);
                            log.debug("{} - [字典格式] 解析到username: {}", phoneId, value);
                        }
                    }
                    break;
                case "nickname_behavior_result":
                    if (accountRegister.getBehavior() == null || accountRegister.getBehavior().isEmpty()) {
                        if (!value.isEmpty()) {
                            accountRegister.setBehavior(value);
                            log.debug("{} - [字典格式] 解析到nickname_behavior_result: {}", phoneId, value);
                        }
                    }
                    break;
                case "authenticator_key":
                    if (accountRegister.getAuthenticatorKey() == null || accountRegister.getAuthenticatorKey().isEmpty()) {
                        if (!value.isEmpty()) {
                            accountRegister.setAuthenticatorKey(value);
                            log.debug("{} - [字典格式] 解析到authenticator_key: {}", phoneId, value);
                        }
                    }
                    break;
                case "outlook_info":
                    if (value.equals("{}") || value.isEmpty()) {
                        if (accountRegister.getOutlookInfo() == null || accountRegister.getOutlookInfo().isEmpty()) {
                            accountRegister.setOutlookInfo("{}");
                            log.debug("{} - [字典格式] 解析到outlook_info: {}", phoneId);
                        }
                    } else if (accountRegister.getOutlookInfo() == null || accountRegister.getOutlookInfo().isEmpty()) {
                        accountRegister.setOutlookInfo(value);
                        log.debug("{} - [字典格式] 解析到outlook_info", phoneId);
                    }
                    break;
                case "is_2fa_setup_success":
                    // 支持 true→1, false→0, DELAYED→2 三种值
                    Integer is2faValue = parseIs2faSetupSuccessValue(value);
                    accountRegister.setIs2faSetupSuccess(is2faValue);
                    log.debug("{} - [字典格式] 解析到is_2fa_setup_success: {}", phoneId, is2faValue);
                    break;
                case "need_retention":
                    // 支持 need_retention: 0/1/2（留存脚本 exitCode=8 会写入 2）
                    Integer needRetention2 = parseNeedRetentionValue(value);
                    accountRegister.setNeedRetention(needRetention2);
                    log.debug("{} - [字典格式] 解析到need_retention: {}", phoneId, needRetention2);
                    break;
            }
        }
        
        return foundAny;
    }
    
    /**
     * 从outlook_info JSON中提取email信息（仅提取email，不提取password）
     * 注意：outlook_info中的password是Outlook邮箱密码，不是TikTok注册密码，所以不提取
     * 
     * @param accountRegister 账号注册对象
     */
    private void extractInfoFromOutlookInfo(TtAccountRegister accountRegister) {
        if (accountRegister.getOutlookInfo() == null || accountRegister.getOutlookInfo().isEmpty() || accountRegister.getOutlookInfo().equals("{}")) {
            return;
        }
        
        try {
            String outlookInfoStr = accountRegister.getOutlookInfo();
            // outlook_info格式可能是：
            // 1. JSON格式：{"email": "xxx", "password": "xxx", ...}
            // 2. Python字典格式：{'email': 'xxx', 'password': 'xxx', ...}
            // 3. 已经是标准JSON格式（从多行格式中提取的）
            // 注意：outlook_info中的password是Outlook邮箱密码，不是TikTok注册密码
            
            String jsonStr = outlookInfoStr.trim();
            
            // 如果是Python字典格式（单引号），转换为JSON格式（双引号）
            // 注意：简单的replace可能不够准确，但通常Python字典和JSON格式相似
            if (jsonStr.startsWith("{") && jsonStr.contains("'")) {
                // Python字典格式，替换单引号为双引号（简单处理）
                jsonStr = jsonStr.replace("'", "\"");
            }
            
            // 尝试解析JSON
            @SuppressWarnings("unchecked")
            Map<String, Object> outlookInfoMap = objectMapper.readValue(jsonStr, Map.class);
            
            // 从outlook_info中提取email（如果accountRegister中没有的话）
            // 注意：这是最后的后备方案，优先级最低
            // 注意：不提取password，因为outlook_info中的password是Outlook邮箱密码，不是TikTok注册密码
            if (accountRegister.getEmail() == null || accountRegister.getEmail().isEmpty()) {
                Object emailObj = outlookInfoMap.get("email");
                if (emailObj != null) {
                    String emailFromOutlook = emailObj.toString();
                    accountRegister.setEmail(emailFromOutlook);
                    log.info("{} - [outlook_info提取] 从outlook_info中提取到email: {}", accountRegister.getPhoneId(), emailFromOutlook);
                } else {
                    log.debug("{} - [outlook_info提取] outlook_info中未找到email字段", accountRegister.getPhoneId());
                }
            } else {
                log.debug("{} - [outlook_info提取] email已存在，跳过从outlook_info提取: {}", accountRegister.getPhoneId(), accountRegister.getEmail());
            }
            
        } catch (Exception e) {
            // JSON解析失败，可能是格式不对，不影响主流程
            log.debug("{} - 从outlook_info解析JSON失败，可能是格式问题: {}", accountRegister.getPhoneId(), e.getMessage());
        }
    }
    
    /**
     * 解析布尔值字符串
     * 支持格式：True, False, true, false, 1, 0
     * 
     * @param value 字符串值
     * @return Boolean值，如果无法解析则返回null
     */
    private Boolean parseBooleanValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        if ("True".equalsIgnoreCase(trimmed) || "1".equals(trimmed)) {
            return true;
        } else if ("False".equalsIgnoreCase(trimmed) || "0".equals(trimmed)) {
            return false;
        }
        return null;
    }

    /**
     * 解析 need_retention 字段值
     * 支持：
     * 0/False/False-like => 0
     * 1/True/True-like  => 1
     * 2/2              => 2（留存脚本 exitCode=8 更新）
     *
     * @param value 原始字符串值
     * @return 解析后的值（0/1/2），如果无法解析返回 null
     */
    private Integer parseNeedRetentionValue(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if ("2".equals(trimmed)) return 2;
        if ("True".equalsIgnoreCase(trimmed) || "1".equals(trimmed)) return 1;
        if ("False".equalsIgnoreCase(trimmed) || "0".equals(trimmed)) return 0;
        return null;
    }
    
    /**
     * 解析is_2fa_setup_success字段值
     * 支持的值：true→1, false→0, DELAYED→2（兼容旧的 Skipped→2）
     *
     * @param value 原始字符串值
     * @return 解析后的值（1=true, 0=false, 2=DELAYED），如果无法解析返回null
     */
    private Integer parseIs2faSetupSuccessValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        // 支持 true, True, TRUE, 1 → 返回 1
        if ("True".equalsIgnoreCase(trimmed) || "1".equals(trimmed)) {
            return 1;
        }
        // 支持 false, False, FALSE, 0 → 返回 0
        if ("False".equalsIgnoreCase(trimmed) || "0".equals(trimmed)) {
            return 0;
        }
        // 支持 DELAYED（新枚举）以及兼容旧的 Skipped → 返回 2
        if ("DELAYED".equalsIgnoreCase(trimmed) || "Skipped".equalsIgnoreCase(trimmed)) {
            return 2;
        }
        // 如果无法识别，返回null
        return null;
    }
    
    /**
     * 保存注册失败记录到数据库
     * 
     * @param context 注册上下文
     * @param registerType 注册类型：FAKE_EMAIL-假邮箱注册，REAL_EMAIL-真邮箱注册（Outlook）
     */
    private void saveFailureRecord(RegisterContext context, String registerType) {
        String phoneId = context.getPhoneId();
        try {
            TtAccountRegister failureRecord = new TtAccountRegister();
            
            // 基本信息
            failureRecord.setPhoneId(phoneId);
            failureRecord.setPhoneServerIp(context.getServerIp());
            failureRecord.setScriptStartTime(context.getScriptStartTime());
            failureRecord.setRegisterTime(LocalDateTime.now());
            failureRecord.setCreatedAt(LocalDateTime.now());
            failureRecord.setUpdatedAt(LocalDateTime.now());
            
            // 失败记录：email、password、username 等字段显式设置为 null
            // 不存储失败原因、退出码等信息
            failureRecord.setEmail(null);
            failureRecord.setPassword(null);
            failureRecord.setUsername(null);
            
            // 从上下文获取的字段（如果有值就设置，没有就为空）
            failureRecord.setGaid(context.getGaid());
            failureRecord.setAndroidVersion(getAndroidVersionFromSdk(context.getSdk()));
            
            // 设置IP渠道
            String ipChannel = context.getDynamicIpChannel();
            if (ipChannel == null || ipChannel.isEmpty()) {
                ipChannel = context.getStaticIpChannel();
            }
            failureRecord.setIpChannel(ipChannel);
            
            // 设置IP地址
            failureRecord.setIp(context.getRealIp());
            failureRecord.setTiktokVersion(context.getTiktokVersion());
            // 设置国家代码
            failureRecord.setCountry(context.getCountry());
            
            // 设置固定字段
            failureRecord.setNurtureJsonSource("ResetPhoneEnv");
            
            // 标记为注册失败
            failureRecord.setRegisterSuccess(false);
            
            // 设置注册类型
            failureRecord.setRegisterType(registerType);
            
            // 保存到数据库
            int insertResult = ttAccountRegisterRepository.insert(failureRecord);
            if (insertResult > 0) {
                log.info("{} - 注册失败记录已保存到数据库", phoneId);
            } else {
                log.warn("{} - 注册失败记录保存失败", phoneId);
            }
        } catch (Exception e) {
            log.error("{} - 保存注册失败记录时出错", phoneId, e);
        }
    }

    /**
     * 假邮箱脚本失败/超时场景下，尽量从输出中解析并落库 email/password。
     * 解析成功返回 true；否则返回 false 由调用方走普通失败记录。
     */
    private boolean trySaveFailedFakeEmailRecord(String output, RegisterContext context) {
        String phoneId = context.getPhoneId();
        try {
            if (output == null || output.trim().isEmpty()) {
                return false;
            }

            TtAccountRegister record = parseRegisterOutput(output, context);
            if (record == null) {
                return false;
            }

            record.setRegisterSuccess(false); // 明确标记失败
            record.setRegisterType("FAKE_EMAIL");
            record.setCreatedAt(LocalDateTime.now());
            record.setUpdatedAt(LocalDateTime.now());

            int insertResult = ttAccountRegisterRepository.insert(record);
            if (insertResult > 0) {
                log.info("{} - 失败场景已保存假邮箱账号信息: email={}, username={}",
                        phoneId, record.getEmail(), record.getUsername());
                return true;
            }

            log.warn("{} - 失败场景保存假邮箱账号信息失败", phoneId);
            return false;
        } catch (Exception e) {
            log.warn("{} - 失败场景解析/保存假邮箱账号信息异常: {}", phoneId, e.getMessage());
            return false;
        }
    }
    
    /**
     * 根据SDK版本获取Android版本名称
     * 
     * @param sdk SDK版本号（如"33"）
     * @return Android版本名称（如"13"）
     */
    private String getAndroidVersionFromSdk(String sdk) {
        if (sdk == null || sdk.isEmpty()) {
            return null;
        }
        
        try {
            int sdkInt = Integer.parseInt(sdk);
            // Android SDK版本到Android版本名称的映射
            // SDK 34 = Android 14
            // SDK 33 = Android 13
        
            switch (sdkInt) {
                case 34: return "14";
                case 33: return "13";
                
                default:
                    // 如果不在已知映射中，返回SDK版本本身
                    log.warn("未知的SDK版本: {}, 返回SDK值", sdk);
                    return sdk;
            }
        } catch (NumberFormatException e) {
            log.warn("SDK版本格式错误: {}", sdk);
            return sdk;
        }
    }

    /**
     * 负载均衡选择 Appium 服务器
     * 根据数据库中每台服务器的使用次数（PENDING + RUNNING 状态的任务数），选择使用最少的服务器
     * 
     * @return 选中的 Appium 服务器地址
     */
    private String selectAppiumServerWithLoadBalance() {
        // 可用的 Appium 服务器列表
        String[] appiumServers = {"10.13.55.85", "10.13.16.7", "10.13.58.129"};
        
        try {
            // 统计每台服务器当前的使用次数（PENDING + RUNNING 状态的任务数）
            Map<String, Integer> serverUsageCount = new HashMap<>();
            
            for (String server : appiumServers) {
                // 查询该服务器上 PENDING 和 RUNNING 状态的任务数
                LambdaQueryWrapper<TtRegisterTask> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(TtRegisterTask::getAppiumServer, server)
                       .in(TtRegisterTask::getStatus, Arrays.asList("PENDING", "RUNNING"));
                Long countLong = ttRegisterTaskRepository.selectCount(wrapper);
                int count = countLong != null ? countLong.intValue() : 0;
                serverUsageCount.put(server, count);
                log.debug("Appium 服务器 {} 当前使用次数: {}", server, count);
            }
            
            // 选择使用次数最少的服务器
            String selectedServer = appiumServers[0]; // 默认选择第一台
            int minCount = serverUsageCount.getOrDefault(selectedServer, 0);
            
            for (String server : appiumServers) {
                int count = serverUsageCount.getOrDefault(server, 0);
                if (count < minCount) {
                    minCount = count;
                    selectedServer = server;
                }
            }
            
            log.info("负载均衡选择 Appium 服务器: {} (使用次数: {})", selectedServer, minCount);
            return selectedServer;
            
        } catch (Exception e) {
            log.error("负载均衡选择 Appium 服务器失败，使用默认服务器", e);
            // 如果查询失败，使用第一台服务器作为默认值
            return appiumServers[0];
        }
    }
    
    /**
     * 统一SSH命令执行
     */
    private SshUtil.SshResult sshCommand(String targetHost, String command) {
        // 根据目标主机判断使用哪个用户
        // 10.13.55.85 / 10.13.16.7 / 10.13.58.129 是Appium脚本服务器，需要使用root用户
        // 其他云手机服务器使用ubuntu用户
        String username = "ubuntu"; // 默认使用ubuntu用户
        if (targetHost != null && (
                targetHost.equals("10.13.55.85") ||
                targetHost.equals("10.13.16.7") ||
                targetHost.equals("10.13.58.129"))) {
            username = "root"; // Appium脚本服务器使用root用户
        }
        
        return SshUtil.executeCommandWithPrivateKey(
            targetHost, 
            22, 
            username,
            sshProperties.getSshPrivateKey(),
            sshProperties.getSshPassphrase(),
            command, 
            1800000, // 30分钟超时，比脚本超时20分钟更长，确保脚本有足够时间完成
            sshProperties.getSshJumpHost(),
            sshProperties.getSshJumpPort(),
            sshProperties.getSshJumpUsername(),
            sshProperties.getSshJumpPassword()
        );
    }
    
    /**
     * 从phoneId中提取country（国家代码）
     * phoneId格式: tt_136_129_3_BR_20251020，其中BR是country
     * 
     * @param phoneId 云手机ID
     * @return 国家代码（如BR、US等），如果提取失败返回默认值US
     */
    private String extractCountryFromPhoneId(String phoneId) {
        try {
            String[] parts = phoneId.split("_");
            if (parts.length >= 5) {
                // 格式: tt_136_129_3_BR_20251020
                // parts[0] = "tt"
                // parts[1] = "136"
                // parts[2] = "129"
                // parts[3] = "3"
                // parts[4] = "BR" (country)
                // parts[5] = "20251020" (date/gaidTag)
                String country = parts[4];
                if (country != null && country.length() == 2) {
                    return country.toUpperCase();
                }
            }
        } catch (Exception e) {
            log.warn("从phoneId提取country失败: {}", phoneId, e);
        }
        // 默认返回US
        log.warn("无法从phoneId提取country，使用默认值US: {}", phoneId);
        return "US";
    }
    
    /**
     * 从phoneId中提取gaidTag
     * phoneId格式: tt_136_129_3_BR_20251020，其中20251020是gaidTag
     * 
     * @param phoneId 云手机ID
     * @return gaidTag（如20251020），如果提取失败返回当前日期
     */
    private String extractGaidTagFromPhoneId(String phoneId) {
        try {
            String[] parts = phoneId.split("_");
            if (parts.length >= 6) {
                // 格式: tt_136_129_3_BR_20251020
                // parts[5] = "20251020" (date/gaidTag)
                String gaidTag = parts[5];
                if (gaidTag != null && gaidTag.length() == 8) {
                    return gaidTag;
                }
            }
        } catch (Exception e) {
            log.warn("从phoneId提取gaidTag失败: {}", phoneId, e);
        }
        // 默认返回当前日期（格式：yyyyMMdd）
        String defaultGaidTag = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        log.warn("无法从phoneId提取gaidTag，使用默认值: {} -> {}", phoneId, defaultGaidTag);
        return defaultGaidTag;
    }
    
    /**
     * 查询任务列表（从数据库）
     */
    public Map<String, Object> getTaskList(String status, String taskType, String serverIp, String phoneId, int page, int size) {
        try {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TtRegisterTask> wrapper = 
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            
            if (status != null && !status.isEmpty()) {
                wrapper.eq(TtRegisterTask::getStatus, status);
            }
            if (taskType != null && !taskType.isEmpty()) {
                wrapper.eq(TtRegisterTask::getTaskType, taskType);
            }
            if (serverIp != null && !serverIp.isEmpty()) {
                wrapper.eq(TtRegisterTask::getServerIp, serverIp);
            }
            if (phoneId != null && !phoneId.isEmpty()) {
                wrapper.eq(TtRegisterTask::getPhoneId, phoneId);
            }
            
            wrapper.orderByDesc(TtRegisterTask::getCreatedAt);
            
            // 分页查询
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<TtRegisterTask> pageObj = 
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size);
            com.baomidou.mybatisplus.core.metadata.IPage<TtRegisterTask> pageResult = 
                ttRegisterTaskRepository.selectPage(pageObj, wrapper);
            
            List<Map<String, Object>> taskList = new ArrayList<>();
            for (TtRegisterTask task : pageResult.getRecords()) {
                Map<String, Object> taskMap = new HashMap<>();
                taskMap.put("id", task.getId());
                taskMap.put("taskId", task.getTaskId());
                taskMap.put("taskType", task.getTaskType());
                taskMap.put("serverIp", task.getServerIp());
                taskMap.put("phoneId", task.getPhoneId());
                taskMap.put("targetCount", task.getTargetCount());
                taskMap.put("tiktokVersionDir", task.getTiktokVersionDir());
                taskMap.put("country", task.getCountry());
                taskMap.put("sdk", task.getSdk());
                taskMap.put("imagePath", task.getImagePath());
                taskMap.put("gaidTag", task.getGaidTag());
                taskMap.put("dynamicIpChannel", task.getDynamicIpChannel());
                taskMap.put("staticIpChannel", task.getStaticIpChannel());
                taskMap.put("biz", task.getBiz());
                taskMap.put("status", task.getStatus());
                taskMap.put("createdAt", task.getCreatedAt() != null ? task.getCreatedAt().toString() : null);
                taskMap.put("updatedAt", task.getUpdatedAt() != null ? task.getUpdatedAt().toString() : null);
                taskList.add(taskMap);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            
            // 构建分页数据对象
            Map<String, Object> data = new HashMap<>();
            data.put("list", taskList);
            data.put("total", pageResult.getTotal());
            data.put("page", page);
            data.put("size", size);
            data.put("totalPages", pageResult.getPages());
            data.put("totalElements", pageResult.getTotal());
            
            result.put("data", data);
            return result;
        } catch (Exception e) {
            log.error("查询任务列表异常", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "查询任务列表失败: " + e.getMessage());
            return result;
        }
    }
    
    /**
     * 根据任务ID查询任务详情
     */
    public Map<String, Object> getTaskById(String taskId) {
        try {
            TtRegisterTask task = ttRegisterTaskRepository.findByTaskId(taskId);
            if (task == null) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "任务不存在");
                return result;
            }
            
            Map<String, Object> taskMap = new HashMap<>();
            taskMap.put("id", task.getId());
            taskMap.put("taskId", task.getTaskId());
            taskMap.put("taskType", task.getTaskType());
            taskMap.put("serverIp", task.getServerIp());
            taskMap.put("phoneId", task.getPhoneId());
            taskMap.put("targetCount", task.getTargetCount());
            taskMap.put("tiktokVersionDir", task.getTiktokVersionDir());
            taskMap.put("country", task.getCountry());
            taskMap.put("sdk", task.getSdk());
            taskMap.put("imagePath", task.getImagePath());
            taskMap.put("gaidTag", task.getGaidTag());
            taskMap.put("dynamicIpChannel", task.getDynamicIpChannel());
            taskMap.put("staticIpChannel", task.getStaticIpChannel());
            taskMap.put("biz", task.getBiz());
            taskMap.put("status", task.getStatus());
            taskMap.put("createdAt", task.getCreatedAt() != null ? task.getCreatedAt().toString() : null);
            taskMap.put("updatedAt", task.getUpdatedAt() != null ? task.getUpdatedAt().toString() : null);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", taskMap);
            return result;
        } catch (Exception e) {
            log.error("查询任务详情异常，taskId: {}", taskId, e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "查询任务详情失败: " + e.getMessage());
            return result;
        }
    }
    
    /**
     * 停止任务（数据库任务）
     */
    public Map<String, Object> stopTaskById(String taskId) {
        try {
            TtRegisterTask task = ttRegisterTaskRepository.findByTaskId(taskId);
            if (task == null) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "任务不存在");
                return result;
            }
            
            if (!"RUNNING".equals(task.getStatus()) && !"PENDING".equals(task.getStatus())) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "任务当前状态为 " + task.getStatus() + "，无法停止");
                return result;
            }
            
            // 先添加到内存停止集合（立即生效）
            stoppedTaskIds.add(taskId);
            
            // 更新数据库状态
            task.setStatus("STOPPED");
            task.setUpdatedAt(LocalDateTime.now());
            ttRegisterTaskRepository.updateById(task);
            
            log.info("任务 {} 已停止（已添加到停止集合）", taskId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "任务已停止");
            result.put("taskId", taskId);
            result.put("status", "STOPPED");
            return result;
        } catch (Exception e) {
            log.error("停止任务异常，taskId: {}", taskId, e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "停止任务失败: " + e.getMessage());
            return result;
        }
    }
    
    /**
     * 批量停止任务
     */
    public Map<String, Object> stopTasksBatch(List<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "taskIds参数不能为空");
            return result;
        }
        
        int successCount = 0;
        int failCount = 0;
        List<String> successTaskIds = new ArrayList<>();
        List<String> failTaskIds = new ArrayList<>();
        
        for (String taskId : taskIds) {
            Map<String, Object> stopResult = stopTaskById(taskId);
            if ((Boolean) stopResult.get("success")) {
                successCount++;
                successTaskIds.add(taskId);
            } else {
                failCount++;
                failTaskIds.add(taskId);
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", String.format("成功停止 %d 个任务，失败 %d 个任务", successCount, failCount));
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("successTaskIds", successTaskIds);
        result.put("failTaskIds", failTaskIds);
        return result;
    }
    
    /**
     * 删除任务
     */
    public Map<String, Object> deleteTask(String taskId) {
        try {
            TtRegisterTask task = ttRegisterTaskRepository.findByTaskId(taskId);
            if (task == null) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "任务不存在");
                return result;
            }
            
            if ("RUNNING".equals(task.getStatus())) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "任务正在运行中，无法删除，请先停止任务");
                return result;
            }
            
            ttRegisterTaskRepository.deleteById(task.getId());
            
            log.info("任务 {} 已删除", taskId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "任务已删除");
            result.put("taskId", taskId);
            return result;
        } catch (Exception e) {
            log.error("删除任务异常，taskId: {}", taskId, e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "删除任务失败: " + e.getMessage());
            return result;
        }
    }
    
    /**
     * 批量删除任务
     */
    public Map<String, Object> deleteTasksBatch(List<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "taskIds参数不能为空");
            return result;
        }
        
        int successCount = 0;
        int failCount = 0;
        List<String> successTaskIds = new ArrayList<>();
        List<String> failTaskIds = new ArrayList<>();
        
        for (String taskId : taskIds) {
            Map<String, Object> deleteResult = deleteTask(taskId);
            if ((Boolean) deleteResult.get("success")) {
                successCount++;
                successTaskIds.add(taskId);
            } else {
                failCount++;
                failTaskIds.add(taskId);
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", String.format("成功删除 %d 个任务，失败 %d 个任务", successCount, failCount));
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("successTaskIds", successTaskIds);
        result.put("failTaskIds", failTaskIds);
        return result;
    }
    
    /**
     * 重置任务状态为 PENDING（用于重新执行失败的任务）
     */
    public Map<String, Object> resetTask(String taskId) {
        try {
            TtRegisterTask task = ttRegisterTaskRepository.findByTaskId(taskId);
            if (task == null) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "任务不存在");
                return result;
            }
            
            if ("RUNNING".equals(task.getStatus())) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "任务正在运行中，无法重置");
                return result;
            }
            
            task.setStatus("PENDING");
            task.setUpdatedAt(LocalDateTime.now());
            ttRegisterTaskRepository.updateById(task);
            
            // 清除停止标志（如果存在）
            stoppedTaskIds.remove(taskId);
            
            log.info("任务 {} 状态已重置为 PENDING，已清除停止标志", taskId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "任务状态已重置为 PENDING，将在应用重启或下次检测时自动执行");
            result.put("taskId", taskId);
            result.put("status", "PENDING");
            return result;
        } catch (Exception e) {
            log.error("重置任务状态异常，taskId: {}", taskId, e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "重置任务状态失败: " + e.getMessage());
            return result;
        }
    }

    /**
     * 在宿主机上可选执行 /vdc/myq/cpi/change_after_reset.sh 脚本（如果存在）
     * 参数：phone_id
     */
    private void runChangeAfterResetIfExists(String phoneId, String serverIp) {
        try {
            String scriptPath = "/vdc/myq/cpi/change_after_reset.sh";
            // 先检查脚本是否存在且可执行
            String checkCmd = String.format("[ -x %s ] && echo 'exists' || echo 'not_exists'", scriptPath);
            SshUtil.SshResult checkResult = sshCommand(serverIp, checkCmd);
            if (!checkResult.isSuccess()) {
                log.warn("{} - 检查 change_after_reset.sh 是否存在时出错: {}", phoneId, checkResult.getErrorMessage());
                return;
            }
            String checkOutput = checkResult.getOutput() != null ? checkResult.getOutput().trim() : "";
            if (!"exists".equals(checkOutput)) {
                log.info("{} - 宿主机 {} 上未找到可执行的 change_after_reset.sh，跳过执行", phoneId, serverIp);
                return;
            }

            // 执行脚本：cd /vdc/myq/cpi && ./change_after_reset.sh phone_id
            // 使用脚本自身的shebang与权限，行为与人为在终端执行一致
            String execCmd = String.format("cd /vdc/myq/cpi && ./change_after_reset.sh %s", phoneId);
            log.info("{} - 执行 change_after_reset.sh 脚本（与手动 ./change_after_reset.sh 一致）: {}", phoneId, execCmd);
            SshUtil.SshResult execResult = sshCommand(serverIp, execCmd);
            if (execResult.isSuccess()) {
                log.info("{} - change_after_reset.sh 执行成功，输出: {}", phoneId, execResult.getOutput());
            } else {
                log.warn("{} - change_after_reset.sh 执行失败: {}, 输出: {}", 
                        phoneId, execResult.getErrorMessage(), execResult.getOutput());
            }
        } catch (Exception e) {
            log.error("{} - 执行 change_after_reset.sh 脚本异常", phoneId, e);
        }
    }

    /**
     * 针对 MX 国家，在宿主机上调整指定设备的 xray 动态代理配置为 gate1.ipweb.cc:7778，并重启 xray-${phoneId}
     * - 配置文件路径：/home/ubuntu/xray/xray-conf/${phoneId}.json
     * - 只更新 outbounds 中 tag=dynamic 的第一个 server
     * - user 使用 B_59206_MX___60_<8位随机字符串>
     */
    private void adjustXrayDynamicForMx(String phoneId, String serverIp) {
        try {
            String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
            StringBuilder sb = new StringBuilder();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < 8; i++) {
                sb.append(chars.charAt(random.nextInt(chars.length())));
            }
            String randSuffix = sb.toString();
            String user = "B_59206_MX___60_" + randSuffix;

            String confPath = "/home/ubuntu/xray/xray-conf/" + phoneId + ".json";
            String cmd = String.format(
                "conf='%s'; tmp=\"${conf}.tmp\"; " +
                "if [ -f \"$conf\" ]; then " +
                "  jq --arg addr 'gate1.ipweb.cc' --argjson port 7778 --arg user '%s' --arg pass 'Kim4567' " +
                "    '(.outbounds[] | select(.tag==\"dynamic\") | .settings.servers[0].address) = $addr " +
                "   | (.outbounds[] | select(.tag==\"dynamic\") | .settings.servers[0].port) = $port " +
                "   | (.outbounds[] | select(.tag==\"dynamic\") | .settings.servers[0].users[0].user) = $user " +
                "   | (.outbounds[] | select(.tag==\"dynamic\") | .settings.servers[0].users[0].pass) = $pass' " +
                "    \"$conf\" > \"$tmp\" && mv \"$tmp\" \"$conf\" && sudo systemctl restart xray-%s; " +
                "else echo 'xray conf not found for %s'; fi",
                confPath, user, phoneId, phoneId
            );

            log.info("{} - 调整 MX 设备 xray 动态代理配置: {}", phoneId, cmd);
            SshUtil.SshResult result = sshCommand(serverIp, cmd);
            if (result.isSuccess()) {
                log.info("{} - 调整 xray 动态代理并重启服务成功，输出: {}", phoneId, result.getOutput());
            } else {
                log.warn("{} - 调整 xray 动态代理或重启服务失败: {}, 输出: {}",
                        phoneId, result.getErrorMessage(), result.getOutput());
            }
        } catch (Exception e) {
            log.error("{} - 调整 MX 设备 xray 动态代理配置异常", phoneId, e);
        }
    }
}
