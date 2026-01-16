package com.cpa.controller;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据源监控控制器（HikariCP版本）
 * 用于实时查看连接池状态
 */
@RestController
@RequestMapping("/api/monitor")
public class DataSourceMonitorController {

    @Autowired
    private DataSource dataSource;

    /**
     * 获取数据源状态
     * 访问地址：http://localhost:8081/api/monitor/datasource-status
     */
    @GetMapping("/datasource-status")
    public Map<String, Object> getDataSourceStatus() {
        Map<String, Object> status = new HashMap<>();
        
        try {
            if (dataSource instanceof HikariDataSource) {
                HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
                HikariPoolMXBean poolMXBean = hikariDataSource.getHikariPoolMXBean();
                
                // 基本信息
                status.put("type", "HikariCP");
                status.put("jdbcUrl", hikariDataSource.getJdbcUrl());
                status.put("driverClassName", hikariDataSource.getDriverClassName());
                
                // 连接池配置
                status.put("maximumPoolSize", hikariDataSource.getMaximumPoolSize());
                status.put("minimumIdle", hikariDataSource.getMinimumIdle());
                status.put("connectionTimeout", hikariDataSource.getConnectionTimeout());
                
                // 实时状态 ⭐ 重点关注
                status.put("activeConnections", poolMXBean.getActiveConnections());     // 活跃连接数
                status.put("idleConnections", poolMXBean.getIdleConnections());         // 空闲连接数
                status.put("totalConnections", poolMXBean.getTotalConnections());       // 总连接数
                status.put("threadsAwaitingConnection", poolMXBean.getThreadsAwaitingConnection()); // 等待连接的线程数 ⚠️
                
                // 计算使用率
                int activeCount = poolMXBean.getActiveConnections();
                int maxSize = hikariDataSource.getMaximumPoolSize();
                double usageRate = maxSize > 0 ? (double) activeCount / maxSize * 100 : 0;
                status.put("usageRate", String.format("%.2f%%", usageRate));
                
                // 健康状态
                String health = "健康";
                if (usageRate > 90) {
                    health = "危险 - 连接池即将耗尽！";
                } else if (usageRate > 70) {
                    health = "警告 - 使用率过高";
                } else if (poolMXBean.getThreadsAwaitingConnection() > 0) {
                    health = "警告 - 有线程在等待连接";
                }
                status.put("health", health);
                
            } else {
                status.put("type", "Unknown");
                status.put("message", "当前数据源不是HikariCP");
            }
        } catch (Exception e) {
            status.put("error", e.getMessage());
        }
        
        return status;
    }
}

