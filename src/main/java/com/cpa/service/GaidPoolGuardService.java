package com.cpa.service;

import com.cpa.config.CpaGaidPoolProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定期查询 cpa.gaid 可用数量，低于阈值则触发 CreateFarmTask 补货（count 可配置，默认 100）。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "cpa.gaid-pool-guard", name = "enabled", havingValue = "true")
public class GaidPoolGuardService {

    private static final String SQL_COUNT = ""
            + "SELECT COUNT(*) FROM gaid WHERE gaid_country = ? AND `status` = '0' AND sdk = ?";

    private final CpaGaidPoolProperties properties;
    private final TaskSchedulerService taskSchedulerService;
    private final JdbcTemplate cpaGaidJdbcTemplate;

    private final ConcurrentHashMap<String, Long> lastRefillAtMillis = new ConcurrentHashMap<>();
    private final ScheduledExecutorService refillScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gaid-refill-burst");
        t.setDaemon(true);
        return t;
    });

    public GaidPoolGuardService(
            CpaGaidPoolProperties properties,
            TaskSchedulerService taskSchedulerService,
            @Qualifier("cpaGaidJdbcTemplate") JdbcTemplate cpaGaidJdbcTemplate) {
        this.properties = properties;
        this.taskSchedulerService = taskSchedulerService;
        this.cpaGaidJdbcTemplate = cpaGaidJdbcTemplate;
    }

    @Scheduled(cron = "${cpa.gaid-pool-guard.schedule-cron:0 */15 * * * ?}")
    public void checkAndRefillGaidPool() {
        try {
            for (String country : new String[] { "US", "MX", "BR" }) {
                int min = "US".equals(country) ? properties.getUsMinPerSdk() : properties.getMxBrMinPerSdk();
                for (int sdk : new int[] { 33, 34 }) {
                    long available = countAvailable(country, sdk);
                    if (available >= min) {
                        continue;
                    }
                    String key = country + "|" + sdk;
                    long now = System.currentTimeMillis();
                    Long last = lastRefillAtMillis.get(key);
                    if (last != null && now - last < properties.getCooldownMillis()) {
                        log.debug("GAID 保底跳过（冷却中） country={} sdk={} available={} min={}",
                                country, sdk, available, min);
                        continue;
                    }
                    log.warn("GAID 可用量低于阈值，触发补货 CreateFarmTask: country={} sdk={} available={} min={}",
                            country, sdk, available, min);
                    lastRefillAtMillis.put(key, now);
                    scheduleBurstRefill(country, sdk, available, min);
                }
            }
        } catch (Exception e) {
            log.error("GAID 池保底检查失败", e);
        }
    }

    private void scheduleBurstRefill(String country, int sdk, long available, int min) {
        int times = Math.max(1, properties.getRefillBurstTimes());
        long intervalMs = Math.max(0L, properties.getRefillBurstIntervalMillis());
        int count = properties.getRefillCount();
        for (int i = 0; i < times; i++) {
            final int idx = i + 1;
            long delay = intervalMs * i;
            refillScheduler.schedule(() -> {
                try {
                    log.warn("GAID 保底补货执行({}/{}): country={} sdk={} count={} available={} min={}",
                            idx, times, country, sdk, count, available, min);
                    taskSchedulerService.triggerGaidPoolRefill(country, sdk, count);
                } catch (Exception ex) {
                    log.error("GAID 保底补货执行失败({}/{}): country={} sdk={} count={}",
                            idx, times, country, sdk, count, ex);
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    private long countAvailable(String country, int sdk) {
        String cc = country.toUpperCase(Locale.ROOT);
        String sdkStr = String.valueOf(sdk);
        Long n = cpaGaidJdbcTemplate.queryForObject(SQL_COUNT, Long.class, cc, sdkStr);
        return n != null ? n : 0L;
    }

    @PreDestroy
    public void shutdown() {
        refillScheduler.shutdownNow();
    }
}
