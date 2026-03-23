package com.cpa.config;

import com.cpa.service.ApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * HTTP连接池监控定时任务
 * 定期记录 OkHttpClient 连接池状态，便于监控和调优
 */
@Slf4j
@Component
public class HttpConnectionPoolMonitor {

    @Autowired(required = false)
    private ApiService apiService;

    /**
     * 每5分钟记录一次HTTP连接池状态
     */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void logConnectionPoolStats() {
        try {
            if (apiService != null) {
                String stats = apiService.getConnectionPoolStats();
                log.info("HTTP连接池状态: {}", stats);
            } else {
                log.debug("ApiService未注入，跳过HTTP连接池状态记录");
            }
        } catch (Exception e) {
            log.error("记录HTTP连接池状态时出错", e);
        }
    }
}

