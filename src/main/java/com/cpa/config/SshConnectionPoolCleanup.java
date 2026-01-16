package com.cpa.config;

import com.cpa.util.SshConnectionPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SSH连接池清理定时任务
 */
@Slf4j
@Component
public class SshConnectionPoolCleanup {

    /**
     * 每5分钟清理一次空闲连接
     */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void cleanupIdleConnections() {
        try {
            int beforeSize = SshConnectionPool.getPoolSize();
            SshConnectionPool.cleanupIdleConnections();
            int afterSize = SshConnectionPool.getPoolSize();
            
            if (beforeSize != afterSize) {
                log.info("SSH连接池清理完成: {} -> {} (清理了{}个连接)", 
                    beforeSize, afterSize, beforeSize - afterSize);
            } else {
                log.debug("SSH连接池清理完成: 当前连接数 {}", afterSize);
            }
            
            // 记录连接池统计信息
            log.info(SshConnectionPool.getConnectionPoolStats());
            SshConnectionPool.logConnectionPoolStats();
        } catch (Exception e) {
            log.error("清理SSH连接池时出错", e);
        }
    }
}

