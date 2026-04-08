package com.cpa.service;

import com.cpa.config.SshProperties;
import com.cpa.entity.TtAccountRegister;
import com.cpa.entity.TtFollowDetailsNew;
import com.cpa.entity.TtRegisterTask;
import com.cpa.entity.TtRetentionRecord;
import com.cpa.repository.TtAccountRegisterRepository;
import com.cpa.repository.TtFollowDetailsNewRepository;
import com.cpa.repository.TtRegisterTaskRepository;
import com.cpa.repository.TtRetentionRecordRepository;
import com.cpa.util.SshUtil;
import com.cpa.util.SshConnectionPool;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.CacheLoader;
import com.google.common.util.concurrent.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

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
    private final TtFollowDetailsNewRepository ttFollowDetailsNewRepository;
    private final TtRegisterTaskRepository ttRegisterTaskRepository;
    private final TtRetentionRecordRepository ttRetentionRecordRepository;
    private final TaskSchedulerService taskSchedulerService;
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

    /**
     * 任务执行信息缓存（taskId -> TaskInfo）。
     * 使用 Guava Cache 替代原始 ConcurrentHashMap：
     *  - maximumSize(5000)：防止历史任务无限堆积
     *  - expireAfterWrite(24h)：任务完成后次日自动清理，查询接口仍可读到当天结果
     * 注意：get 用 getIfPresent（不自动加载），values 用 asMap().values()
     */
    private final com.google.common.cache.Cache<String, TaskInfo> taskInfoMap =
            CacheBuilder.newBuilder()
                    .maximumSize(5000)
                    .expireAfterWrite(24, TimeUnit.HOURS)
                    .build();

    /**
     * 任务配置缓存（taskId -> TtRegisterTask），TTL 5分钟自动回源 DB。
     * 直接改库后最多 5 分钟生效；调用 updateTaskConfig 或 refreshTaskConfig 可立即失效。
     */
    private final LoadingCache<String, Optional<TtRegisterTask>> taskConfigCache =
            CacheBuilder.newBuilder()
                    .expireAfterWrite(5, TimeUnit.MINUTES)
                    .build(new CacheLoader<String, Optional<TtRegisterTask>>() {
                        @Override
                        public Optional<TtRegisterTask> load(String taskId) {
                            return Optional.ofNullable(ttRegisterTaskRepository.findByTaskId(taskId));
                        }
                    });
    
    // 多线程池配置：分离不同类型的任务
    // 1. 并行注册线程池（用于多设备并行注册）
    private static final AtomicInteger threadCounter = new AtomicInteger(0);
    private final ExecutorService parallelRegisterExecutor = Executors.newFixedThreadPool(1000, 
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
    
    /**
     * 定时任务每批拉起任务时，相邻两路启动间隔（毫秒），避免同一秒同时进入注册/ResetPhoneEnv。
     * 第 1 路 0ms，第 2 路 350ms，…，第 10 路 3150ms。
     */
    private static final int SCHEDULED_PENDING_STAGGER_STEP_MS = 2000;

    // 4. ResetPhoneEnv接口调用并发控制（按服务器分组，不同服务器可以并行）
    // 每个服务器同时调用ResetPhoneEnv的最大并发数：ResetPhoneEnv耗时3~5分钟，15台机器，5并发是安全上限
    @Value("${tt-register.reset-concurrency-per-server:5}")
    private int maxResetPhoneEnvConcurrencyPerServer;
    // 每个服务器每分钟最多发起的ResetPhoneEnv次数（令牌桶速率），防止Docker被突发操作压垮
    // 默认4：对应每台服务器平均每15秒一个Reset，与耗时3~5分钟+5并发上限匹配
    @Value("${tt-register.reset-rate-per-minute-per-server:4.0}")
    private double resetRatePerMinutePerServer;
    // 为每个服务器创建独立的信号量（并发上限），不同服务器的调用可以并行
    private final ConcurrentHashMap<String, Semaphore> resetPhoneEnvSemaphores = new ConcurrentHashMap<>();
    // 为每个服务器创建独立的令牌桶（速率控制），均匀分散Reset请求，避免突发冲击Docker
    private final ConcurrentHashMap<String, RateLimiter> resetPhoneEnvRateLimiters = new ConcurrentHashMap<>();
    
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
    
    /**
     * 已停止的任务ID集合（内存中快速检查，避免数据库查询延迟）。
     * 使用 Guava Cache 替代原始 Set：
     *  - TTL 2小时：任务停止后即使忘记 remove，也不会无限积累
     *  - value 固定为 Boolean.TRUE，仅用 key 做存在性判断
     *  - 操作统一走 markTaskStopped/isTaskStopped/clearTaskStopped，避免散落的 add/remove/contains
     */
    private static final com.google.common.cache.Cache<String, Boolean> stoppedTaskCache =
            CacheBuilder.newBuilder()
                    .expireAfterWrite(2, TimeUnit.HOURS)
                    .build();

    private static void markTaskStopped(String taskId) {
        stoppedTaskCache.put(taskId, Boolean.TRUE);
    }

    private static boolean isTaskStopped(String taskId) {
        return stoppedTaskCache.getIfPresent(taskId) != null;
    }

    private static void clearTaskStopped(String taskId) {
        stoppedTaskCache.invalidate(taskId);
    }

    /**
     * 设备执行锁（按设备ID，防止同一设备被多个任务同时操作）。
     * 保持 ConcurrentHashMap 而非 TTL Cache：若 TTL 到期时 Semaphore 仍被持有，
     * 新线程会拿到新对象，旧线程 release 的是旧对象，锁即失效。
     * 清理工作由 scheduledCleanIdleDeviceLocks() 定时完成。
     */
    private static final ConcurrentHashMap<String, Semaphore> deviceLocks = new ConcurrentHashMap<>();
    /** 记录每把锁最后一次 acquire/release 的时间戳（ms），用于判断是否空闲超时 */
    private static final ConcurrentHashMap<String, Long> deviceLockLastUsed = new ConcurrentHashMap<>();

    /**
     * 定时清理空闲设备锁：每 30 分钟扫一次，移除已空闲（permits==1）且 30 分钟未使用的条目，
     * 防止设备下线后 Semaphore 永久占用内存。
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000)
    public void scheduledCleanIdleDeviceLocks() {
        long now = System.currentTimeMillis();
        long idleThresholdMs = 30 * 60 * 1000L; // 30 分钟
        int removed = 0;
        for (Map.Entry<String, Semaphore> entry : deviceLocks.entrySet()) {
            String phoneId = entry.getKey();
            Semaphore sem = entry.getValue();
            Long lastUsed = deviceLockLastUsed.get(phoneId);
            boolean idle = sem.availablePermits() == 1
                    && (lastUsed == null || now - lastUsed > idleThresholdMs);
            if (idle) {
                // 再次确认：tryAcquire 成功说明确实空闲，立即 release 并移除
                if (sem.tryAcquire()) {
                    sem.release();
                    deviceLocks.remove(phoneId, sem); // 用 remove(key, value) 避免移除别的线程刚放入的新对象
                    deviceLockLastUsed.remove(phoneId);
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("清理空闲设备锁完成，移除 {} 个", removed);
        }
    }

    /**
     * 本机留存去重缓存：同一账号在 24 小时内只会被本 JVM 处理一次留存
     * key: account_register_id, value: 首次处理时间
     */
    private final Cache<Long, Instant> retentionProcessingCache =
            CacheBuilder.newBuilder()
                    .expireAfterWrite(24, TimeUnit.HOURS)
                    .build();
    /** IP归属地缓存，避免重复请求外部接口 */
    private final Map<String, Map<String, String>> ipGeoCache = new ConcurrentHashMap<>();
    /** 管理页总数缓存（方案A：列表先返回，总数异步计算） */
    private final Map<String, Long> accountManageCountCache = new ConcurrentHashMap<>();
    private final Set<String> accountManageCountComputing = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> windowManageCountCache = new ConcurrentHashMap<>();
    private final Set<String> windowManageCountComputing = ConcurrentHashMap.newKeySet();

    /**
     * 从缓存获取最新任务配置并构建 resetParams。
     * Cache TTL 5分钟自动回源；updateTaskConfig/refreshTaskConfig 会主动 invalidate 缓存，可立即生效。
     * 若缓存获取失败，回退到传入的 fallbackParams。
     */
    private Map<String, String> getLatestResetParams(String taskId, Map<String, String> fallbackParams) {
        try {
            Optional<TtRegisterTask> opt = taskConfigCache.get(taskId);
            if (!opt.isPresent()) {
                return fallbackParams;
            }
            TtRegisterTask latest = opt.get();
            Map<String, String> params = new HashMap<>();
            if (latest.getCountry() != null) params.put("country", latest.getCountry());
            if (latest.getSdk() != null) params.put("sdk", latest.getSdk());
            if (latest.getImagePath() != null) params.put("imagePath", latest.getImagePath());
            if (latest.getGaidTag() != null) params.put("gaidTag", latest.getGaidTag());
            if (latest.getDynamicIpChannel() != null) params.put("dynamicIpChannel", latest.getDynamicIpChannel());
            if (latest.getStaticIpChannel() != null) params.put("staticIpChannel", latest.getStaticIpChannel());
            if (latest.getBiz() != null) params.put("biz", latest.getBiz());
            if (latest.getAppiumServer() != null) params.put("appiumServer", latest.getAppiumServer());
            return params;
        } catch (Exception e) {
            log.warn("任务 {} 读取配置缓存失败，使用原始参数: {}", taskId, e.getMessage());
            return fallbackParams;
        }
    }

    /**
     * 手动刷新任务配置缓存，使下一次循环立即从 DB 读取最新配置。
     */
    public void refreshTaskConfig(String taskId) {
        taskConfigCache.invalidate(taskId);
        log.info("任务 {} 配置缓存已手动刷新", taskId);
    }

    /**
     * 若数据库中任务已被置为 STOPPED（如手动改库），则同步到内存并返回 true，调用方应停止执行。
     * 返回 true 时已设置 task.setStatus("STOPPED") 并加入 stoppedTaskIds，调用方应 updateById(task) 后退出。
     */
    private boolean syncStoppedFromDb(TtRegisterTask task) {
        TtRegisterTask db = ttRegisterTaskRepository.selectById(task.getId());
        if (db != null && "STOPPED".equals(db.getStatus())) {
            task.setStatus("STOPPED");
            task.setUpdatedAt(LocalDateTime.now());
            markTaskStopped(task.getTaskId());
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
     * 获取指定服务器的ResetPhoneEnv调用信号量（并发上限）
     * @param serverIp 服务器IP
     * @return 信号量
     */
    private Semaphore getResetPhoneEnvSemaphore(String serverIp) {
        return resetPhoneEnvSemaphores.computeIfAbsent(serverIp,
            k -> new Semaphore(maxResetPhoneEnvConcurrencyPerServer, true));
    }

    /**
     * 获取指定服务器的ResetPhoneEnv令牌桶（速率控制）
     * 每分钟最多发出 resetRatePerMinutePerServer 个令牌，均匀分散Reset请求，避免Docker被突发冲击。
     * @param serverIp 服务器IP
     * @return RateLimiter
     */
    private RateLimiter getResetPhoneEnvRateLimiter(String serverIp) {
        return resetPhoneEnvRateLimiters.computeIfAbsent(serverIp,
            k -> RateLimiter.create(resetRatePerMinutePerServer / 60.0));
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
            
            // 使用默认并发数 30
            int maxConcurrency = 30;
            Semaphore semaphore = new Semaphore(maxConcurrency);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            AtomicInteger pendingBatchSlot = new AtomicInteger(0);
            
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
                if (isTaskStopped(task.getTaskId())) {
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
                deviceLockLastUsed.put(phoneId, System.currentTimeMillis());

                // 尝试获取信号量
                if (!semaphore.tryAcquire()) {
                    log.debug("并发数已达上限，等待执行任务: taskId={}", task.getTaskId());
                    deviceLock.release(); // 释放设备锁
                    deviceLockLastUsed.put(phoneId, System.currentTimeMillis());
                    continue;
                }
                
                // 更新任务状态为 RUNNING
                task.setStatus("RUNNING");
                task.setUpdatedAt(LocalDateTime.now());
                ttRegisterTaskRepository.updateById(task);
                
                final TtRegisterTask finalTask = task;
                final Semaphore finalDeviceLock = deviceLock; // 保存设备锁引用
                final String finalPhoneId = phoneId;          // 用于 release 时更新时间戳
                final int staggerSlot = pendingBatchSlot.getAndIncrement();
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        int delayMs = staggerSlot * SCHEDULED_PENDING_STAGGER_STEP_MS;
                        if (delayMs > 0) {
                            try {
                                Thread.sleep(delayMs);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                log.warn("定时任务错峰等待被中断: taskId={}", finalTask.getTaskId());
                                return;
                            }
                        }
                        log.info("开始执行任务: taskId={}, taskType={}, phoneId={}, targetCount={}, staggerSlot={}, staggerDelayMs={}", 
                                finalTask.getTaskId(), finalTask.getTaskType(), 
                                finalTask.getPhoneId(), finalTask.getTargetCount(), staggerSlot, delayMs);
                        
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
                        deviceLockLastUsed.put(finalPhoneId, System.currentTimeMillis());
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
            int maxConcurrency = 30;
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
                if (isTaskStopped(task.getTaskId())) {
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
                deviceLockLastUsed.put(phoneId, System.currentTimeMillis());

                // 尝试获取信号量
                if (!semaphore.tryAcquire()) {
                    log.debug("并发数已达上限，等待执行主板机任务: taskId={}", task.getTaskId());
                    deviceLock.release(); // 释放设备锁
                    deviceLockLastUsed.put(phoneId, System.currentTimeMillis());
                    continue;
                }

                // 更新任务状态为 RUNNING
                task.setStatus("RUNNING");
                task.setUpdatedAt(LocalDateTime.now());
                ttRegisterTaskRepository.updateById(task);

                final TtRegisterTask finalTask = task;
                final Semaphore finalDeviceLock = deviceLock;
                final String finalPhoneId = phoneId;
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
                        deviceLockLastUsed.put(finalPhoneId, System.currentTimeMillis());
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
                if (isTaskStopped(task.getTaskId())) {
                    currentTask.setStatus("STOPPED");
                    currentTask.setUpdatedAt(LocalDateTime.now());
                    ttRegisterTaskRepository.updateById(currentTask);
                    continue;
                }
                String phoneId = task.getPhoneId();
                Semaphore deviceLock = deviceLocks.computeIfAbsent(phoneId, k -> new Semaphore(1, true));
                if (!deviceLock.tryAcquire()) continue;
                deviceLockLastUsed.put(phoneId, System.currentTimeMillis());
                if (!semaphore.tryAcquire()) {
                    deviceLock.release();
                    deviceLockLastUsed.put(phoneId, System.currentTimeMillis());
                    continue;
                }
                task.setStatus("RUNNING");
                task.setUpdatedAt(LocalDateTime.now());
                ttRegisterTaskRepository.updateById(task);
                final TtRegisterTask finalTask = task;
                final Semaphore finalDeviceLock = deviceLock;
                final String finalPhoneId = phoneId;
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
                        deviceLockLastUsed.put(finalPhoneId, System.currentTimeMillis());
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
     * 应用启动时恢复孤儿任务：将所有 RUNNING 重置为 PENDING。
     * 服务重启意味着所有执行线程已消失，RUNNING 任务均为孤儿，需重新调度。
     * 只更新 status/updated_at，不覆盖 country/dynamicIpChannel 等配置字段。
     */
    @PostConstruct
    public void recoverTasks() {
        try {
            int count = ttRegisterTaskRepository.resetRunningToPending();
            if (count > 0) {
                log.warn("服务启动恢复：发现 {} 个孤儿任务（RUNNING），已重置为 PENDING，等待下次定时任务扫描执行", count);
            } else {
                log.info("服务启动检查：无孤儿任务");
            }
        } catch (Exception e) {
            log.error("恢复孤儿任务时出错", e);
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
                    if (isTaskStopped(task.getTaskId())) {
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
                        markTaskStopped(task.getTaskId());
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

                    // 注册完成后，再次检查是否被停止
                    if (isTaskStopped(task.getTaskId())) {
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
                    if (isTaskStopped(task.getTaskId())) {
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
                        markTaskStopped(task.getTaskId());
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
                    if (isTaskStopped(task.getTaskId())) {
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
                        markTaskStopped(task.getTaskId()); // 同步到内存集合
                        task.setStatus("STOPPED");
                        task.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(task);
                        break;
                    }

                    // 每轮从缓存读取最新配置，支持热更新（TTL 5分钟，updateTaskConfig/refreshTaskConfig 可立即失效）
                    Map<String, String> currentResetParams = getLatestResetParams(task.getTaskId(), resetParams);

                    log.info("任务 {} - 设备 {} 第 {} 轮注册", task.getTaskId(), phoneId, round);

                    // 调用注册流程（包含 ResetPhoneEnv + 安装APK + 执行注册脚本）
                    String result;
                    try {
                        result = registerSingleDeviceWithoutStart(phoneId, serverIp, round, 0, tiktokVersionDir, currentResetParams, emailMode);
                    } catch (Exception e) {
                        log.error("任务 {} - 设备 {} 第 {} 轮注册时发生未捕获异常", task.getTaskId(), phoneId, round, e);
                        result = "FAILED: 注册流程异常 - " + e.getMessage();
                    }

                    if (result != null && result.startsWith("SUCCESS")) {
                        log.info("任务 {} - 设备 {} 第 {} 轮注册成功", task.getTaskId(), phoneId, round);
                    } else {
                        log.warn("任务 {} - 设备 {} 第 {} 轮注册失败: {}", task.getTaskId(), phoneId, round, result);
                    }

                    // 注册完成后检查是否被停止（避免继续下一轮）
                    if (isTaskStopped(task.getTaskId())) {
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
                    if (isTaskStopped(task.getTaskId())) {
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
                        markTaskStopped(task.getTaskId()); // 同步到内存集合
                        task.setStatus("STOPPED");
                        task.setUpdatedAt(LocalDateTime.now());
                        ttRegisterTaskRepository.updateById(task);
                        break;
                    }

                    // 每轮从缓存读取最新配置，支持热更新（TTL 5分钟，updateTaskConfig/refreshTaskConfig 可立即失效）
                    Map<String, String> currentResetParams = getLatestResetParams(task.getTaskId(), resetParams);

                    log.info("任务 {} - 设备 {} 注册进度: {}/{}", task.getTaskId(), phoneId, i, targetCount);

                    // 调用注册流程（包含 ResetPhoneEnv + 安装APK + 执行注册脚本）
                    String result;
                    try {
                        result = registerSingleDeviceWithoutStart(phoneId, serverIp, i, targetCount, tiktokVersionDir, currentResetParams, emailMode);
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

                    Thread.sleep(5000); // 每个账号之间休息5秒
                }

                // 完成前检查是否被手动改为 STOPPED，避免覆盖写成 COMPLETED
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
            if (isTaskStopped(task.getTaskId())) {
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
                if (isTaskStopped(task.getTaskId())) {
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
        TaskInfo taskInfo = taskInfoMap.getIfPresent(taskId);
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
        TaskInfo taskInfo = taskInfoMap.getIfPresent(taskId);
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
        TaskInfo taskInfo = taskInfoMap.getIfPresent(taskId);
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
        TaskInfo taskInfo = taskInfoMap.getIfPresent(taskId);

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
        TaskInfo taskInfo = taskInfoMap.getIfPresent(taskId);
        
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
        TaskInfo taskInfo = taskInfoMap.getIfPresent(taskId);
        
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
    public Map<String, Object> getAllTasks(int page, int size, String taskId, String status, String serverIp) {
        List<Map<String, Object>> taskList = new ArrayList<>();

        if (page < 1) {
            page = 1;
        }
        if (size <= 0) {
            size = 20;
        }

        int offset = (page - 1) * size;

        // 1) 显式分页：防止 MyBatis-Plus pagination 插件未生效时返回全量
        long total = ttRegisterTaskRepository.countTasks(taskId, status, serverIp);
        List<TtRegisterTask> pageRecords = ttRegisterTaskRepository.listTasksPaged(taskId, status, serverIp, offset, size);

        for (TtRegisterTask task : pageRecords) {
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
        for (TaskInfo taskInfo : taskInfoMap.asMap().values()) {
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
            for (int i = 0; i < taskList.size(); i++) {
                if (override.get("taskId").equals(taskList.get(i).get("taskId"))) {
                    taskList.set(i, override);
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
        result.put("total", total);
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

        // 立即失效缓存，使正在运行的任务下一循环即可读到新配置
        taskConfigCache.invalidate(taskId);

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
        // RUNNING 中的任务不允许直接恢复，防止并发执行
        if ("RUNNING".equals(task.getStatus())) {
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("message", "任务正在运行中，无法恢复为待执行");
            return res;
        }

        task.setStatus("PENDING");
        task.setUpdatedAt(LocalDateTime.now());
        ttRegisterTaskRepository.updateById(task);

        // 清理停止集合标记，避免再次被调度线程识别为“在停止集合中，跳过执行”
        clearTaskStopped(taskId);
        log.info("任务 {} 已恢复为 PENDING，已从停止集合中移除", taskId);

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
    private void maybeRequestCreateFarmOnGaidError(String detail) {
        if (detail == null) {
            return;
        }
        try {
            taskSchedulerService.requestCreateFarmTaskWhenGaidPoolLikelyEmpty(detail);
        } catch (Exception e) {
            log.warn("按需 CreateFarmTask 触发异常: {}", e.getMessage());
        }
    }

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
            
            // 任务表有配置则优先使用任务表配置；为空时回退到后端固定默认值
            String dynamicIpChannel = resetParams.getOrDefault("dynamicIpChannel", "");
            String staticIpChannel = resetParams.getOrDefault("staticIpChannel", "");
            if (dynamicIpChannel == null || dynamicIpChannel.trim().isEmpty()) {
                dynamicIpChannel = "netnut_biu";
            } else {
                dynamicIpChannel = dynamicIpChannel.trim();
            }
            if (staticIpChannel == null || staticIpChannel.trim().isEmpty()) {
                staticIpChannel = "ipidea";
            } else {
                staticIpChannel = staticIpChannel.trim();
            }
            log.info("{} {} - 使用IP渠道: dynamicIpChannel={}, staticIpChannel={}",
                    logPrefix, phoneId, dynamicIpChannel, staticIpChannel);
            
            String biz = resetParams.getOrDefault("biz", "");
            
            // 2. 调用ResetPhoneEnv接口（合并reset和换机功能），带重试逻辑
            log.info("{} {} - 步骤1: 调用ResetPhoneEnv接口（reset+换机）", logPrefix, phoneId);
            log.info("{} {} - ResetPhoneEnv参数: country={}, sdk={}, imagePath={}, gaidTag={}, dynamicIpChannel={}, staticIpChannel={}, biz={}", 
                    logPrefix, phoneId, country, sdk, imagePath, gaidTag, dynamicIpChannel, staticIpChannel, biz);
            
            Map<String, Object> resetResult = null;
            String realIp = null;
            String gaid = null;
            String state = null;
            String city = null;
            String model = null;
            String buildId = null;
            String userAgent = null;
            String brand = null;
            int maxRetries = 1;  // 统一降为3次重试，减少连接数放大
            int retryCount = 0;
            boolean resetSuccess = false;

            // DynamicIPError 渠道降级列表（按优先级）：失败时依次切换，本轮内不写 DB
            final List<String> ipChannelFallbacks = new java.util.ArrayList<>(java.util.Arrays.asList("lajiao", "netnut_biu", "kookeey"));
            ipChannelFallbacks.remove(dynamicIpChannel); // 当前渠道已在使用，从候补中移除
            int ipChannelFallbackIndex = 0;

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
                    
                    // 先获取令牌桶许可（速率控制），均匀分散对同一服务器的Reset请求
                    // 用 tryAcquire(500ms) 轮询替代 acquire()，每次等待前检查任务是否已停止
                    // RateLimiter.acquire() 不响应 interrupt，轮询是唯一正确方案
                    RateLimiter rateLimiter = getResetPhoneEnvRateLimiter(serverIp);
                    {
                        long rateLimitWaitStart = System.currentTimeMillis();
                        while (!rateLimiter.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                            if (isTaskStopped(taskId)) {
                                return "STOPPED: 任务已停止（令牌桶等待中）";
                            }
                        }
                        double waitSeconds = (System.currentTimeMillis() - rateLimitWaitStart) / 1000.0;
                        if (waitSeconds > 0.1) {
                            log.info("{} {} - 令牌桶限流等待 {}s（服务器: {}，速率: {}/min）",
                                    logPrefix, phoneId, String.format("%.1f", waitSeconds), serverIp, resetRatePerMinutePerServer);
                        }
                    }

                    // 再获取信号量（并发上限），防止超过服务器最大并发能力
                    // 同样用 tryAcquire(500ms) 轮询，确保停止信号能在 500ms 内被感知
                    Semaphore semaphore = getResetPhoneEnvSemaphore(serverIp);
                    log.debug("{} {} - 等待ResetPhoneEnv调用许可（服务器: {}，当前可用: {}）", logPrefix, phoneId, serverIp, semaphore.availablePermits());
                    while (!semaphore.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                        if (isTaskStopped(taskId)) {
                            return "STOPPED: 任务已停止（信号量等待中）";
                        }
                    }
                    long apiCallStartTime = System.currentTimeMillis();
                    try {
                        int currentConcurrency = maxResetPhoneEnvConcurrencyPerServer - semaphore.availablePermits();
                        log.info("{} {} - 开始调用ResetPhoneEnv接口（服务器: {}，当前并发: {}/{}）",
                                logPrefix, phoneId, serverIp, currentConcurrency, maxResetPhoneEnvConcurrencyPerServer);

                        try {
                            // 10.7 网段仍调用 ResetPhoneEnv；10.13 网段改为调用 TTFarmResetPhone（xray_server_ip 暂写死）
                            // 用 CompletableFuture + 超时保护：若 ResetPhoneEnv 卡住超过 8 分钟，立即放弃并释放信号量槽位，
                            // 避免一台卡死的手机长期占用并发槽，拖累所有其他手机
                            final String finalCountry = country;
                            final String finalSdk = sdk;
                            final String finalImagePath = imagePath;
                            final String finalGaidTag = gaidTag;
                            final String finalDynamicIpChannel = dynamicIpChannel;
                            final String finalStaticIpChannel = staticIpChannel;
                            final String finalBiz = biz;
                            CompletableFuture<Map<String, Object>> resetFuture;
                            if (serverIp != null && serverIp.startsWith("10.13.")) {
                                resetFuture = CompletableFuture.supplyAsync(() ->
                                    apiService.ttFarmResetPhone(
                                            phoneId, serverIp, "192.168.41.84",
                                            finalCountry, finalSdk, finalImagePath,
                                            finalDynamicIpChannel, false
                                    ), sshCommandExecutor);
                            } else {
                                resetFuture = CompletableFuture.supplyAsync(() ->
                                    apiService.resetPhoneEnv(
                                            phoneId, serverIp,
                                            finalCountry, finalSdk, finalImagePath, finalGaidTag,
                                            finalDynamicIpChannel, finalStaticIpChannel, finalBiz
                                    ), sshCommandExecutor);
                            }
                            try {
                                resetResult = resetFuture.get(7, TimeUnit.MINUTES);
                            } catch (java.util.concurrent.TimeoutException te) {
                                resetFuture.cancel(true);
                                long elapsed = System.currentTimeMillis() - apiCallStartTime;
                                log.error("{} {} - ResetPhoneEnv调用超时（已等待 {}ms，超过7分钟上限），立即释放信号量槽位",
                                        logPrefix, phoneId, elapsed);
                                throw new RuntimeException("ResetPhoneEnv超时（>7min），phoneId=" + phoneId);
                            } catch (java.util.concurrent.ExecutionException ee) {
                                throw (ee.getCause() instanceof Exception)
                                        ? (Exception) ee.getCause() : new RuntimeException(ee.getCause());
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
                                maybeRequestCreateFarmOnGaidError(message);
                                return "FAILED: ResetPhoneEnv失败 - " + message;
                            }
                        }
                            }
                            
                        if (code != 0) {
                            String message = (String) responseStatus.get("message");
                                log.warn("{} {} - ResetPhoneEnv返回非0状态码: code={}, message={}, 重试次数: {}/{}",
                                        logPrefix, phoneId, code, message, retryCount, maxRetries);

                                // DynamicIPError：当前渠道不可用，本轮内按优先级切换渠道重试
                                if (message != null && message.contains("DynamicIPError")) {
                                    if (ipChannelFallbackIndex < ipChannelFallbacks.size()) {
                                        String nextChannel = ipChannelFallbacks.get(ipChannelFallbackIndex++);
                                        log.warn("{} {} - DynamicIPError，渠道 [{}] 不可用，切换到 [{}] 重试",
                                                logPrefix, phoneId, dynamicIpChannel, nextChannel);
                                        dynamicIpChannel = nextChannel;
                                        // retryCount 不递增，渠道切换不消耗重试次数
                                        continue;
                                    } else {
                                        log.error("{} {} - DynamicIPError，所有渠道均不可用（已尝试: lajiao/netnut_biu/kookeey），放弃",
                                                logPrefix, phoneId);
                                        return "FAILED: ResetPhoneEnv失败 (DynamicIPError，所有渠道均不可用) - " + message;
                                    }
                                }

                                // 代理配置失败（远端已自行重试多次）属于不可重试的确定性失败，直接 fail fast
                                boolean isNonRetryable = message != null && (
                                        message.contains("failed after") ||
                                        message.contains("SetupAndVerifyProxy") ||
                                        message.contains("Both IP verification APIs failed"));
                                if (!isNonRetryable && retryCount < maxRetries) {
                                    retryCount++;
                                    continue; // 继续重试
                                } else {
                                    if (isNonRetryable) {
                                        log.error("{} {} - ResetPhoneEnv不可重试失败（代理/IP验证确定性错误）: code={}, message={}",
                                                logPrefix, phoneId, code, message);
                                    } else {
                                        log.error("{} {} - ResetPhoneEnv重试{}次后仍失败: code={}, message={}",
                                                logPrefix, phoneId, maxRetries, code, message);
                                    }
                            maybeRequestCreateFarmOnGaidError(message);
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
                        maybeRequestCreateFarmOnGaidError(e.getMessage());
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
            Object stateObj = resetResult.get("state");
            if (stateObj != null) state = stateObj.toString();
            Object cityObj = resetResult.get("city");
            if (cityObj != null) city = cityObj.toString();
            Object modelObj = resetResult.get("model");
            if (modelObj != null) model = modelObj.toString();
            Object buildIdObj = resetResult.get("buildId");
            if (buildIdObj == null) buildIdObj = resetResult.get("build_id");
            if (buildIdObj != null) buildId = buildIdObj.toString();
            Object userAgentObj = resetResult.get("userAgent");
            if (userAgentObj == null) userAgentObj = resetResult.get("user_agent");
            if (userAgentObj != null) userAgent = userAgentObj.toString();
            Object brandObj = resetResult.get("brand");
            if (brandObj != null) brand = brandObj.toString();
            log.info("{} {} - ResetPhoneEnv成功, real_ip={}, gaid={}, state={}, city={}, model={}, buildId={}, brand={}, 重试次数: {}",
                    logPrefix, phoneId, realIp, gaid, state, city, model, buildId, brand, retryCount);

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
            context.setState(state);
            context.setCity(city);
            context.setModel(model);
            context.setBuildId(buildId);
            context.setUserAgent(userAgent);
            context.setBrand(brand);
            context.setTiktokVersion(tiktokVersion);
            context.setImagePath(imagePath);
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
            maybeRequestCreateFarmOnGaidError(e.getMessage());
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
        private String state;
        private String city;
        private String model;
        private String buildId;
        private String userAgent;
        private String brand;
        private String tiktokVersion;
        private String imagePath;
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
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBuildId() { return buildId; }
        public void setBuildId(String buildId) { this.buildId = buildId; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
        public String getBrand() { return brand; }
        public void setBrand(String brand) { this.brand = brand; }
        public String getTiktokVersion() { return tiktokVersion; }
        public void setTiktokVersion(String tiktokVersion) { this.tiktokVersion = tiktokVersion; }
        public String getImagePath() { return imagePath; }
        public void setImagePath(String imagePath) { this.imagePath = imagePath; }
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
                            tryFetchAndPersistTrafficData(partialRecord, phoneId);
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
                        
                        // 注册成功后拉取流量（失败场景也会消耗流量，见 tryFetchAndPersistTrafficData）
                        tryFetchAndPersistTrafficData(accountRegister, phoneId);
                        
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
                            tryFetchAndPersistTrafficData(partialRecord, phoneId);
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
            accountRegister.setState(context.getState());
            accountRegister.setCity(context.getCity());
            accountRegister.setModel(context.getModel());
            accountRegister.setBuildId(context.getBuildId());
            accountRegister.setUserAgent(context.getUserAgent());
            accountRegister.setBrand(context.getBrand());
            
            accountRegister.setTiktokVersion(context.getTiktokVersion());
            accountRegister.setImagePath(context.getImagePath());
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
            accountRegister.setState(context.getState());
            accountRegister.setCity(context.getCity());
            accountRegister.setModel(context.getModel());
            accountRegister.setBuildId(context.getBuildId());
            accountRegister.setUserAgent(context.getUserAgent());
            accountRegister.setBrand(context.getBrand());
            accountRegister.setTiktokVersion(context.getTiktokVersion());
            accountRegister.setImagePath(context.getImagePath());
            
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
     * 注册流程结束后拉取 GetTrafficData 并写入 trafficData（成功/失败均可能消耗代理流量）
     */
    private void tryFetchAndPersistTrafficData(TtAccountRegister accountRegister, String phoneId) {
        if (accountRegister == null || phoneId == null || phoneId.isEmpty()) {
            return;
        }
        try {
            String trafficData = apiService.getTrafficData(phoneId);
            if (trafficData != null && !trafficData.trim().isEmpty()) {
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
        }
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
            failureRecord.setState(context.getState());
            failureRecord.setCity(context.getCity());
            failureRecord.setModel(context.getModel());
            failureRecord.setBuildId(context.getBuildId());
            failureRecord.setUserAgent(context.getUserAgent());
            failureRecord.setBrand(context.getBrand());
            failureRecord.setTiktokVersion(context.getTiktokVersion());
            failureRecord.setImagePath(context.getImagePath());
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
                tryFetchAndPersistTrafficData(failureRecord, phoneId);
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
                tryFetchAndPersistTrafficData(record, phoneId);
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
     * 账号管理：批量导出（同筛选条件，全量不分页，返回行列表供前端生成CSV）
     */
    public Map<String, Object> exportAccountList(String startDate, String endDate,
                                                  String username, String country, String region,
                                                  String registerStatus, String keyStatus, String matureStatus,
                                                  String emailBindStatus, String blockStatus, String sellStatus,
                                                  String shopStatus,
                                                  String status, String accountType, String note,
                                                  String sortOrder) {
        Map<String, Object> result = new HashMap<>();
        try {
            String safeUsername = trimToNull(username);
            String safeCountry = trimToNull(country);
            String safeRegion = trimToNull(region);
            String safeRegisterStatus = trimToNull(registerStatus);
            String safeKeyStatus = trimToNull(keyStatus);
            String safeMatureStatus = trimToNull(matureStatus);
            String safeEmailBindStatus = trimToNull(emailBindStatus);
            String safeBlockStatus = trimToNull(blockStatus);
            String safeSellStatus = trimToNull(sellStatus);
            String safeShopStatus = trimToNull(shopStatus);
            String safeStatus = trimToNull(status);
            String safeAccountType = trimToNull(accountType);
            String safeNote = trimToNull(note);
            String safeSortOrder = normalizeSortOrder(sortOrder);

            LocalDateTime start = parseDateStart(startDate);
            LocalDateTime end = parseDateEnd(endDate);

            LambdaQueryWrapper<TtAccountRegister> wrapper = new LambdaQueryWrapper<>();
            applyAccountManageFilters(wrapper, start, end,
                    safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote);
            applyAccountOrder(wrapper, safeSortOrder);

            List<TtAccountRegister> source = ttAccountRegisterRepository.selectList(wrapper);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (TtAccountRegister ar : source) {
                Map<String, Object> row = buildAccountManageRow(ar);
                rows.add(row);
            }
            result.put("success", true);
            result.put("data", rows);
            result.put("total", rows.size());
        } catch (Exception e) {
            log.error("导出账号列表异常", e);
            result.put("success", false);
            result.put("message", "导出失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 账号管理：批量导入（按 username 匹配，有则更新，无则插入）
     * 每条记录支持字段：username, email, password, status, note, country
     * status 映射：封号→block_time=now(); 已售→is_sell_out=1; 可售→清除封号/售出标记
     */
    public Map<String, Object> batchUpsertAccounts(List<Map<String, Object>> rows) {
        Map<String, Object> result = new HashMap<>();
        int insertCount = 0, updateCount = 0, skipCount = 0;
        List<String> errors = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            String username = trimToNull(asString(row.get("username")));
            if (username == null) {
                skipCount++;
                errors.add("跳过：username为空的行");
                continue;
            }
            try {
                TtAccountRegister existing = ttAccountRegisterRepository.findByUsername(username);
                if (existing != null) {
                    // 更新
                    applyImportRow(existing, row);
                    existing.setUpdatedAt(LocalDateTime.now());
                    ttAccountRegisterRepository.updateById(existing);
                    updateCount++;
                } else {
                    // 插入
                    TtAccountRegister ar = new TtAccountRegister();
                    ar.setUsername(username);
                    ar.setPhoneId("import");   // 占位，保证非空
                    ar.setCreatedAt(LocalDateTime.now());
                    ar.setUpdatedAt(LocalDateTime.now());
                    applyImportRow(ar, row);
                    ttAccountRegisterRepository.insert(ar);
                    insertCount++;
                }
            } catch (Exception e) {
                skipCount++;
                errors.add("username=" + username + " 处理失败: " + e.getMessage());
                log.warn("账号导入upsert失败: username={}", username, e);
            }
        }

        result.put("success", true);
        result.put("insertCount", insertCount);
        result.put("updateCount", updateCount);
        result.put("skipCount", skipCount);
        result.put("errors", errors);
        result.put("message", String.format("导入完成：新增 %d 条，更新 %d 条，跳过 %d 条", insertCount, updateCount, skipCount));
        // 导入会影响列表总数，清理总数缓存避免旧值
        invalidateManageCountCaches();
        return result;
    }

    /**
     * 将导入行的字段写入账号实体
     * 支持：email, password, status, note, country
     */
    private void applyImportRow(TtAccountRegister ar, Map<String, Object> row) {
        String email = trimToNull(asString(row.get("email")));
        if (email != null) ar.setEmail(email);

        String password = trimToNull(asString(row.get("password")));
        if (password != null) ar.setPassword(password);

        String country = trimToNull(asString(row.get("country")));
        if (country != null) ar.setCountry(country.toUpperCase(Locale.ROOT));

        String note = trimToNull(asString(row.get("note")));
        if (note != null) ar.setNote(note);

        String newEmail = trimToNull(asString(row.get("new_email")));
        if (newEmail != null) ar.setNewEmail(newEmail);

        String status = trimToNull(asString(row.get("status")));
        if (status != null) {
            applyStatusToAccount(ar, status);
        }
    }

    private void applyStatusToAccount(TtAccountRegister ar, String status) {
        // 未完成不参与“写入状态”，保持原值不变（避免导入导出的数据无法回填）。
        if ("未完成".equals(status) || "UNFINISHED".equalsIgnoreCase(status)) {
            return;
        }
        if ("封号".equals(status) || "BLOCKED".equalsIgnoreCase(status) || "2FA成功-封号".equals(status)) {
            ar.setIsSellOut(0);
            ar.setShopStatus(0);
            ar.setNurtureStatus(0);
            ar.setIs2faSetupSuccess(1);
            if (ar.getBlockTime() == null) {
                ar.setBlockTime(LocalDateTime.now());
            }
            return;
        }
        if ("已售".equals(status) || "SOLD".equalsIgnoreCase(status)) {
            ar.setIsSellOut(1);
            ar.setShopStatus(0);
            ar.setNurtureStatus(0);
            return;
        }
        if ("可售".equals(status) || "SALEABLE".equalsIgnoreCase(status)) {
            ar.setIsSellOut(0);
            ar.setShopStatus(0);
            ar.setNurtureStatus(0);
            ar.setBlockTime(null);
            ar.setIs2faSetupSuccess(1);
            return;
        }
        if ("橱窗".equals(status)) {
            ar.setIsSellOut(0);
            ar.setShopStatus(1);
            ar.setNurtureStatus(0);
            ar.setBlockTime(null);
            ar.setIs2faSetupSuccess(1);
            return;
        }
        if ("养号".equals(status)) {
            ar.setIsSellOut(0);
            ar.setShopStatus(0);
            ar.setNurtureStatus(1);
            ar.setBlockTime(null);
            ar.setIs2faSetupSuccess(1);
            return;
        }
        if ("2FA失败".equals(status) || "2FA_FAIL".equalsIgnoreCase(status)) {
            ar.setIsSellOut(0);
            ar.setShopStatus(0);
            ar.setNurtureStatus(0);
            ar.setBlockTime(null);
            ar.setIs2faSetupSuccess(0);
            return;
        }
        if ("2FA成功-正常".equals(status) || "2FA_OK_NORMAL".equalsIgnoreCase(status)) {
            ar.setIsSellOut(0);
            ar.setShopStatus(0);
            ar.setNurtureStatus(0);
            ar.setIs2faSetupSuccess(1);
            ar.setBlockTime(null);
            return;
        }
        if ("换绑成功".equals(status) || "REBIND_OK".equalsIgnoreCase(status)) {
            ar.setIsSellOut(0);
            ar.setShopStatus(0);
            ar.setNurtureStatus(0);
            ar.setIs2faSetupSuccess(1);
            ar.setBlockTime(null);
            ar.setNewEmailBindSuccess(1);
            return;
        }
        if ("换绑失败".equals(status) || "REBIND_FAIL".equalsIgnoreCase(status)) {
            ar.setIsSellOut(0);
            ar.setShopStatus(0);
            ar.setNurtureStatus(0);
            ar.setIs2faSetupSuccess(1);
            ar.setBlockTime(null);
            ar.setNewEmailBindSuccess(0);
            return;
        }

        // 未匹配的状态值：直接拒绝写入，保证“导入校验值”语义明确。
        throw new IllegalArgumentException("未知状态值: " + status + "，允许值：" +
                "2FA失败 / 2FA成功-封号 / 2FA成功-正常 / 可售 / 已售 / 换绑成功 / 换绑失败 / 养号 / 橱窗；或英文缩写如 2FA_FAIL / BLOCKED / 2FA_OK_NORMAL / SALEABLE / SOLD / REBIND_OK / REBIND_FAIL。");
    }

    /**
     * 账号管理列表（分页+筛选）
     * 查询区=筛选条件，展示区=分页结果。
     */
    public Map<String, Object> getAccountManageList(int page, int size,
                                                    String startDate, String endDate,
                                                    String username, String country, String region,
                                                    String registerStatus, String keyStatus, String matureStatus,
                                                    String emailBindStatus, String blockStatus, String sellStatus,
                                                    String shopStatus,
                                                    String status, String accountType, String note,
                                                    String sortOrder) {
        Map<String, Object> result = new HashMap<>();
        try {
            int safePage = page < 1 ? 1 : page;
            int safeSize = normalizeAccountPageSize(size);
            String safeUsername = trimToNull(username);
            String safeCountry = trimToNull(country);
            String safeRegion = trimToNull(region);
            String safeRegisterStatus = trimToNull(registerStatus);
            String safeKeyStatus = trimToNull(keyStatus);
            String safeMatureStatus = trimToNull(matureStatus);
            String safeEmailBindStatus = trimToNull(emailBindStatus);
            String safeBlockStatus = trimToNull(blockStatus);
            String safeSellStatus = trimToNull(sellStatus);
            String safeShopStatus = trimToNull(shopStatus);
            String safeStatus = trimToNull(status);
            String safeAccountType = trimToNull(accountType);
            String safeNote = trimToNull(note);
            String safeSortOrder = normalizeSortOrder(sortOrder);

            LocalDateTime start = parseDateStart(startDate);
            LocalDateTime end = parseDateEnd(endDate);
            if (start != null && end != null && start.isAfter(end)) {
                result.put("success", false);
                result.put("message", "开始日期不能晚于结束日期");
                return result;
            }

            LambdaQueryWrapper<TtAccountRegister> countWrapper = new LambdaQueryWrapper<>();
            applyAccountManageFilters(countWrapper, start, end,
                    safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote);

            LambdaQueryWrapper<TtAccountRegister> dataWrapper = new LambdaQueryWrapper<>();
            applyAccountManageFilters(dataWrapper, start, end,
                    safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote);
            // 只查列表展示所需字段，避免把大字段整行拉回造成 I/O 放大
            dataWrapper.select(
                    TtAccountRegister::getId,
                    TtAccountRegister::getPhoneId,
                    TtAccountRegister::getCreatedAt,
                    TtAccountRegister::getBlockTime,
                    TtAccountRegister::getIsSellOut,
                    TtAccountRegister::getRegisterSuccess,
                    TtAccountRegister::getIs2faSetupSuccess,
                    TtAccountRegister::getNewEmailBindSuccess,
                    TtAccountRegister::getNewEmail,
                    TtAccountRegister::getUsername,
                    TtAccountRegister::getPassword,
                    TtAccountRegister::getEmail,
                    TtAccountRegister::getAuthenticatorKey,
                    TtAccountRegister::getNote,
                    TtAccountRegister::getIp,
                    TtAccountRegister::getState,
                    TtAccountRegister::getCity,
                    TtAccountRegister::getModel,
                    TtAccountRegister::getAndroidVersion,
                    TtAccountRegister::getCountry
            );
            applyAccountOrder(dataWrapper, safeSortOrder);

            List<Map<String, Object>> rows = new ArrayList<>();
            long total;
            long totalPages;
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<TtAccountRegister> pageObj =
                    new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(safePage, safeSize, true);
            com.baomidou.mybatisplus.core.metadata.IPage<TtAccountRegister> pageResult =
                    ttAccountRegisterRepository.selectPage(pageObj, dataWrapper);
            boolean totalAccurate = true;
            total = pageResult.getTotal();
            totalPages = Math.max(1, (total + safeSize - 1) / safeSize);
            for (TtAccountRegister ar : pageResult.getRecords()) {
                rows.add(buildAccountManageRow(ar));
            }

            Map<String, Object> data = new HashMap<>();
            data.put("list", rows);
            data.put("total", total);
            data.put("page", safePage);
            data.put("size", safeSize);
            data.put("totalPages", totalPages);
            data.put("totalAccurate", totalAccurate);
            data.put("pageSizeOptions", Arrays.asList(10, 50, 100));

            result.put("success", true);
            result.put("data", data);
            return result;
        } catch (Exception e) {
            log.error("查询账号管理列表异常", e);
            result.put("success", false);
            result.put("message", "查询账号管理列表失败: " + e.getMessage());
            return result;
        }
    }

    public Map<String, Object> getAccountDateSummary(String startDate, String endDate) {
        Map<String, Object> result = new HashMap<>();
        try {
            LocalDateTime start = parseDateStart(startDate);
            LocalDateTime end = parseDateEnd(endDate);
            if (start == null || end == null) {
                result.put("success", false);
                result.put("message", "请提供开始日期和结束日期");
                return result;
            }
            if (start.isAfter(end)) {
                result.put("success", false);
                result.put("message", "开始日期不能晚于结束日期");
                return result;
            }

            LambdaQueryWrapper<TtAccountRegister> registerSuccessWrapper = new LambdaQueryWrapper<>();
            registerSuccessWrapper.ge(TtAccountRegister::getCreatedAt, start)
                    .le(TtAccountRegister::getCreatedAt, end)
                    .eq(TtAccountRegister::getRegisterSuccess, true);
            long registerSuccessCount = ttAccountRegisterRepository.selectCount(registerSuccessWrapper);

            LambdaQueryWrapper<TtAccountRegister> twofaSuccessWrapper = new LambdaQueryWrapper<>();
            twofaSuccessWrapper.ge(TtAccountRegister::getCreatedAt, start)
                    .le(TtAccountRegister::getCreatedAt, end)
                    .eq(TtAccountRegister::getIs2faSetupSuccess, 1);
            long twofaSuccessCount = ttAccountRegisterRepository.selectCount(twofaSuccessWrapper);

            double twofaRate = registerSuccessCount <= 0
                    ? 0D
                    : (twofaSuccessCount * 100.0D / registerSuccessCount);

            Map<String, Object> data = new HashMap<>();
            data.put("registerSuccessCount", registerSuccessCount);
            data.put("twofaSuccessCount", twofaSuccessCount);
            data.put("twofaRate", Math.round(twofaRate * 100.0D) / 100.0D);
            result.put("success", true);
            result.put("data", data);
            return result;
        } catch (Exception e) {
            log.error("按日期统计账号数据失败: startDate={}, endDate={}", startDate, endDate, e);
            result.put("success", false);
            result.put("message", "统计失败: " + e.getMessage());
            return result;
        }
    }

    /**
     * 账号管理：在当前筛选结果集内统计各维度数量（用于占比展示；与列表 WHERE 一致，再叠加各子条件做拆分）
     */
    public Map<String, Object> getAccountFilterStats(String startDate, String endDate,
                                                     String username, String country, String region,
                                                     String registerStatus, String keyStatus, String matureStatus,
                                                     String emailBindStatus, String blockStatus, String sellStatus,
                                                     String shopStatus,
                                                     String status, String accountType, String note) {
        Map<String, Object> result = new HashMap<>();
        try {
            String safeUsername = trimToNull(username);
            String safeCountry = trimToNull(country);
            String safeRegion = trimToNull(region);
            String safeRegisterStatus = trimToNull(registerStatus);
            String safeKeyStatus = trimToNull(keyStatus);
            String safeMatureStatus = trimToNull(matureStatus);
            String safeEmailBindStatus = trimToNull(emailBindStatus);
            String safeBlockStatus = trimToNull(blockStatus);
            String safeSellStatus = trimToNull(sellStatus);
            String safeShopStatus = trimToNull(shopStatus);
            String safeStatus = trimToNull(status);
            String safeAccountType = trimToNull(accountType);
            String safeNote = trimToNull(note);

            LocalDateTime start = parseDateStart(startDate);
            LocalDateTime end = parseDateEnd(endDate);
            if (start != null && end != null && start.isAfter(end)) {
                result.put("success", false);
                result.put("message", "开始日期不能晚于结束日期");
                return result;
            }

            LocalDate matureCutoffDate = LocalDate.now().minusMonths(1);
            LocalDateTime matureCutoffEnd = matureCutoffDate.atTime(23, 59, 59);

            long total = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, null);

            long regOk = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, w -> w.eq(TtAccountRegister::getRegisterSuccess, true));
            long regFail = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, w -> w.eq(TtAccountRegister::getRegisterSuccess, false));

            long keyOk = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, w -> w.eq(TtAccountRegister::getIs2faSetupSuccess, 1));
            long keyFail = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, w -> w.in(TtAccountRegister::getIs2faSetupSuccess, 0, 2));

            long mat = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, w -> w.le(TtAccountRegister::getCreatedAt, matureCutoffEnd));
            long unmat = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, w -> w.gt(TtAccountRegister::getCreatedAt, matureCutoffEnd));

            long eb1 = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, w -> w.eq(TtAccountRegister::getNewEmailBindSuccess, 1));
            long eb0 = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, w -> w.eq(TtAccountRegister::getNewEmailBindSuccess, 0));
            long ebNull = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, w -> w.isNull(TtAccountRegister::getNewEmailBindSuccess));

            long blk = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, w -> w.isNotNull(TtAccountRegister::getBlockTime));
            long unblk = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, w -> w.isNull(TtAccountRegister::getBlockTime));

            long sold = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, w -> w.eq(TtAccountRegister::getIsSellOut, 1));
            long saleable = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, w -> applySaleableStrictFilter(w, matureCutoffEnd));
            long sellOther = Math.max(0L, total - sold - saleable);

            String shopGaidSql = "SELECT DISTINCT gaid FROM tt_follow_details_new " +
                    "WHERE shop_status = 3 AND gaid IS NOT NULL AND gaid <> ''";
            String shopUserSql = "SELECT DISTINCT username FROM tt_follow_details_new " +
                    "WHERE shop_status = 3 AND username IS NOT NULL AND username <> ''";
            long winShop = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, w -> w.inSql(TtAccountRegister::getGaid, shopGaidSql)
                            .inSql(TtAccountRegister::getUsername, shopUserSql));
            String notShopSql = "NOT (tt_account_register.gaid IN (" + shopGaidSql + ") AND tt_account_register.username IN (" + shopUserSql + "))";
            long winMatrix = countWithAccountManageFilters(start, end, safeUsername, safeCountry, safeRegion,
                    safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                    safeStatus, safeAccountType, safeNote, w -> w.eq(TtAccountRegister::getNurtureStatus, 1).apply(notShopSql));
            long winOther = Math.max(0L, total - winShop - winMatrix);

            Map<String, Object> data = new HashMap<>();
            data.put("total", total);
            Map<String, Object> register = new HashMap<>();
            register.put("success", regOk);
            register.put("fail", regFail);
            data.put("register", register);
            Map<String, Object> key = new HashMap<>();
            key.put("success", keyOk);
            key.put("fail", keyFail);
            data.put("key", key);
            Map<String, Object> mature = new HashMap<>();
            mature.put("mature", mat);
            mature.put("unmature", unmat);
            data.put("mature", mature);
            Map<String, Object> emailBind = new HashMap<>();
            emailBind.put("success", eb1);
            emailBind.put("fail", eb0);
            emailBind.put("none", ebNull);
            data.put("emailBind", emailBind);
            Map<String, Object> block = new HashMap<>();
            block.put("blocked", blk);
            block.put("unblocked", unblk);
            data.put("block", block);
            Map<String, Object> sell = new HashMap<>();
            sell.put("sold", sold);
            sell.put("saleable", saleable);
            sell.put("other", sellOther);
            data.put("sell", sell);
            Map<String, Object> window = new HashMap<>();
            window.put("shop", winShop);
            window.put("matrix", winMatrix);
            window.put("other", winOther);
            data.put("window", window);

            result.put("success", true);
            result.put("data", data);
            return result;
        } catch (Exception e) {
            log.error("账号筛选结果统计失败", e);
            result.put("success", false);
            result.put("message", "统计失败: " + e.getMessage());
            return result;
        }
    }

    private void applySaleableStrictFilter(LambdaQueryWrapper<TtAccountRegister> wrapper, LocalDateTime matureCutoffEnd) {
        // 可售的统一定义（不再额外限制橱窗/矩阵/换绑状态）：
        // 1) 未标记已售
        wrapper.apply("(is_sell_out <> 1 OR is_sell_out IS NULL)");
        // 2) 2FA 成功
        wrapper.eq(TtAccountRegister::getIs2faSetupSuccess, 1);
        // 3) 未封号
        wrapper.isNull(TtAccountRegister::getBlockTime);
        // 4) 已满月
        wrapper.le(TtAccountRegister::getCreatedAt, matureCutoffEnd);
    }

    private long countWithAccountManageFilters(LocalDateTime start, LocalDateTime end,
                                               String safeUsername, String safeCountry, String safeRegion,
                                               String safeRegisterStatus, String safeKeyStatus, String safeMatureStatus,
                                               String safeEmailBindStatus, String safeBlockStatus, String safeSellStatus, String safeShopStatus,
                                               String safeStatus, String safeAccountType, String safeNote,
                                               Consumer<LambdaQueryWrapper<TtAccountRegister>> extra) {
        LambdaQueryWrapper<TtAccountRegister> w = new LambdaQueryWrapper<>();
        applyAccountManageFilters(w, start, end, safeUsername, safeCountry, safeRegion,
                safeRegisterStatus, safeKeyStatus, safeMatureStatus, safeEmailBindStatus, safeBlockStatus, safeSellStatus, safeShopStatus,
                safeStatus, safeAccountType, safeNote);
        if (extra != null) {
            extra.accept(w);
        }
        return ttAccountRegisterRepository.selectCount(w);
    }

    public Map<String, Object> getAccountDetail(Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (id == null || id <= 0) {
                result.put("success", false);
                result.put("message", "id无效");
                return result;
            }
            TtAccountRegister ar = ttAccountRegisterRepository.selectById(id);
            if (ar == null) {
                result.put("success", false);
                result.put("message", "账号不存在");
                return result;
            }
            Map<String, Object> row = buildAccountManageRow(ar);
            row.put("authenticatorKey", ar.getAuthenticatorKey());
            row.put("registerSuccess", ar.getRegisterSuccess());
            row.put("is2faSetupSuccess", ar.getIs2faSetupSuccess());
            row.put("newEmailBindSuccess", ar.getNewEmailBindSuccess());
            result.put("success", true);
            result.put("data", row);
            return result;
        } catch (Exception e) {
            log.error("查询账号详情失败: id={}", id, e);
            result.put("success", false);
            result.put("message", "查询详情失败: " + e.getMessage());
            return result;
        }
    }

    public Map<String, Object> updateAccount(Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (request == null) {
                result.put("success", false);
                result.put("message", "请求不能为空");
                return result;
            }
            Long id = null;
            Object idObj = request.get("id");
            if (idObj instanceof Number) {
                id = ((Number) idObj).longValue();
            } else if (idObj instanceof String) {
                try {
                    id = Long.parseLong(((String) idObj).trim());
                } catch (Exception ignored) {
                }
            }
            if (id == null || id <= 0) {
                result.put("success", false);
                result.put("message", "id无效");
                return result;
            }

            TtAccountRegister ar = ttAccountRegisterRepository.selectById(id);
            if (ar == null) {
                result.put("success", false);
                result.put("message", "账号不存在");
                return result;
            }

            String email = trimToNull(asString(request.get("email")));
            if (email != null) ar.setEmail(email);
            String password = trimToNull(asString(request.get("password")));
            if (password != null) ar.setPassword(password);
            String note = trimToNull(asString(request.get("note")));
            if (note != null || request.containsKey("note")) ar.setNote(note);
            String country = trimToNull(asString(request.get("country")));
            if (country != null) ar.setCountry(country.toUpperCase(Locale.ROOT));
            String authenticatorKey = trimToNull(asString(request.get("authenticatorKey")));
            if (authenticatorKey != null || request.containsKey("authenticatorKey")) ar.setAuthenticatorKey(authenticatorKey);
            String newEmail = trimToNull(asString(request.get("newEmail")));
            if (newEmail != null || request.containsKey("newEmail")) ar.setNewEmail(newEmail);

            String status = trimToNull(asString(request.get("status")));
            if (status != null) {
                applyStatusToAccount(ar, status);
            }

            if (request.containsKey("newEmailBindSuccess")) {
                Integer newEmailBindSuccess = toNullableInt(request.get("newEmailBindSuccess"));
                ar.setNewEmailBindSuccess(newEmailBindSuccess);
            }

            ar.setUpdatedAt(LocalDateTime.now());
            ttAccountRegisterRepository.updateById(ar);
            invalidateManageCountCaches();

            result.put("success", true);
            result.put("message", "更新成功");
            Map<String, Object> row = buildAccountManageRow(ar);
            row.put("authenticatorKey", ar.getAuthenticatorKey());
            result.put("data", row);
            return result;
        } catch (Exception e) {
            log.error("更新账号失败: request={}", request, e);
            result.put("success", false);
            result.put("message", "更新失败: " + e.getMessage());
            return result;
        }
    }

    /**
     * 账号管理：开始养号（勾选批量）
     * 1) 将 tt_account_register.nurture_status 更新为 1
     * 2) 向 tt_follow_details_new 插入一条养号记录（用于后续开窗/养号链路）
     */
    @Transactional
    public Map<String, Object> startNurtureAccounts(List<Long> accountIds) {
        Map<String, Object> result = new HashMap<>();
        if (accountIds == null || accountIds.isEmpty()) {
            result.put("success", false);
            result.put("message", "请先勾选账号");
            return result;
        }
        int updatedCount = 0;
        int insertedCount = 0;
        int skipCount = 0;
        List<String> errors = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Long id : accountIds) {
            if (id == null || id <= 0) {
                skipCount++;
                continue;
            }
            try {
                TtAccountRegister ar = ttAccountRegisterRepository.selectById(id);
                if (ar == null) {
                    skipCount++;
                    continue;
                }

                // 更新账号表养号状态
                ar.setNurtureStatus(1);
                ar.setUpdatedAt(now);
                ttAccountRegisterRepository.updateById(ar);
                updatedCount++;

                // 插入 follow 明细（按需求：每次点击都插入一条）
                TtFollowDetailsNew follow = new TtFollowDetailsNew();
                follow.setPhoneId(ar.getPhoneId());
                follow.setPhoneServerId(ar.getPhoneServerIp());
                follow.setGaid(ar.getGaid());
                follow.setAndroidVersion(ar.getAndroidVersion());
                follow.setTiktokVersion(ar.getTiktokVersion());
                follow.setUsername(ar.getUsername());
                follow.setRegisterTime(ar.getRegisterTime());
                follow.setCreatedAt(now);
                follow.setIp(ar.getIp());
                follow.setAuthenticatorKey(ar.getAuthenticatorKey());
                follow.setNote(ar.getNote());
                follow.setPassword(ar.getPassword());
                follow.setUploadStatus(0);
                follow.setShopStatus(0);
                follow.setNurtureStatus(1);
                follow.setCountry(ar.getCountry());
                ttFollowDetailsNewRepository.insert(follow);
                insertedCount++;
            } catch (Exception e) {
                skipCount++;
                errors.add("id=" + id + " 处理失败: " + e.getMessage());
                log.warn("开始养号失败: id={}", id, e);
            }
        }

        result.put("success", true);
        result.put("updatedCount", updatedCount);
        result.put("insertedCount", insertedCount);
        result.put("skipCount", skipCount);
        result.put("errors", errors);
        result.put("message", String.format("开始养号完成：更新%d条，插入%d条，跳过%d条", updatedCount, insertedCount, skipCount));
        invalidateManageCountCaches();
        return result;
    }

    private void applyAccountManageFilters(LambdaQueryWrapper<TtAccountRegister> wrapper,
                                           LocalDateTime start, LocalDateTime end,
                                           String safeUsername, String safeCountry, String safeRegion,
                                           String safeRegisterStatus, String safeKeyStatus,
                                           String safeMatureStatus, String safeEmailBindStatus,
                                           String safeBlockStatus, String safeSellStatus, String safeShopStatus,
                                           String safeStatus, String safeAccountType, String safeNote) {
        // 注册状态筛选：仅当前端显式选择 SUCCESS 或 FAIL 时才加条件；
        // 否则不过滤 register_success，展示成功+失败的全部账号。
        if ("SUCCESS".equalsIgnoreCase(safeRegisterStatus)) {
            wrapper.eq(TtAccountRegister::getRegisterSuccess, true);
        } else if ("FAIL".equalsIgnoreCase(safeRegisterStatus)) {
            wrapper.eq(TtAccountRegister::getRegisterSuccess, false);
        }

        if (start != null) {
            wrapper.ge(TtAccountRegister::getCreatedAt, start);
        }
        if (end != null) {
            wrapper.le(TtAccountRegister::getCreatedAt, end);
        }
        if (safeUsername != null) {
            // 账号优先前缀匹配，可利用普通索引
            wrapper.likeRight(TtAccountRegister::getUsername, safeUsername);
        }
        if (safeNote != null) {
            wrapper.like(TtAccountRegister::getNote, safeNote);
        }
        if (safeRegion != null) {
            // 地区改前缀匹配，提高索引命中率
            wrapper.likeRight(TtAccountRegister::getState, safeRegion);
        }

        boolean useNewFilters = safeRegisterStatus != null || safeKeyStatus != null || safeMatureStatus != null ||
                safeEmailBindStatus != null || safeBlockStatus != null || safeSellStatus != null || safeShopStatus != null;

        if (useNewFilters) {
            LocalDate matureCutoffDate = LocalDate.now().minusMonths(1);
            LocalDateTime matureCutoffEnd = matureCutoffDate.atTime(23, 59, 59);

            // 密钥
            if (safeKeyStatus != null && !"ALL".equalsIgnoreCase(safeKeyStatus)) {
                if ("SUCCESS".equalsIgnoreCase(safeKeyStatus)) {
                    wrapper.eq(TtAccountRegister::getIs2faSetupSuccess, 1);
                } else if ("FAIL".equalsIgnoreCase(safeKeyStatus)) {
                    wrapper.in(TtAccountRegister::getIs2faSetupSuccess, 0, 2);
                }
            }

            // 满月/未满：仅按注册日期与「满月」分界，不叠加 is_2fa_setup_success（便于与密钥等维度任意组合）
            if (safeMatureStatus != null && !"ALL".equalsIgnoreCase(safeMatureStatus)) {
                if ("MATURE".equalsIgnoreCase(safeMatureStatus)) {
                    wrapper.le(TtAccountRegister::getCreatedAt, matureCutoffEnd);
                } else if ("UNMATURE".equalsIgnoreCase(safeMatureStatus)) {
                    wrapper.gt(TtAccountRegister::getCreatedAt, matureCutoffEnd);
                }
            }

            // 封号/未封：仅看 block_time，不叠加其它字段
            if (safeBlockStatus != null && !"ALL".equalsIgnoreCase(safeBlockStatus)) {
                if ("BLOCKED".equalsIgnoreCase(safeBlockStatus)) {
                    wrapper.isNotNull(TtAccountRegister::getBlockTime);
                } else if ("UNBLOCKED".equalsIgnoreCase(safeBlockStatus)) {
                    wrapper.isNull(TtAccountRegister::getBlockTime);
                }
            }

            // 邮绑：仅 new_email_bind_success，不叠加
            if (safeEmailBindStatus != null && !"ALL".equalsIgnoreCase(safeEmailBindStatus)) {
                if ("SUCCESS".equalsIgnoreCase(safeEmailBindStatus)) {
                    wrapper.eq(TtAccountRegister::getNewEmailBindSuccess, 1);
                } else if ("FAIL".equalsIgnoreCase(safeEmailBindStatus)) {
                    wrapper.eq(TtAccountRegister::getNewEmailBindSuccess, 0);
                }
            }

            // 已售：仅 is_sell_out=1；可售：统一按“未售 + 2FA成功 + 未封号 + 满月”定义
            if (safeSellStatus != null && !"ALL".equalsIgnoreCase(safeSellStatus)) {
                if ("SOLD".equalsIgnoreCase(safeSellStatus)) {
                    wrapper.eq(TtAccountRegister::getIsSellOut, 1);
                } else if ("SALEABLE".equalsIgnoreCase(safeSellStatus)) {
                    applySaleableStrictFilter(wrapper, matureCutoffEnd);
                }
            }

            // 橱窗号：follow 明细 shop_status=3；矩阵号：账号表 nurture_status=1（与其它维度仅 AND，互不叠加隐含条件）
            if (safeShopStatus != null && !"ALL".equalsIgnoreCase(safeShopStatus)) {
                if ("SHOP".equalsIgnoreCase(safeShopStatus)) {
                    wrapper.inSql(TtAccountRegister::getGaid,
                            "SELECT DISTINCT gaid FROM tt_follow_details_new " +
                                    "WHERE shop_status = 3 AND gaid IS NOT NULL AND gaid <> ''")
                            .inSql(TtAccountRegister::getUsername,
                                    "SELECT DISTINCT username FROM tt_follow_details_new " +
                                            "WHERE shop_status = 3 AND username IS NOT NULL AND username <> ''");
                } else if ("MATRIX".equalsIgnoreCase(safeShopStatus)) {
                    wrapper.eq(TtAccountRegister::getNurtureStatus, 1);
                }
            }
        } else {
            // 兼容旧前端：使用单一 status/accountType 参数
            applyAccountStatusFilter(wrapper, safeStatus);
            applyAccountTypeFilter(wrapper, safeAccountType);
        }

        if (safeCountry != null) {
            wrapper.eq(TtAccountRegister::getCountry, safeCountry.toUpperCase(Locale.ROOT));
        }
    }

    private long estimateTotalWithoutCount(int page, int size, int currentSize) {
        long base = (long) (page - 1) * size + currentSize;
        if (currentSize >= size) {
            // 至少还有一页的可能，给前端保留“下一页”入口
            return base + 1;
        }
        return base;
    }

    private String buildAccountManageCountKey(String startDate, String endDate, String safeEmail, String safeCountry,
                                              String safeRegion, String safeStatus, String safeAccountType, String safeNote) {
        return String.join("|",
                String.valueOf(trimToNull(startDate)),
                String.valueOf(trimToNull(endDate)),
                String.valueOf(safeEmail),
                String.valueOf(safeCountry),
                String.valueOf(safeRegion),
                String.valueOf(safeStatus),
                String.valueOf(safeAccountType),
                String.valueOf(safeNote));
    }

    private void triggerAsyncAccountManageCount(String key, LambdaQueryWrapper<TtAccountRegister> countWrapper) {
        if (!accountManageCountComputing.add(key)) return;
        CompletableFuture.runAsync(() -> {
            try {
                long exact = ttAccountRegisterRepository.selectCount(countWrapper);
                accountManageCountCache.put(key, exact);
            } catch (Exception e) {
                log.warn("账号管理异步count失败: key={}", key, e);
            } finally {
                accountManageCountComputing.remove(key);
            }
        }, scheduledTaskExecutor);
    }

    private int normalizeAccountPageSize(int size) {
        if (size == 10 || size == 50 || size == 100) {
            return size;
        }
        return 10;
    }

    private String normalizeSortOrder(String sortOrder) {
        if ("asc".equalsIgnoreCase(trimToNull(sortOrder))) {
            return "asc";
        }
        return "desc";
    }

    private void applyAccountOrder(LambdaQueryWrapper<TtAccountRegister> wrapper, String sortOrder) {
        if ("asc".equalsIgnoreCase(sortOrder)) {
            wrapper.orderByAsc(TtAccountRegister::getCreatedAt);
            wrapper.orderByAsc(TtAccountRegister::getId);
            return;
        }
        wrapper.orderByDesc(TtAccountRegister::getCreatedAt);
        wrapper.orderByDesc(TtAccountRegister::getId);
    }

    private void applyAccountStatusFilter(LambdaQueryWrapper<TtAccountRegister> wrapper, String status) {
        if (status == null || status.isEmpty() || "ALL".equalsIgnoreCase(status) || "全部".equals(status)) {
            return;
        }
        if ("2FA失败".equals(status) || "2FA_FAIL".equalsIgnoreCase(status)) {
            wrapper.eq(TtAccountRegister::getIs2faSetupSuccess, 0);
            return;
        }
        if ("养号".equals(status)) {
            wrapper.eq(TtAccountRegister::getNurtureStatus, 1);
            return;
        }
        if ("橱窗".equals(status)) {
            // 橱窗筛选改为按 follow 明细表判定：
            // tt_account_register 通过 gaid 和 username 同时关联 tt_follow_details_new，
            // 且 follow.shop_status = 3 才视为橱窗号。
            wrapper.inSql(TtAccountRegister::getGaid,
                            "SELECT DISTINCT gaid FROM tt_follow_details_new " +
                                    "WHERE shop_status = 3 AND gaid IS NOT NULL AND gaid <> ''")
                    .inSql(TtAccountRegister::getUsername,
                            "SELECT DISTINCT username FROM tt_follow_details_new " +
                                    "WHERE shop_status = 3 AND username IS NOT NULL AND username <> ''");
            return;
        }
        if ("已售".equals(status) || "SOLD".equalsIgnoreCase(status)) {
            wrapper.eq(TtAccountRegister::getIsSellOut, 1);
            return;
        }
        if ("2FA成功-封号".equals(status) || "封号".equals(status) || "BLOCKED".equalsIgnoreCase(status)) {
            wrapper.eq(TtAccountRegister::getIs2faSetupSuccess, 1);
            wrapper.isNotNull(TtAccountRegister::getBlockTime);
            return;
        }
        if ("2FA成功-正常".equals(status) || "2FA_OK_NORMAL".equalsIgnoreCase(status)) {
            wrapper.eq(TtAccountRegister::getIs2faSetupSuccess, 1);
            wrapper.isNull(TtAccountRegister::getBlockTime);
            return;
        }
        if ("可售".equals(status) || "SALEABLE".equalsIgnoreCase(status)) {
            wrapper.eq(TtAccountRegister::getIs2faSetupSuccess, 1);
            wrapper.isNull(TtAccountRegister::getBlockTime);
            wrapper.le(TtAccountRegister::getCreatedAt, LocalDateTime.now().minusMonths(1));
            return;
        }
        if ("换绑成功".equals(status) || "REBIND_OK".equalsIgnoreCase(status)) {
            wrapper.eq(TtAccountRegister::getIs2faSetupSuccess, 1);
            wrapper.isNull(TtAccountRegister::getBlockTime);
            wrapper.le(TtAccountRegister::getCreatedAt, LocalDateTime.now().minusMonths(1));
            wrapper.eq(TtAccountRegister::getNewEmailBindSuccess, 1);
            return;
        }
        if ("换绑失败".equals(status) || "REBIND_FAIL".equalsIgnoreCase(status)) {
            wrapper.eq(TtAccountRegister::getIs2faSetupSuccess, 1);
            wrapper.isNull(TtAccountRegister::getBlockTime);
            wrapper.le(TtAccountRegister::getCreatedAt, LocalDateTime.now().minusMonths(1));
            wrapper.eq(TtAccountRegister::getNewEmailBindSuccess, 0);
        }
    }

    private void applyAccountTypeFilter(LambdaQueryWrapper<TtAccountRegister> wrapper, String accountType) {
        if (accountType == null || accountType.isEmpty() || "ALL".equalsIgnoreCase(accountType) || "全部".equals(accountType)) {
            return;
        }
        LocalDateTime threshold = LocalDateTime.now().minusMonths(1);
        if ("满月白".equals(accountType) || "MATURE_WHITE".equalsIgnoreCase(accountType)) {
            wrapper.eq(TtAccountRegister::getIs2faSetupSuccess, 1);
            wrapper.le(TtAccountRegister::getCreatedAt, threshold);
            return;
        }
        if ("小白号".equals(accountType) || "NEW_WHITE".equalsIgnoreCase(accountType)) {
            wrapper.eq(TtAccountRegister::getIs2faSetupSuccess, 1);
            wrapper.gt(TtAccountRegister::getCreatedAt, threshold);
        }
    }

    private LocalDateTime parseDateStart(String dateText) {
        String value = trimToNull(dateText);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value).atStartOfDay();
        } catch (Exception e) {
            throw new IllegalArgumentException("startDate格式错误，应为yyyy-MM-dd");
        }
    }

    private LocalDateTime parseDateEnd(String dateText) {
        String value = trimToNull(dateText);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value).atTime(23, 59, 59);
        } catch (Exception e) {
            throw new IllegalArgumentException("endDate格式错误，应为yyyy-MM-dd");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private Map<String, Object> buildAccountManageRow(TtAccountRegister ar) {
        Map<String, Object> row = new HashMap<>();
        String geoRegion = trimToNull(ar.getState()) != null ? ar.getState() : "";
        String geoCity = trimToNull(ar.getCity()) != null ? ar.getCity() : "";
        String geoCountry = trimToNull(ar.getCountry()) != null ? ar.getCountry() : "";

        row.put("id", ar.getId());
        row.put("phoneId", ar.getPhoneId());
        row.put("createdAt", ar.getCreatedAt() != null ? ar.getCreatedAt().toString() : null);
        row.put("registerDate", ar.getCreatedAt() != null ? ar.getCreatedAt().toString() : null);
        row.put("status", calcAccountStatus(ar));
        row.put("newEmailBindSuccess", ar.getNewEmailBindSuccess());
        row.put("newEmailBindStatus", mapNewEmailBindStatus(ar.getNewEmailBindSuccess()));
        row.put("newEmail", ar.getNewEmail());
        row.put("username", ar.getUsername());
        row.put("password", ar.getPassword());
        row.put("email", ar.getEmail());
        row.put("authenticatorKey", ar.getAuthenticatorKey());
        row.put("loginMethod", "2fa");
        row.put("accountType", calcAccountType(ar));
        row.put("note", ar.getNote());
        row.put("ip", ar.getIp());
        row.put("state", geoRegion);
        row.put("city", geoCity);
        row.put("model", ar.getModel());
        row.put("androidVersion", ar.getAndroidVersion());
        row.put("country", geoCountry);
        row.put("geoRegion", geoRegion);   // 供内存过滤使用
        row.put("geoCity", geoCity);
        // 展示区"详情"：ip--州--城市--机型--安卓系统版本
        row.put("detail", String.format("%s--%s--%s--%s--%s",
                asString(ar.getIp()), geoRegion, geoCity,
                asString(ar.getModel()), asString(ar.getAndroidVersion())));
        return row;
    }

    private String mapNewEmailBindStatus(Integer v) {
        if (v == null) return "未换绑";
        if (v == 1) return "换绑成功";
        if (v == 0) return "换绑失败";
        return "未换绑";
    }

    private String calcAccountStatus(TtAccountRegister ar) {
        // 注册失败优先展示（用于前端“注册失败/注册成功”筛选）
        if (ar.getRegisterSuccess() != null && !ar.getRegisterSuccess()) {
            return "注册失败";
        }
        // 已售优先级最高：只要 is_sell_out=1，状态就固定展示为“已售”
        if (ar.getIsSellOut() != null && ar.getIsSellOut() == 1) {
            return "已售";
        }
        // 其次是“橱窗”：已开窗优先展示为“橱窗”
        if (ar.getShopStatus() != null && ar.getShopStatus() == 1) {
            return "橱窗";
        }
        // 再次是“养号”：标记为养号且未开窗
        if (ar.getNurtureStatus() != null && ar.getNurtureStatus() == 1) {
            return "养号";
        }
        Integer twofa = ar.getIs2faSetupSuccess();
        boolean isMature = isMatureAccount(ar.getCreatedAt());

        if (twofa != null && twofa == 0) {
            return "2FA失败";
        }
        if (twofa != null && twofa == 1 && ar.getBlockTime() != null) {
            return "2FA成功-封号";
        }
        if (twofa != null && twofa == 1 && ar.getBlockTime() == null) {
            // 2FA 成功且未封号：满月统一视为“可售”，不再被换绑结果覆盖；
            // 换绑仅通过“换绑状态”列展示。
            if (isMature) {
                return "可售";
            }
            return "2FA成功-正常";
        }
        return "未完成";
    }

    private String calcAccountType(TtAccountRegister ar) {
        if (ar.getIs2faSetupSuccess() == null || ar.getIs2faSetupSuccess() != 1 || ar.getBlockTime() != null) {
            return "-";
        }
        if (isMatureAccount(ar.getCreatedAt())) {
            return "满月白";
        }
        return "小白号";
    }

    private boolean isMatureAccount(LocalDateTime createdAt) {
        if (createdAt == null) return false;
        LocalDate cutoffDate = LocalDate.now().minusMonths(1);
        LocalDate createdDate = createdAt.toLocalDate();
        return createdDate.isBefore(cutoffDate) || createdDate.isEqual(cutoffDate);
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Integer toNullableInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text) || "-1".equals(text)) return null;
        try {
            return Integer.parseInt(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 查看设备：按 gaid 恢复历史环境到指定 phone_id，并返回本地调试命令
     */
    public Map<String, Object> inspectDeviceByPhoneAndGaid(String phoneId, String gaid) {
        Map<String, Object> result = new HashMap<>();
        try {
            String safePhoneId = trimToNull(phoneId);
            String safeGaid = trimToNull(gaid);
            if (safePhoneId == null || safeGaid == null) {
                result.put("success", false);
                result.put("message", "phone_id 和 gaid 不能为空");
                return result;
            }

            TtAccountRegister gaidRecord = ttAccountRegisterRepository.findLatestByGaid(safeGaid);
            if (gaidRecord == null) {
                result.put("success", false);
                result.put("message", "未找到该 gaid 对应的历史记录");
                return result;
            }
            TtAccountRegister phoneRecord = ttAccountRegisterRepository.findLatestByPhoneId(safePhoneId);
            if (phoneRecord == null || trimToNull(phoneRecord.getPhoneServerIp()) == null) {
                result.put("success", false);
                result.put("message", "未找到该 phone_id 对应的 server_ip");
                return result;
            }

            String targetServerIp = phoneRecord.getPhoneServerIp();
            String packageName = "com.zhiliaoapp.musically";
            final String fallbackImageTag = "20260228";
            String imagePath = trimToNull(gaidRecord.getImagePath());
            if (imagePath == null) {
                // image_path 为空时走原有逻辑（默认镜像）
                imagePath = trimToNull(defaultImage);
                if (imagePath == null) imagePath = "";
                // 按需求：image_path 为空时，默认日期标记固定为 20260228
                imagePath = applyImageDateTag(imagePath, fallbackImageTag);
            }
            // image_path 中含 android 版本段时，统一按该 gaid 备份记录的安卓版本校正
            imagePath = adjustImagePathForAndroidVersion(imagePath, gaidRecord.getAndroidVersion());

            apiService.restoreApp(safePhoneId, targetServerIp, packageName, imagePath, safeGaid);

            // 恢复成功后查询容器 adb 端口映射（默认 5555/tcp）
            String portCmd = String.format(
                    "(docker ps | grep -w %s | tail -1 | awk '{print $(NF-1)}' | awk -F ':' '{print $2}' | awk -F '-' '{print $1}' || true)",
                    safePhoneId);
            SshUtil.SshResult portResult = sshCommand(targetServerIp, portCmd);
            String adbPort = extractMappedPort(portResult.getOutput());
            if (adbPort == null) {
                adbPort = extractMappedPort(portResult.getErrorOutput());
            }

            if (adbPort == null) {
                if (!portResult.isSuccess()) {
                    result.put("success", false);
                    result.put("message", "恢复成功，但查询端口失败: " + buildSshErrorMessage(portResult));
                    return result;
                }
                result.put("success", false);
                result.put("message", "恢复成功，但未解析到 ADB 端口，输出: " + asString(portResult.getOutput()));
                return result;
            }

            String tunnelHost = trimToNull(sshProperties.getSshJumpHost());
            if (tunnelHost == null) {
                tunnelHost = trimToNull(targetServerIp);
            }
            String tunnelUser = trimToNull(sshProperties.getSshJumpUsername());
            if (tunnelUser == null) tunnelUser = "ubuntu";

            String localPort = "3334";
            String tunnelCmd = String.format("ssh -N -L %s:%s:%s %s@%s",
                    localPort, targetServerIp, adbPort, tunnelUser, tunnelHost);
            String adbCmd = String.format("adb connect localhost:%s", localPort);

            Map<String, Object> data = new HashMap<>();
            data.put("phoneId", safePhoneId);
            data.put("gaid", safeGaid);
            data.put("serverIp", targetServerIp);
            data.put("adbPort", adbPort);
            data.put("imagePathUsed", imagePath);
            data.put("tunnelCommand", tunnelCmd);
            data.put("adbConnectCommand", adbCmd);
            data.put("tips", Arrays.asList(
                    "第一步：先在本机执行端口转发命令",
                    "第二步：执行 adb connect 命令连接设备"
            ));
            result.put("success", true);
            result.put("message", "恢复成功，请按步骤执行命令连接设备");
            result.put("data", data);
            return result;
        } catch (Exception e) {
            log.error("查看设备并恢复环境失败: phoneId={}, gaid={}", phoneId, gaid, e);
            result.put("success", false);
            result.put("message", "查看设备失败: " + e.getMessage());
            return result;
        }
    }

    private String extractMappedPort(String output) {
        String out = trimToNull(output);
        if (out == null) return null;
        // 纯端口输出（如 49393）
        if (out.matches("^\\d{2,5}$")) {
            return out;
        }
        // 典型输出: 0.0.0.0:49393 或 :::49393
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(":(\\d{2,5})").matcher(out);
        String port = null;
        while (m.find()) {
            port = m.group(1);
        }
        return port;
    }

    private String buildSshErrorMessage(SshUtil.SshResult sshResult) {
        if (sshResult == null) {
            return "SSH结果为空";
        }
        String msg = trimToNull(sshResult.getErrorMessage());
        if (msg != null) {
            return msg;
        }
        String errOut = trimToNull(sshResult.getErrorOutput());
        if (errOut != null) {
            return "stderr: " + errOut;
        }
        String out = trimToNull(sshResult.getOutput());
        if (out != null) {
            return "output: " + out;
        }
        return "exitCode=" + sshResult.getExitCode();
    }

    private String applyImageDateTag(String imagePath, String dateTag) {
        String safeTag = trimToNull(dateTag);
        if (safeTag == null || safeTag.length() != 8) return imagePath;
        String path = imagePath == null ? "" : imagePath;
        // 常见镜像格式包含 androidXX_YYYYMMDD，将日期段替换为固定默认值
        if (path.matches(".*android\\d+_\\d{8}.*")) {
            return path.replaceAll("(android\\d+_)\\d{8}", "$1" + safeTag);
        }
        return path;
    }

    /**
     * 开窗管理列表（分页+筛选）
     */
    public Map<String, Object> getWindowManageList(int page, int size,
                                                   String fanStartDate, String fanEndDate,
                                                   String nurtureStartDate, String nurtureEndDate,
                                                   String nurtureStrategy, String shopStatus,
                                                   String nurtureDevice, String country,
                                                   String account, String note) {
        Map<String, Object> result = new HashMap<>();
        try {
            int safePage = page < 1 ? 1 : page;
            int safeSize = normalizeAccountPageSize(size);
            String safeStrategy = trimToNull(nurtureStrategy);
            String safeDevice = trimToNull(nurtureDevice);
            String safeCountry = trimToNull(country);
            String safeAccount = trimToNull(account);
            String safeNote = trimToNull(note);

            LocalDateTime fanStart = parseDateStart(fanStartDate);
            LocalDateTime fanEnd = parseDateEnd(fanEndDate);
            LocalDateTime nurtureStart = parseDateStart(nurtureStartDate);
            LocalDateTime nurtureEnd = parseDateEnd(nurtureEndDate);

            LambdaQueryWrapper<TtFollowDetailsNew> countWrapper = new LambdaQueryWrapper<>();
            applyWindowManageFilters(countWrapper, fanStart, fanEnd, nurtureStart, nurtureEnd,
                    safeStrategy, shopStatus, safeDevice, safeCountry, safeAccount, safeNote);

            LambdaQueryWrapper<TtFollowDetailsNew> dataWrapper = new LambdaQueryWrapper<>();
            applyWindowManageFilters(dataWrapper, fanStart, fanEnd, nurtureStart, nurtureEnd,
                    safeStrategy, shopStatus, safeDevice, safeCountry, safeAccount, safeNote);
            // 只查页面展示列，减少行读取体积
            dataWrapper.select(
                    TtFollowDetailsNew::getId,
                    TtFollowDetailsNew::getPhoneId,
                    TtFollowDetailsNew::getUsername,
                    TtFollowDetailsNew::getPassword,
                    TtFollowDetailsNew::getGaid,
                    TtFollowDetailsNew::getFanDate,
                    TtFollowDetailsNew::getNurtureDate,
                    TtFollowDetailsNew::getShopStatus,
                    TtFollowDetailsNew::getFollowingType,
                    TtFollowDetailsNew::getRegisterIpRegion,
                    TtFollowDetailsNew::getRegisterEnv,
                    TtFollowDetailsNew::getNote,
                    TtFollowDetailsNew::getCreatedAt,
                    TtFollowDetailsNew::getNurtureDevice,
                    TtFollowDetailsNew::getCountry
            );
            dataWrapper.orderByDesc(TtFollowDetailsNew::getCreatedAt);
            dataWrapper.orderByDesc(TtFollowDetailsNew::getId);

            com.baomidou.mybatisplus.extension.plugins.pagination.Page<TtFollowDetailsNew> pageObj =
                    new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(safePage, safeSize, true);
            com.baomidou.mybatisplus.core.metadata.IPage<TtFollowDetailsNew> pageResult =
                    ttFollowDetailsNewRepository.selectPage(pageObj, dataWrapper);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (TtFollowDetailsNew row : pageResult.getRecords()) {
                rows.add(buildWindowManageRow(row));
            }

            long total = pageResult.getTotal();
            boolean totalAccurate = true;

            Map<String, Object> data = new HashMap<>();
            data.put("list", rows);
            data.put("total", total);
            data.put("page", safePage);
            data.put("size", safeSize);
            data.put("totalPages", Math.max(1, (total + safeSize - 1) / safeSize));
            data.put("totalAccurate", totalAccurate);
            data.put("pageSizeOptions", Arrays.asList(10, 50, 100));
            result.put("success", true);
            result.put("data", data);
            return result;
        } catch (Exception e) {
            log.error("查询开窗管理列表异常", e);
            result.put("success", false);
            result.put("message", "查询开窗管理列表失败: " + e.getMessage());
            return result;
        }
    }

    private void applyWindowManageFilters(LambdaQueryWrapper<TtFollowDetailsNew> wrapper,
                                          LocalDateTime fanStart, LocalDateTime fanEnd,
                                          LocalDateTime nurtureStart, LocalDateTime nurtureEnd,
                                          String safeStrategy, String shopStatus, String safeDevice,
                                          String safeCountry, String safeAccount, String safeNote) {
        if (fanStart != null) {
            wrapper.ge(TtFollowDetailsNew::getFanDate, fanStart);
        }
        if (fanEnd != null) {
            wrapper.le(TtFollowDetailsNew::getFanDate, fanEnd);
        }
        if (nurtureStart != null) {
            wrapper.ge(TtFollowDetailsNew::getNurtureDate, nurtureStart);
        }
        if (nurtureEnd != null) {
            wrapper.le(TtFollowDetailsNew::getNurtureDate, nurtureEnd);
        }
        if (safeAccount != null) {
            // 用户名按前缀检索，避免 contains 导致全表扫描
            wrapper.likeRight(TtFollowDetailsNew::getUsername, safeAccount);
        }
        if (safeNote != null) {
            wrapper.like(TtFollowDetailsNew::getNote, safeNote);
        }
        if (safeStrategy != null && !"ALL".equalsIgnoreCase(safeStrategy)) {
            Integer followingType = parseFollowingType(safeStrategy);
            if (followingType != null) {
                wrapper.eq(TtFollowDetailsNew::getFollowingType, followingType);
            } else {
                // 无法识别的策略值直接返回空结果，避免全表扫描
                wrapper.eq(TtFollowDetailsNew::getId, -1L);
            }
        }
        if (safeDevice != null && !"ALL".equalsIgnoreCase(safeDevice)) {
            wrapper.eq(TtFollowDetailsNew::getNurtureDevice, safeDevice);
        }
        if (safeCountry != null && !"ALL".equalsIgnoreCase(safeCountry)) {
            wrapper.eq(TtFollowDetailsNew::getCountry, safeCountry.toUpperCase(Locale.ROOT));
        }
        applyWindowShopStatusFilter(wrapper, shopStatus);
    }

    private String buildWindowManageCountKey(String fanStartDate, String fanEndDate, String nurtureStartDate, String nurtureEndDate,
                                             String safeStrategy, String shopStatus, String safeDevice, String safeCountry,
                                             String safeAccount, String safeNote) {
        return String.join("|",
                String.valueOf(trimToNull(fanStartDate)),
                String.valueOf(trimToNull(fanEndDate)),
                String.valueOf(trimToNull(nurtureStartDate)),
                String.valueOf(trimToNull(nurtureEndDate)),
                String.valueOf(safeStrategy),
                String.valueOf(trimToNull(shopStatus)),
                String.valueOf(safeDevice),
                String.valueOf(safeCountry),
                String.valueOf(safeAccount),
                String.valueOf(safeNote));
    }

    private void triggerAsyncWindowManageCount(String key, LambdaQueryWrapper<TtFollowDetailsNew> countWrapper) {
        if (!windowManageCountComputing.add(key)) return;
        CompletableFuture.runAsync(() -> {
            try {
                long exact = ttFollowDetailsNewRepository.selectCount(countWrapper);
                windowManageCountCache.put(key, exact);
            } catch (Exception e) {
                log.warn("开窗管理异步count失败: key={}", key, e);
            } finally {
                windowManageCountComputing.remove(key);
            }
        }, scheduledTaskExecutor);
    }

    private void invalidateManageCountCaches() {
        accountManageCountCache.clear();
        accountManageCountComputing.clear();
        windowManageCountCache.clear();
        windowManageCountComputing.clear();
    }

    private void applyWindowShopStatusFilter(LambdaQueryWrapper<TtFollowDetailsNew> wrapper, String shopStatus) {
        String v = trimToNull(shopStatus);
        if (v == null || "ALL".equalsIgnoreCase(v) || "全部".equals(v)) {
            return;
        }
        if ("成功".equals(v) || "SUCCESS".equalsIgnoreCase(v)) {
            wrapper.eq(TtFollowDetailsNew::getShopStatus, 2);
            return;
        }
        if ("失败".equals(v) || "FAILED".equalsIgnoreCase(v)) {
            wrapper.eq(TtFollowDetailsNew::getShopStatus, 1);
            return;
        }
        if ("等待".equals(v) || "PENDING".equalsIgnoreCase(v)) {
            wrapper.eq(TtFollowDetailsNew::getShopStatus, 0);
        }
    }

    private Map<String, Object> buildWindowManageRow(TtFollowDetailsNew row) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", row.getId());
        m.put("phoneId", row.getPhoneId());
        m.put("username", row.getUsername());
        m.put("password", row.getPassword());
        m.put("gaid", row.getGaid());
        LocalDateTime fanDate = row.getFanDate();
        LocalDateTime nurtureDate = row.getNurtureDate();
        m.put("fanDate", fanDate != null ? fanDate.toString() : null);
        m.put("nurtureDate", nurtureDate != null ? nurtureDate.toString() : null);
        m.put("shopStatus", mapShopStatusText(row.getShopStatus()));
        m.put("nurtureStrategy", mapNurtureStrategyText(row.getFollowingType()));
        m.put("registerIp", trimToNull(row.getRegisterIpRegion()));
        m.put("registerEnv", trimToNull(row.getRegisterEnv()));
        m.put("note", row.getNote());
        return m;
    }

    private String mapShopStatusText(Integer status) {
        if (status == null) return null;
        if (status == 2) return "开店成功";
        if (status == 1) return "有开店标识不能开店";
        if (status == 0) return "没有开店标识";
        return null;
    }

    private String mapNurtureStrategyText(Integer followingType) {
        if (followingType == null) return null;
        if (followingType == 1) return "浏览";
        if (followingType == 2) return "发布+浏览";
        return null;
    }

    private Integer parseFollowingType(String strategyText) {
        String v = trimToNull(strategyText);
        if (v == null) return null;
        if ("浏览".equals(v) || "BROWSE".equalsIgnoreCase(v)) return 1;
        if ("发布+浏览".equals(v) || "PUBLISH_BROWSE".equalsIgnoreCase(v)) return 2;
        return null;
    }

    private Map<String, String> resolveIpGeo(String ip) {
        String key = trimToNull(ip);
        if (key == null) {
            return Collections.emptyMap();
        }
        return ipGeoCache.computeIfAbsent(key, this::fetchIpGeo);
    }

    private Map<String, String> fetchIpGeo(String ip) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://ipinfo.io/widget/demo/" + ip);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return Collections.emptyMap();
            }
            try (InputStream in = conn.getInputStream()) {
                String body = new String(in.readAllBytes());
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(body);
                com.fasterxml.jackson.databind.JsonNode data = root.path("data");
                if (data.isMissingNode() || data.isNull()) {
                    data = root;
                }
                String region = data.path("region").asText("");
                String city = data.path("city").asText("");
                String country = data.path("country").asText("");
                Map<String, String> geo = new HashMap<>();
                geo.put("region", region);
                geo.put("city", city);
                geo.put("country", country);
                return geo;
            }
        } catch (Exception e) {
            log.debug("IP归属地查询失败, ip={}", ip, e);
            return Collections.emptyMap();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    /**
     * 查询任务列表（从数据库）
     */
    public Map<String, Object> getTaskList(String status, String taskType, String serverIp, String phoneId, int page, int size) {
        try {
            int safePage = page < 1 ? 1 : page;
            int safeSize = (size == 10 || size == 50 || size == 100) ? size : 10;
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
            wrapper.orderByDesc(TtRegisterTask::getId);

            com.baomidou.mybatisplus.extension.plugins.pagination.Page<TtRegisterTask> pageObj =
                    new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(safePage, safeSize, true);
            com.baomidou.mybatisplus.core.metadata.IPage<TtRegisterTask> pageResult =
                    ttRegisterTaskRepository.selectPage(pageObj, wrapper);
            long total = pageResult.getTotal();
            long totalPages = Math.max(1, (total + safeSize - 1) / safeSize);
            
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
            data.put("total", total);
            data.put("page", safePage);
            data.put("size", safeSize);
            data.put("totalPages", totalPages);
            data.put("totalElements", total);
            
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
            markTaskStopped(taskId);

            // 任务正在运行时，尝试强制终止远端注册脚本进程（避免仅改状态但进程仍在跑）
            if ("RUNNING".equals(task.getStatus())) {
                forceStopRemoteRegisterProcess(task);
            }
            
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
     * 按任务信息在远端 Appium 主机上强制停止注册脚本进程。
     * 匹配条件：python3 + 注册脚本名 + phoneId。
     */
    private void forceStopRemoteRegisterProcess(TtRegisterTask task) {
        try {
            if (task == null || task.getPhoneId() == null || task.getPhoneId().trim().isEmpty()) {
                return;
            }

            String phoneId = task.getPhoneId().trim();
            String scriptHost = task.getAppiumServer();
            if (scriptHost == null || scriptHost.trim().isEmpty()) {
                scriptHost = "10.13.55.85"; // 与执行注册脚本时默认值保持一致
            } else {
                scriptHost = scriptHost.trim();
            }

            String killCmd = String.format(
                "pids=$(ps aux | grep -E 'python3.*(tiktok_register_us_test_account.py|tiktok_register_br_test_account.py)' " +
                "| grep -F '%s' | grep -v grep | awk '{print $2}'); " +
                "if [ -n \"$pids\" ]; then kill -9 $pids 2>/dev/null || true; echo \"$pids\"; else echo 'no_process'; fi",
                phoneId
            );

            SshUtil.SshResult killResult = sshCommand(scriptHost, killCmd);
            if (!killResult.isSuccess()) {
                log.warn("停止任务时远端杀进程命令执行失败，taskId={}, phoneId={}, host={}, error={}",
                        task.getTaskId(), phoneId, scriptHost, killResult.getErrorMessage());
                return;
            }

            String output = killResult.getOutput() == null ? "" : killResult.getOutput().trim();
            if (output.isEmpty() || "no_process".equals(output)) {
                log.info("停止任务时未发现存活注册进程，taskId={}, phoneId={}, host={}",
                        task.getTaskId(), phoneId, scriptHost);
            } else {
                log.info("停止任务时已终止注册进程，taskId={}, phoneId={}, host={}, pids={}",
                        task.getTaskId(), phoneId, scriptHost, output);
            }
        } catch (Exception e) {
            log.warn("停止任务时强制终止远端注册进程异常，taskId={}", task != null ? task.getTaskId() : null, e);
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
            clearTaskStopped(taskId);
            
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
