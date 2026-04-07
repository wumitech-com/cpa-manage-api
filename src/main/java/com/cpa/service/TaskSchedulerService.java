package com.cpa.service;

import com.cpa.entity.TtAccountData;
import com.cpa.entity.TtAccountDataOutlook;
import com.cpa.repository.TtAccountRegisterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定时任务调度服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSchedulerService {

    private final DeviceService deviceService;
    private final ScriptService scriptService;
    private final RestTemplate restTemplate;
    private final TtAccountRegisterRepository ttAccountRegisterRepository;

    /** 定时任务 CreateFarmTask 单次 count，与「日均/100」调度粒度一致 */
    private static final int CREATE_FARM_BATCH_SIZE = 100;

    /** 按需补 GAID 建场时与线上一致：单次拉取规模 */
    private static final int ON_DEMAND_CREATE_FARM_COUNT = 200;

    /**
     * 五位权重依次对应 SDK：29、30、31、33、34（与线上一致）。
     * 按需补货等仍可能用 0:0:0:1:1；定时动态调度按 SDK 分别算频率后只发单一 proportion。
     */
    private static final String DEFAULT_SCHEDULED_PROPORTION = "0:0:0:1:1";

    /** 动态建场：按「昨日往前 7 日」各 SDK 消耗分别算每日次数与间隔 */
    private volatile LocalDate dynamicScheduleDate = null;
    private volatile int dynamicCallsPerDaySdk33 = 0;
    private volatile int dynamicIntervalMinutesSdk33 = Integer.MAX_VALUE;
    private volatile LocalDateTime lastCreateFarmTriggerAtSdk33 = null;
    private volatile int dynamicCallsPerDaySdk34 = 0;
    private volatile int dynamicIntervalMinutesSdk34 = Integer.MAX_VALUE;
    private volatile LocalDateTime lastCreateFarmTriggerAtSdk34 = null;

    /**
     * 换机侧报 GAID 池空时按需补建场：按「国家|SDK」冷却，避免同国同 SDK 短时间重复打。
     */
    private final ConcurrentHashMap<String, Long> lastOnDemandCreateFarmByKeyMillis = new ConcurrentHashMap<>();
    private static final long ONDEMAND_CREATE_FARM_COOLDOWN_MS = 3 * 60 * 1000L;

    /** 与错误信息里 country[XX] 一致 */
    private static final Pattern COUNTRY_BRACKET = Pattern.compile("(?i)country\\[([A-Za-z]{2})\\]");
    /** FetchGAID [MX-34]：国家 + SDK 版本 */
    private static final Pattern FETCH_GAID_COUNTRY_SDK = Pattern.compile("(?i)FetchGAID\\s*\\[([A-Za-z]{2})-(\\d+)\\]");
    /** sdk[34] */
    private static final Pattern SDK_BRACKET = Pattern.compile("(?i)sdk\\[(\\d+)\\]");

    /**
     * 每日定时任务 - 每天8点执行
     */
    // @Scheduled(cron = "0 0 8 * * ?")
    public void dailyTask() {
        log.info("开始执行每日定时任务，时间: {}", LocalDateTime.now());
        
        try {
            // 1. 批量注册任务
            // executeBatchRegisterTask();
            
            // 2. 批量刷视频任务（养号）
            executeBatchWatchVideoTask();
            
            // 3. 批量业务任务（养号完成的账号）
            executeBatchBusinessTask();
            
            // 4. 更新养号状态
            updateNurtureStatus();
            
            log.info("每日定时任务执行完成");
            
        } catch (Exception e) {
            log.error("每日定时任务执行失败", e);
        }
    }

    /**
     * 执行批量注册任务
     */
    private void executeBatchRegisterTask() {
        log.info("开始执行批量注册任务");
        
        try {
            // 获取需要注册的设备列表
            List<TtAccountDataOutlook> devicesNeedRegister = deviceService.getDevicesNeedRegister();
            
            if (devicesNeedRegister.isEmpty()) {
                log.info("没有需要注册的设备");
                return;
            }
            
            log.info("找到 {} 个需要注册的设备", devicesNeedRegister.size());
            
            // 提取设备ID
            List<Long> deviceIds = devicesNeedRegister.stream()
                    .map(TtAccountDataOutlook::getId)
                    .toList();
            
            // 批量执行注册脚本（限制每次最多50个）
            int batchSize = 50;
            for (int i = 0; i < deviceIds.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, deviceIds.size());
                List<Long> batchDeviceIds = deviceIds.subList(i, endIndex);
                
                log.info("执行注册脚本批次 {}/{}, 设备数量: {}", 
                        (i / batchSize + 1), 
                        (deviceIds.size() + batchSize - 1) / batchSize,
                        batchDeviceIds.size());
                
                Map<String, Object> result = scriptService.executeRegisterScript();
                log.info("注册脚本执行结果: {}", result.get("message"));
                
                // 由于脚本现在是长时间运行的，我们需要等待它完成
                String pid = (String) result.get("pid");
                if (pid != null) {
                    log.info("等待注册脚本完成，进程ID: {}", pid);
                    // 每5分钟检查一次状态
                    while (true) {
                        Thread.sleep(300000); // 5分钟
                        Map<String, Object> status = scriptService.getRegisterScriptStatus();
                        String scriptStatus = (String) ((Map<String, Object>) status.get("data")).get("status");
                        if ("completed".equals(scriptStatus) || "error".equals(scriptStatus) || "timeout".equals(scriptStatus)) {
                            log.info("注册脚本已结束，状态: {}", scriptStatus);
                            break;
                        }
                        log.debug("注册脚本运行中...");
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("批量注册任务执行失败", e);
        }
    }

    /**
     * 执行批量刷视频任务（养号）
     */
    private void executeBatchWatchVideoTask() {
        log.info("开始执行批量刷视频任务（养号）");
        
        try {
            // 获取需要养号的账号列表
            List<TtAccountData> accountsNeedNurture = deviceService.getAccountsNeedNurture();
            
            if (accountsNeedNurture.isEmpty()) {
                log.info("没有需要养号的账号");
                return;
            }
            
            log.info("找到 {} 个需要养号的账号", accountsNeedNurture.size());
            
            // 提取账号ID
            List<Long> accountIds = accountsNeedNurture.stream()
                    .map(TtAccountData::getId)
                    .toList();
            
            // 批量执行刷视频脚本（限制每次最多100个）
            int batchSize = 100;
            for (int i = 0; i < accountIds.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, accountIds.size());
                List<Long> batchAccountIds = accountIds.subList(i, endIndex);
                
                log.info("执行刷视频脚本批次 {}/{}, 账号数量: {}", 
                        (i / batchSize + 1), 
                        (accountIds.size() + batchSize - 1) / batchSize,
                        batchAccountIds.size());
                
                Map<String, Object> result = scriptService.executeWatchVideoScript(batchAccountIds);
                log.info("刷视频脚本执行结果: {}", result.get("message"));
                
                // 批次间间隔，避免过载
                if (endIndex < accountIds.size()) {
                    Thread.sleep(3000); // 3秒间隔
                }
            }
            
        } catch (Exception e) {
            log.error("批量刷视频任务执行失败", e);
        }
    }

    /**
     * 执行批量业务任务（养号完成的账号）
     */
    private void executeBatchBusinessTask() {
        log.info("开始执行批量业务任务");
        
        try {
            // 获取养号完成的账号列表
            List<TtAccountData> nurturedAccounts = deviceService.getNurturedAccounts();
            
            if (nurturedAccounts.isEmpty()) {
                log.info("没有养号完成的账号");
                return;
            }
            
            log.info("找到 {} 个养号完成的账号", nurturedAccounts.size());
            
            // 提取账号ID
            List<Long> accountIds = nurturedAccounts.stream()
                    .map(TtAccountData::getId)
                    .toList();
            
            // 这里可以执行其他业务脚本，比如：
            // - 批量关注脚本
            // - 批量上传视频脚本
            // - 其他运营脚本
            
            // 示例：执行批量关注任务（随机关注一些用户）
            executeBatchFollowTask(accountIds);
            
        } catch (Exception e) {
            log.error("批量业务任务执行失败", e);
        }
    }

    /**
     * 执行批量关注任务
     */
    private void executeBatchFollowTask(List<Long> accountIds) {
        log.info("开始执行批量关注任务，账号数量: {}", accountIds.size());
        
        try {
            // 这里可以配置要关注的目标用户列表
            String[] targetUsernames = {"user1", "user2", "user3", "user4", "user5"};
            
            // 随机选择目标用户
            String targetUsername = targetUsernames[(int) (Math.random() * targetUsernames.length)];
            
            // 限制每次最多关注50个账号
            int batchSize = 50;
            for (int i = 0; i < accountIds.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, accountIds.size());
                List<Long> batchAccountIds = accountIds.subList(i, endIndex);
                
                log.info("执行关注脚本批次 {}/{}, 账号数量: {}, 目标用户: {}", 
                        (i / batchSize + 1), 
                        (accountIds.size() + batchSize - 1) / batchSize,
                        batchAccountIds.size(),
                        targetUsername);
                
                Map<String, Object> result = scriptService.executeFollowScript(batchAccountIds, targetUsername);
                log.info("关注脚本执行结果: {}", result.get("message"));
                
                // 批次间间隔，避免过载
                if (endIndex < accountIds.size()) {
                    Thread.sleep(2000); // 2秒间隔
                }
            }
            
        } catch (Exception e) {
            log.error("批量关注任务执行失败", e);
        }
    }

    /**
     * 更新养号状态
     */
    private void updateNurtureStatus() {
        log.info("开始更新养号状态");
        
        try {
            // 更新养号状态（7天以上认为养号完成）
            boolean result = deviceService.batchUpdateNurtureStatus(7);
            log.info("更新养号状态结果: {}", result ? "成功" : "失败");
            
        } catch (Exception e) {
            log.error("更新养号状态失败", e);
        }
    }

    /**
     * 手动触发每日任务
     */
    public Map<String, Object> manualTriggerDailyTask() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("手动触发每日任务");
            
            // 异步执行每日任务
            new Thread(() -> {
                try {
                    dailyTask();
                } catch (Exception e) {
                    log.error("手动触发每日任务失败", e);
                }
            }).start();
            
            result.put("success", true);
            result.put("message", "每日任务已触发，请稍后查看执行结果");
            
        } catch (Exception e) {
            log.error("手动触发每日任务失败", e);
            result.put("success", false);
            result.put("message", "触发失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取任务调度状态
     */
    public Map<String, Object> getTaskScheduleStatus() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 获取各阶段设备数量
            List<TtAccountDataOutlook> devicesNeedRegister = deviceService.getDevicesNeedRegister();
            List<TtAccountData> accountsNeedNurture = deviceService.getAccountsNeedNurture();
            List<TtAccountData> nurturedAccounts = deviceService.getNurturedAccounts();
            
            Map<String, Object> status = new HashMap<>();
            status.put("devicesNeedRegister", devicesNeedRegister.size());
            status.put("accountsNeedNurture", accountsNeedNurture.size());
            status.put("nurturedAccounts", nurturedAccounts.size());
            status.put("lastUpdateTime", LocalDateTime.now());
            
            result.put("success", true);
            result.put("data", status);
            
        } catch (Exception e) {
            log.error("获取任务调度状态失败", e);
            result.put("success", false);
            result.put("message", "获取状态失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 每小时检查任务状态（可选）
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void hourlyTaskCheck() {
        log.debug("执行每小时任务检查，时间: {}", LocalDateTime.now());
        
        try {
            // 这里可以添加一些轻量级的检查任务
            // 比如检查设备状态、清理过期数据等
            
        } catch (Exception e) {
            log.error("每小时任务检查失败", e);
        }
    }

    /**
     * @param proportion 五位权重，顺序为 SDK 29、30、31、33、34
     */
    private void triggerCreateFarmTask(String countryCode, String proportion, int count) {
        String cc = normalizeCountryCode(countryCode);
        String prop = (proportion == null || proportion.isEmpty()) ? DEFAULT_SCHEDULED_PROPORTION : proportion;
        String url = String.format(Locale.ROOT,
                "https://cpa-api.wumitech.com/CreateFarmTask?proportion=%s&country=%s&priority=10&count=%d",
                prop, cc, count);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, null, String.class);
            log.info("触发CreateFarmTask完成，country={}, proportion={}, count={}, status={}, body={}",
                    cc, prop, count, response.getStatusCode().value(), response.getBody());
        } catch (Exception e) {
            log.error("触发CreateFarmTask失败，country={}, url={}", cc, url, e);
        }
    }

    /**
     * GAID 池保底补货：按国家 + SDK 单独调用 CreateFarmTask（proportion 与 {@link #proportionForSdk(int)} 一致）。
     */
    public void triggerGaidPoolRefill(String country, int sdk, int count) {
        triggerCreateFarmTask(country, proportionForSdk(sdk), count);
    }

    private static String normalizeCountryCode(String countryCode) {
        if (countryCode == null || countryCode.isEmpty()) {
            return "US";
        }
        String t = countryCode.trim();
        if (t.length() != 2) {
            return "US";
        }
        return t.toUpperCase(Locale.ROOT);
    }

    /**
     * 从 TTFarmResetPhone / FetchGAID 错误文案中解析国家，供 CreateFarmTask 使用。
     */
    static String extractCountryFromGaidErrorDetail(String detail) {
        if (detail == null || detail.isEmpty()) {
            return "US";
        }
        Matcher m1 = COUNTRY_BRACKET.matcher(detail);
        if (m1.find()) {
            return normalizeCountryCode(m1.group(1));
        }
        Matcher m2 = FETCH_GAID_COUNTRY_SDK.matcher(detail);
        if (m2.find()) {
            return normalizeCountryCode(m2.group(1));
        }
        return "US";
    }

    /**
     * 从错误文案解析目标 SDK（29/30/31/33/34），供 proportion 使用。
     */
    static int extractSdkFromGaidErrorDetail(String detail) {
        if (detail == null || detail.isEmpty()) {
            return -1;
        }
        Matcher m0 = SDK_BRACKET.matcher(detail);
        if (m0.find()) {
            try {
                return Integer.parseInt(m0.group(1));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        Matcher m1 = FETCH_GAID_COUNTRY_SDK.matcher(detail);
        if (m1.find()) {
            try {
                return Integer.parseInt(m1.group(2));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return -1;
    }

    /**
     * 五位 proportion 对应 29、30、31、33、34；单一 SDK 为 1，其余 0。
     */
    static String proportionForSdk(int sdk) {
        switch (sdk) {
            case 29:
                return "1:0:0:0:0";
            case 30:
                return "0:1:0:0:0";
            case 31:
                return "0:0:1:0:0";
            case 33:
                return "0:0:0:1:0";
            case 34:
                return "0:0:0:0:1";
            default:
                return DEFAULT_SCHEDULED_PROPORTION;
        }
    }

    /**
     * 换机/Reset 返回 GAID 池无可用记录时，按需触发一次建场（country + proportion 按 SDK + count=200，按「国|SDK」冷却）。
     */
    public void requestCreateFarmTaskWhenGaidPoolLikelyEmpty(String detail) {
        if (detail == null || !isGaidPoolExhaustedMessage(detail)) {
            return;
        }
        String country = extractCountryFromGaidErrorDetail(detail);
        int sdk = extractSdkFromGaidErrorDetail(detail);
        String proportion = proportionForSdk(sdk);
        String cooldownKey = country + "|" + (sdk > 0 ? sdk : "default");
        long now = System.currentTimeMillis();
        Long last = lastOnDemandCreateFarmByKeyMillis.get(cooldownKey);
        if (last != null && now - last < ONDEMAND_CREATE_FARM_COOLDOWN_MS) {
            log.debug("按需 CreateFarmTask 跳过（冷却中） key={} detail={}", cooldownKey, abbrev(detail, 240));
            return;
        }
        lastOnDemandCreateFarmByKeyMillis.put(cooldownKey, now);
        log.warn("检测到 GAID 池不足，按需触发 CreateFarmTask。country={}, sdk={}, proportion={}, detail={}",
                country, sdk > 0 ? sdk : "(默认" + DEFAULT_SCHEDULED_PROPORTION + ")", proportion, abbrev(detail, 800));
        triggerCreateFarmTask(country, proportion, ON_DEMAND_CREATE_FARM_COUNT);
    }

    private static boolean isGaidPoolExhaustedMessage(String s) {
        String u = s.toUpperCase(Locale.ROOT);
        if (u.contains("FETCHGAID")) {
            return true;
        }
        return u.contains("GAID NOT FOUND") || u.contains("DAL.FETCHGAID");
    }

    private static String abbrev(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }

    /**
     * 动态频率触发建场任务：按 SDK 33、34「拆开」各算各的频率，但每次 HTTP 只带单一 proportion。
     * <ul>
     *   <li>库字段 android_version 仅为 13 / 14，分别对应业务 SDK API 33 / 34（不是把 33、34 存在 android_version 里）。</li>
     *   <li>近 7 日（不含今天）按 13、14 条数估日均，再分别得 callsPerDay 与 intervalMinutes。</li>
     *   <li>触发时 {@link #proportionForSdk(int)} → 仅 33 或仅 34 一条权重，从不混成 0:0:0:1:1。</li>
     *   <li>非 13/14 的存量行（含 NULL）计入 other，按 13/14 已有比例摊到两侧日均，避免漏统计。</li>
     * </ul>
     */
    @Scheduled(cron = "0 * * * * ?")
    public void triggerCreateFarmTaskByYesterdayRegister() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        if (dynamicScheduleDate == null || !dynamicScheduleDate.equals(today)) {
            LocalDateTime start = today.minusDays(7).atStartOfDay();
            LocalDateTime end = today.minusDays(1).atTime(LocalTime.MAX);

            long total;
            long raw33;
            long raw34;
            try {
                total = ttAccountRegisterRepository.countTodayRegister(start, end);
                raw33 = ttAccountRegisterRepository.countCreatedInRangeForSdkApi33(start, end);
                raw34 = ttAccountRegisterRepository.countCreatedInRangeForSdkApi34(start, end);
            } catch (Exception e) {
                log.error("读取近7日注册量(按SDK)失败，start={}, end={}", start, end, e);
                return;
            }

            long other = Math.max(0L, total - raw33 - raw34);
            double eff33 = raw33;
            double eff34 = raw34;
            if (raw33 + raw34 > 0) {
                double sum = raw33 + raw34;
                eff33 = raw33 + other * (raw33 / sum);
                eff34 = raw34 + other * (raw34 / sum);
            } else if (other > 0) {
                eff33 = other / 2.0;
                eff34 = other / 2.0;
            }

            double avgDaily33 = eff33 / 7.0;
            double avgDaily34 = eff34 / 7.0;

            applyDynamicPlanForSdk(avgDaily33, true);
            applyDynamicPlanForSdk(avgDaily34, false);

            dynamicScheduleDate = today;
            lastCreateFarmTriggerAtSdk33 = null;
            lastCreateFarmTriggerAtSdk34 = null;

            log.info("CreateFarmTask 动态频率已更新(7日均按SDK): total={}, raw33={}, raw34={}, other={}, "
                            + "avgDaily33={}, avgDaily34={}, callsDay33={}, intervalMin33={}, callsDay34={}, intervalMin34={}",
                    total, raw33, raw34, other,
                    String.format(Locale.ROOT, "%.2f", avgDaily33),
                    String.format(Locale.ROOT, "%.2f", avgDaily34),
                    dynamicCallsPerDaySdk33, dynamicIntervalMinutesSdk33,
                    dynamicCallsPerDaySdk34, dynamicIntervalMinutesSdk34);
        }

        fireCreateFarmIfDue(now, 33, dynamicCallsPerDaySdk33, dynamicIntervalMinutesSdk33,
                () -> lastCreateFarmTriggerAtSdk33,
                t -> lastCreateFarmTriggerAtSdk33 = t);
        fireCreateFarmIfDue(now, 34, dynamicCallsPerDaySdk34, dynamicIntervalMinutesSdk34,
                () -> lastCreateFarmTriggerAtSdk34,
                t -> lastCreateFarmTriggerAtSdk34 = t);
    }

    private void applyDynamicPlanForSdk(double avgDaily, boolean sdk33) {
        if (avgDaily <= 0) {
            if (sdk33) {
                dynamicCallsPerDaySdk33 = 0;
                dynamicIntervalMinutesSdk33 = Integer.MAX_VALUE;
            } else {
                dynamicCallsPerDaySdk34 = 0;
                dynamicIntervalMinutesSdk34 = Integer.MAX_VALUE;
            }
            return;
        }
        int calls = (int) Math.ceil(avgDaily / CREATE_FARM_BATCH_SIZE);
        int interval = Math.max(1, (int) Math.ceil(1440.0 / calls));
        if (sdk33) {
            dynamicCallsPerDaySdk33 = calls;
            dynamicIntervalMinutesSdk33 = interval;
        } else {
            dynamicCallsPerDaySdk34 = calls;
            dynamicIntervalMinutesSdk34 = interval;
        }
    }

    private void fireCreateFarmIfDue(
            LocalDateTime now,
            int sdk,
            int callsPerDay,
            int intervalMinutes,
            Supplier<LocalDateTime> lastAtGetter,
            Consumer<LocalDateTime> lastAtSetter) {
        if (callsPerDay <= 0) {
            return;
        }
        LocalDateTime last = lastAtGetter.get();
        if (last == null || !last.plusMinutes(intervalMinutes).isAfter(now)) {
            triggerCreateFarmTask("US", proportionForSdk(sdk), CREATE_FARM_BATCH_SIZE);
            lastAtSetter.accept(now);
        }
    }
}
