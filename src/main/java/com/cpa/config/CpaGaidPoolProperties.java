package com.cpa.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GAID 池保底：查询 cpa.gaid 可用量，低于阈值则调用 CreateFarmTask 补货。
 */
@Data
@ConfigurationProperties(prefix = "cpa.gaid-pool-guard")
public class CpaGaidPoolProperties {

    /**
     * 关闭时不建数据源、不跑定时任务。
     */
    private boolean enabled = false;

    /**
     * 检查周期（cron），默认每 15 分钟。
     */
    private String scheduleCron = "0 */15 * * * ?";

    /** US 下 sdk 33/34 各自最低可用条数 */
    private int usMinPerSdk = 30_000;

    /** MX、BR 下 sdk 33/34 各自最低可用条数 */
    private int mxBrMinPerSdk = 10_000;

    /** 单次补货 CreateFarmTask 的 count（与线上一致用 100） */
    private int refillCount = 100;

    /** 一次低库存命中后，补货调用次数（默认 3 次） */
    private int refillBurstTimes = 3;

    /** 补货调用间隔（毫秒，默认 3 分钟） */
    private long refillBurstIntervalMillis = 3 * 60_000L;

    /** 同一国家+SDK 两次补货最小间隔（毫秒），避免短时间重复打接口 */
    private long cooldownMillis = 5 * 60_000L;

    private DataSourceSettings datasource = new DataSourceSettings();

    @Data
    public static class DataSourceSettings {
        private String url;
        private String username;
        private String password;
        private String driverClassName = "com.mysql.cj.jdbc.Driver";
    }
}
