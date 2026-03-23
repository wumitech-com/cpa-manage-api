package com.cpa.service;

import com.cpa.repository.TtAccountDataOutlookRepository;
import com.cpa.repository.TtAccountDataRepository;
import com.cpa.repository.TtAccountRegisterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据统计服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final TtAccountDataOutlookRepository outlookRepository;
    private final TtAccountDataRepository accountRepository;
    private final TtAccountRegisterRepository ttAccountRegisterRepository;
    private final com.cpa.repository.TtRetentionRecordRepository ttRetentionRecordRepository;

    /**
     * 获取设备池统计信息
     */
    public Map<String, Object> getDevicePoolStatistics() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 总设备数量
            long totalDevices = outlookRepository.selectCount(null);
            
            // 各状态设备数量
            List<Map<String, Object>> statusCounts = outlookRepository.countByStatus();
            
            // 需要注册的设备数量
            List<com.cpa.entity.TtAccountDataOutlook> devicesNeedRegister = outlookRepository.findDevicesNeedRegister();
            int needRegisterCount = devicesNeedRegister.size();
            
            // 按国家统计
            List<Object> countryStats = getCountryStatistics("outlook");
            
            // 按包名统计
            List<Object> pkgStats = getPkgStatistics("outlook");
            
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("totalDevices", totalDevices);
            statistics.put("statusCounts", statusCounts);
            statistics.put("needRegisterCount", needRegisterCount);
            statistics.put("countryStats", countryStats);
            statistics.put("pkgStats", pkgStats);
            statistics.put("lastUpdateTime", LocalDateTime.now());
            
            result.put("success", true);
            result.put("data", statistics);
            
        } catch (Exception e) {
            log.error("获取设备池统计信息失败", e);
            result.put("success", false);
            result.put("message", "获取统计信息失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取账号库统计信息
     */
    public Map<String, Object> getAccountLibraryStatistics() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 总账号数量
            long totalAccounts = accountRepository.selectCount(null);
            
            // 各状态账号数量
            List<Map<String, Object>> statusCounts = accountRepository.countByStatus();
            
            // 养号进度统计
            List<Map<String, Object>> nurtureStats = accountRepository.countByNurtureStatus();
            
            // 刷视频天数分布
            List<Map<String, Object>> videoDaysStats = accountRepository.countVideoDaysDistribution();
            
            // 按国家统计
            List<Object> countryStats = getCountryStatistics("account");
            
            // 按包名统计
            List<Object> pkgStats = getPkgStatistics("account");
            
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("totalAccounts", totalAccounts);
            statistics.put("statusCounts", statusCounts);
            statistics.put("nurtureStats", nurtureStats);
            statistics.put("videoDaysStats", videoDaysStats);
            statistics.put("countryStats", countryStats);
            statistics.put("pkgStats", pkgStats);
            statistics.put("lastUpdateTime", LocalDateTime.now());
            
            result.put("success", true);
            result.put("data", statistics);
            
        } catch (Exception e) {
            log.error("获取账号库统计信息失败", e);
            result.put("success", false);
            result.put("message", "获取统计信息失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取整体统计概览
     */
    public Map<String, Object> getOverallStatistics() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 设备池统计
            Map<String, Object> devicePoolStats = getDevicePoolStatistics();
            @SuppressWarnings("unchecked")
            Map<String, Object> devicePoolData = (Map<String, Object>) devicePoolStats.get("data");
            
            // 账号库统计
            Map<String, Object> accountLibraryStats = getAccountLibraryStatistics();
            @SuppressWarnings("unchecked")
            Map<String, Object> accountLibraryData = (Map<String, Object>) accountLibraryStats.get("data");
            
            // 计算整体指标
            long totalDevices = (Long) devicePoolData.get("totalDevices");
            long totalAccounts = (Long) accountLibraryData.get("totalAccounts");
            int needRegisterCount = (Integer) devicePoolData.get("needRegisterCount");
            
            List<com.cpa.entity.TtAccountData> accountsNeedNurture = accountRepository.findAccountsNeedNurture();
            int needNurtureCount = accountsNeedNurture.size();
            
            List<com.cpa.entity.TtAccountData> nurturedAccounts = accountRepository.findNurturedAccounts();
            int nurturedCount = nurturedAccounts.size();
            
            // 注册转化率
            double registerRate = totalDevices > 0 ? (double) totalAccounts / (totalDevices + totalAccounts) * 100 : 0;
            
            // 养号完成率
            double nurtureRate = totalAccounts > 0 ? (double) nurturedCount / totalAccounts * 100 : 0;
            
            Map<String, Object> overview = new HashMap<>();
            overview.put("totalDevices", totalDevices);
            overview.put("totalAccounts", totalAccounts);
            overview.put("needRegisterCount", needRegisterCount);
            overview.put("needNurtureCount", needNurtureCount);
            overview.put("nurturedCount", nurturedCount);
            overview.put("registerRate", Math.round(registerRate * 100.0) / 100.0);
            overview.put("nurtureRate", Math.round(nurtureRate * 100.0) / 100.0);
            overview.put("devicePoolStats", devicePoolData);
            overview.put("accountLibraryStats", accountLibraryData);
            overview.put("lastUpdateTime", LocalDateTime.now());
            
            result.put("success", true);
            result.put("data", overview);
            
        } catch (Exception e) {
            log.error("获取整体统计概览失败", e);
            result.put("success", false);
            result.put("message", "获取统计概览失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取趋势统计（最近7天）
     */
    public Map<String, Object> getTrendStatistics() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            LocalDate today = LocalDate.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            
            Map<String, Object> trends = new HashMap<>();
            
            // 最近7天的数据趋势
            for (int i = 6; i >= 0; i--) {
                LocalDate date = today.minusDays(i);
                String dateStr = date.format(formatter);
                
                // 这里可以查询每天的数据变化
                // 由于现有表结构没有创建时间索引，这里简化处理
                Map<String, Object> dayData = new HashMap<>();
                dayData.put("date", dateStr);
                dayData.put("newDevices", 0); // 需要根据实际数据计算
                dayData.put("newAccounts", 0); // 需要根据实际数据计算
                dayData.put("nurturedAccounts", 0); // 需要根据实际数据计算
                
                trends.put(dateStr, dayData);
            }
            
            result.put("success", true);
            result.put("data", trends);
            
        } catch (Exception e) {
            log.error("获取趋势统计失败", e);
            result.put("success", false);
            result.put("message", "获取趋势统计失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取国家统计信息
     */
    private List<Object> getCountryStatistics(String tableType) {
        // 这里需要根据实际的表类型执行不同的查询
        // 简化处理，返回空列表
        return List.of();
    }

    /**
     * 获取包名统计信息
     */
    private List<Object> getPkgStatistics(String tableType) {
        // 这里需要根据实际的表类型执行不同的查询
        // 简化处理，返回空列表
        return List.of();
    }

    /**
     * 获取脚本执行统计
     */
    public Map<String, Object> getScriptExecutionStatistics() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 这里可以从Redis或日志中获取脚本执行统计信息
            Map<String, Object> scriptStats = new HashMap<>();
            scriptStats.put("createPhoneExecutions", 0);
            scriptStats.put("registerExecutions", 0);
            scriptStats.put("followExecutions", 0);
            scriptStats.put("watchVideoExecutions", 0);
            scriptStats.put("totalExecutions", 0);
            scriptStats.put("successRate", 0.0);
            scriptStats.put("lastExecutionTime", LocalDateTime.now());
            
            result.put("success", true);
            result.put("data", scriptStats);
            
        } catch (Exception e) {
            log.error("获取脚本执行统计失败", e);
            result.put("success", false);
            result.put("message", "获取脚本执行统计失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取设备利用率统计
     */
    public Map<String, Object> getDeviceUtilizationStatistics() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 计算设备利用率
            long totalDevices = outlookRepository.selectCount(null);
            long totalAccounts = accountRepository.selectCount(null);
            
            List<com.cpa.entity.TtAccountData> accountsNeedNurture = accountRepository.findAccountsNeedNurture();
            int activeAccounts = accountsNeedNurture.size();
            
            double utilizationRate = totalDevices > 0 ? (double) activeAccounts / totalDevices * 100 : 0;
            
            Map<String, Object> utilization = new HashMap<>();
            utilization.put("totalDevices", totalDevices);
            utilization.put("totalAccounts", totalAccounts);
            utilization.put("activeAccounts", activeAccounts);
            utilization.put("utilizationRate", Math.round(utilizationRate * 100.0) / 100.0);
            utilization.put("lastUpdateTime", LocalDateTime.now());
            
            result.put("success", true);
            result.put("data", utilization);
            
        } catch (Exception e) {
            log.error("获取设备利用率统计失败", e);
            result.put("success", false);
            result.put("message", "获取设备利用率统计失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取每日注册脚本统计（基于 tt_account_register）
     * 今日注册数(username not null)、今日2FA成功数、今日留存做2FA数、最近7天趋势
     * @param queryDate 可选，查询基准日，不传则默认今天
     */
    public Map<String, Object> getDailyRegisterStatistics(LocalDate queryDate) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            LocalDate today = queryDate != null ? queryDate : LocalDate.now();
            LocalDateTime todayStart = today.atStartOfDay();
            LocalDateTime todayEnd = today.atTime(23, 59, 59);
            LocalDateTime weekStart = today.minusDays(6).atStartOfDay();
            
            // 今日：注册数、2FA成功数、留存做2FA数
            long todayRegister = ttAccountRegisterRepository.countTodayRegister(todayStart, todayEnd);
            long today2faSuccess = ttAccountRegisterRepository.countToday2faSuccess(todayStart, todayEnd);
            long todayRegisterSuccess = ttAccountRegisterRepository.countTodayRegisterSuccess(todayStart, todayEnd);
            long todayNeedRetention = ttAccountRegisterRepository.countTodayNeedRetention(todayStart, todayEnd);
            double todayRegisterSuccessRate = todayRegister > 0
                    ? Math.round((double) todayRegisterSuccess / todayRegister * 10000.0) / 100.0
                    : 0;
            double today2faSetupSuccessRate = todayRegisterSuccess > 0
                    ? Math.round((double) today2faSuccess / todayRegisterSuccess * 10000.0) / 100.0
                    : 0;
            
            // 最近7天趋势（注册 / 2FA / 留存 / 流量）
            List<Map<String, Object>> dailyTrend = new ArrayList<>();
            List<Map<String, Object>> rawTrend = ttAccountRegisterRepository.countDailyStats(weekStart);
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            
            for (int i = 6; i >= 0; i--) {
                LocalDate d = today.minusDays(i);
                String dateStr = d.format(fmt);
                Map<String, Object> dayData = new HashMap<>();
                dayData.put("date", dateStr);
                dayData.put("label", i == 0 ? "今天" : (i == 1 ? "昨天" : d.getMonthValue() + "/" + d.getDayOfMonth()));
                
                Map<String, Object> found = rawTrend.stream()
                    .filter(m -> {
                        Object v = m.get("stat_date");
                        if (v == null) v = m.get("statDate");
                        return dateStr.equals(String.valueOf(v));
                    })
                    .findFirst().orElse(null);
                
                long reg = found != null ? getLong(found, "register_cnt") : 0;
                long twofa = found != null ? getLong(found, "twofa_cnt") : 0;
                long ret = found != null ? getLong(found, "retention_cnt") : 0;
                double rate = reg > 0 ? Math.round((double) twofa / reg * 10000.0) / 100.0 : 0;
                
                dayData.put("register", reg);
                dayData.put("twofa", twofa);
                dayData.put("retention", ret);
                dayData.put("twofaRate", rate);

                // 当天流量统计：按当天 2FA 成功账号的 trafficData 汇总
                LocalDateTime dStart = d.atStartOfDay();
                LocalDateTime dEnd = d.atTime(23, 59, 59);
                List<com.cpa.entity.TtAccountRegister> dayTwofaList =
                        ttAccountRegisterRepository.list2faSuccessByDate(dStart, dEnd);
                double dayTraffic = 0.0;
                if (dayTwofaList != null) {
                    for (com.cpa.entity.TtAccountRegister ar : dayTwofaList) {
                        dayTraffic += parseTraffic(ar.getTrafficData());
                    }
                }
                double dayAvg = twofa > 0 ? dayTraffic / twofa : 0.0;
                dayData.put("trafficTotal", Math.round(dayTraffic * 100.0) / 100.0);
                dayData.put("trafficAvg", Math.round(dayAvg * 100.0) / 100.0);
                dailyTrend.add(dayData);
            }

            // 今日 2FA 成功账号的详情分布
            LocalDateTime twofaStart = today.atStartOfDay();
            LocalDateTime twofaEnd = today.atTime(23, 59, 59);
            List<com.cpa.entity.TtAccountRegister> twofaList =
                    ttAccountRegisterRepository.list2faSuccessByDate(twofaStart, twofaEnd);

            Map<String, Long> androidDist = new HashMap<>();
            Map<String, Long> behaviorDist = new HashMap<>();
            Map<String, Long> tiktokDist = new HashMap<>();
            Map<String, Long> countryDist = new HashMap<>();
            Map<String, Long> phoneServerIpDist = new HashMap<>();
            double totalTraffic = 0.0;

            for (com.cpa.entity.TtAccountRegister ar : twofaList) {
                inc(androidDist, normalize(ar.getAndroidVersion(), "未知"));
                // 按数据库原始行为值统计，不再压缩成 skip/normal 两类
                inc(behaviorDist, normalize(ar.getBehavior(), "未知"));
                inc(tiktokDist, normalize(ar.getTiktokVersion(), "未知"));
                inc(countryDist, normalize(ar.getCountry(), "未知"));
                inc(phoneServerIpDist, normalize(ar.getPhoneServerIp(), "未知"));
                totalTraffic += parseTraffic(ar.getTrafficData());
            }

            Map<String, Object> twofaDetail = new HashMap<>();
            twofaDetail.put("total2faSuccess", today2faSuccess);
            twofaDetail.put("androidVersionDist", toDistList(androidDist));
            twofaDetail.put("behaviorDist", toDistList(behaviorDist));
            twofaDetail.put("tiktokVersionDist", toDistList(tiktokDist));
            twofaDetail.put("countryDist", toDistList(countryDist));
            twofaDetail.put("phoneServerIpDist", toDistList(phoneServerIpDist));
            twofaDetail.put("trafficTotal", Math.round(totalTraffic * 100.0) / 100.0);
            double avg = today2faSuccess > 0 ? totalTraffic / today2faSuccess : 0.0;
            twofaDetail.put("trafficAvgPerSuccess", Math.round(avg * 100.0) / 100.0);

            // 服务器每小时 2FA 成功明细
            List<Map<String, Object>> byServerHour = ttAccountRegisterRepository.count2faByServerAndHour(twofaStart, twofaEnd);
            Map<String, long[]> hourlyMap = new LinkedHashMap<>();
            for (Map<String, Object> row : byServerHour) {
                String serverIp = normalize(String.valueOf(row.get("server_ip")), "未知");
                int hour = (int) getLong(row, "hour_of_day");
                long cnt = getLong(row, "cnt");
                if (hour < 0 || hour > 23) {
                    continue;
                }
                long[] arr = hourlyMap.computeIfAbsent(serverIp, k -> new long[24]);
                arr[hour] += cnt;
            }
            List<Map<String, Object>> serverHourlyList = new ArrayList<>();
            for (Map.Entry<String, long[]> entry : hourlyMap.entrySet()) {
                String serverIp = entry.getKey();
                long[] arr = entry.getValue();
                long totalCnt = 0;
                List<Map<String, Object>> hourly = new ArrayList<>();
                for (int h = 0; h < 24; h++) {
                    long c = arr[h];
                    totalCnt += c;
                    Map<String, Object> hourItem = new HashMap<>();
                    hourItem.put("hour", String.format("%02d", h));
                    hourItem.put("count", c);
                    hourly.add(hourItem);
                }
                Map<String, Object> item = new HashMap<>();
                item.put("serverIp", serverIp);
                item.put("total", totalCnt);
                item.put("hourly", hourly);
                serverHourlyList.add(item);
            }
            serverHourlyList.sort((a, b) -> Long.compare(
                    ((Number) b.getOrDefault("total", 0L)).longValue(),
                    ((Number) a.getOrDefault("total", 0L)).longValue()
            ));
            twofaDetail.put("serverHourly2fa", serverHourlyList);

            Map<String, Object> data = new HashMap<>();
            data.put("todayRegister", todayRegister);
            data.put("todayRegisterSuccess", todayRegisterSuccess);
            data.put("today2faSuccess", today2faSuccess);
            data.put("todayNeedRetention", todayNeedRetention);
            data.put("todayRegisterSuccessRate", todayRegisterSuccessRate);
            data.put("today2faSetupSuccessRate", today2faSetupSuccessRate);
            data.put("dailyTrend", dailyTrend);
            data.put("twofaDetail", twofaDetail);
            data.put("lastUpdateTime", LocalDateTime.now());
            
            result.put("success", true);
            result.put("data", data);
            
        } catch (Exception e) {
            log.error("获取每日注册统计失败", e);
            result.put("success", false);
            result.put("message", "获取统计失败: " + e.getMessage());
        }
        
        return result;
    }
    
    private long getLong(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    private void inc(Map<String, Long> map, String key) {
        map.put(key, map.getOrDefault(key, 0L) + 1L);
    }

    private String normalize(String v, String def) {
        if (v == null || v.trim().isEmpty()) return def;
        return v.trim();
    }

    /**
     * trafficData 解析：从字符串里提取第一个数字，作为流量单位（具体单位依赖上游定义）
     */
    private double parseTraffic(String trafficData) {
        if (trafficData == null) return 0.0;
        String s = trafficData.trim();
        if (s.isEmpty()) return 0.0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(s);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0;
    }

    private List<Map<String, Object>> toDistList(Map<String, Long> src) {
        List<Map<String, Object>> list = new ArrayList<>();
        long total = src.values().stream().mapToLong(Long::longValue).sum();
        src.forEach((k, v) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("value", k);
            item.put("count", v);
            double pct = total > 0 ? Math.round((double) v / total * 10000.0) / 100.0 : 0.0;
            item.put("percent", pct);
            list.add(item);
        });
        list.sort((a, b) -> Long.compare(
                (Long) b.getOrDefault("count", 0L),
                (Long) a.getOrDefault("count", 0L)
        ));
        return list;
    }

    private List<Map<String, Object>> toRateDistList(Map<String, Long> blockedMap, Map<String, Long> totalMap) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Long> e : totalMap.entrySet()) {
            String key = e.getKey();
            long total = e.getValue() != null ? e.getValue() : 0L;
            if (total <= 0) {
                continue;
            }
            long blocked = blockedMap.getOrDefault(key, 0L);
            double rate = Math.round((double) blocked / total * 10000.0) / 100.0;
            Map<String, Object> item = new HashMap<>();
            item.put("value", key);
            item.put("count", blocked);
            item.put("totalCount", total);
            item.put("percent", rate);
            list.add(item);
        }
        list.sort((a, b) -> Long.compare(
                ((Number) b.getOrDefault("count", 0L)).longValue(),
                ((Number) a.getOrDefault("count", 0L)).longValue()
        ));
        return list;
    }

    /**
     * 封号率统计：次日、3天、7天
     * 基准日 = 2FA设置成功那天（created_at），统计该批账号中 block_time 不为空的比例
     * @param queryDate 可选，查询基准日，不传则默认今天。次日/3天/7天均相对于此日计算
     */
    public Map<String, Object> getBlockRateStatistics(LocalDate queryDate) {
        Map<String, Object> result = new HashMap<>();
        try {
            LocalDate today = queryDate != null ? queryDate : LocalDate.now();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

            Map<String, Object> data = new HashMap<>();
            Map<String, Long> blockedAndroidDist = new HashMap<>();
            Map<String, Long> blockedBehaviorDist = new HashMap<>();
            Map<String, Long> blockedTiktokDist = new HashMap<>();
            Map<String, Long> blockedCountryDist = new HashMap<>();
            Map<String, Long> blockedServerIpDist = new HashMap<>();
            Map<String, Map<String, Object>> blockedDetailByPeriod = new HashMap<>();
            data.put("queryDate", today.format(fmt));
            data.put("lastUpdateTime", LocalDateTime.now());

            for (int daysAgo : new int[]{1, 3, 7}) {
                LocalDate baseDate = today.minusDays(daysAgo);
                LocalDateTime start = baseDate.atStartOfDay();
                LocalDateTime end = baseDate.atTime(23, 59, 59);

                long total = ttAccountRegisterRepository.count2faSuccessByDate(start, end);
                long blocked = ttAccountRegisterRepository.countBlockedByDate(start, end);
                double rate = total > 0 ? Math.round((double) blocked / total * 10000.0) / 100.0 : 0;

                String key = daysAgo == 1 ? "nextDay" : (daysAgo == 3 ? "threeDay" : "sevenDay");
                Map<String, Object> item = new HashMap<>();
                item.put("baseDate", baseDate.format(fmt));
                item.put("total", total);
                item.put("blocked", blocked);
                item.put("blockRate", rate);
                item.put("label", daysAgo == 1 ? "次日封号率" : (daysAgo == 3 ? "3天封号率" : "7天封号率"));
                data.put(key, item);

                // 汇总三批（次日/3天/7天）中的封号账号分布详情
                List<com.cpa.entity.TtAccountRegister> blockedList =
                        ttAccountRegisterRepository.listBlocked2faByDate(start, end);
                List<com.cpa.entity.TtAccountRegister> all2faList =
                        ttAccountRegisterRepository.list2faSuccessByDate(start, end);
                Map<String, Long> periodAndroidDist = new HashMap<>();
                Map<String, Long> periodBehaviorDist = new HashMap<>();
                Map<String, Long> periodTiktokDist = new HashMap<>();
                Map<String, Long> periodCountryDist = new HashMap<>();
                Map<String, Long> periodServerIpDist = new HashMap<>();
                Map<String, Long> periodAndroidTotalDist = new HashMap<>();
                Map<String, Long> periodBehaviorTotalDist = new HashMap<>();
                Map<String, Long> periodTiktokTotalDist = new HashMap<>();
                Map<String, Long> periodCountryTotalDist = new HashMap<>();
                Map<String, Long> periodServerIpTotalDist = new HashMap<>();
                for (com.cpa.entity.TtAccountRegister ar : blockedList) {
                    inc(blockedAndroidDist, normalize(ar.getAndroidVersion(), "未知"));
                    // 与今日详情保持一致：按行为原值统计分布
                    inc(blockedBehaviorDist, normalize(ar.getBehavior(), "未知"));
                    inc(blockedTiktokDist, normalize(ar.getTiktokVersion(), "未知"));
                    inc(blockedCountryDist, normalize(ar.getCountry(), "未知"));
                    inc(blockedServerIpDist, normalize(ar.getPhoneServerIp(), "未知"));

                    inc(periodAndroidDist, normalize(ar.getAndroidVersion(), "未知"));
                    inc(periodBehaviorDist, normalize(ar.getBehavior(), "未知"));
                    inc(periodTiktokDist, normalize(ar.getTiktokVersion(), "未知"));
                    inc(periodCountryDist, normalize(ar.getCountry(), "未知"));
                    inc(periodServerIpDist, normalize(ar.getPhoneServerIp(), "未知"));
                }
                for (com.cpa.entity.TtAccountRegister ar : all2faList) {
                    inc(periodAndroidTotalDist, normalize(ar.getAndroidVersion(), "未知"));
                    inc(periodBehaviorTotalDist, normalize(ar.getBehavior(), "未知"));
                    inc(periodTiktokTotalDist, normalize(ar.getTiktokVersion(), "未知"));
                    inc(periodCountryTotalDist, normalize(ar.getCountry(), "未知"));
                    inc(periodServerIpTotalDist, normalize(ar.getPhoneServerIp(), "未知"));
                }
                Map<String, Object> periodDetail = new HashMap<>();
                periodDetail.put("androidVersionDist", toDistList(periodAndroidDist));
                periodDetail.put("behaviorDist", toDistList(periodBehaviorDist));
                periodDetail.put("tiktokVersionDist", toDistList(periodTiktokDist));
                periodDetail.put("countryDist", toDistList(periodCountryDist));
                periodDetail.put("phoneServerIpDist", toDistList(periodServerIpDist));
                periodDetail.put("androidVersionRateDist", toRateDistList(periodAndroidDist, periodAndroidTotalDist));
                periodDetail.put("behaviorRateDist", toRateDistList(periodBehaviorDist, periodBehaviorTotalDist));
                periodDetail.put("tiktokVersionRateDist", toRateDistList(periodTiktokDist, periodTiktokTotalDist));
                periodDetail.put("countryRateDist", toRateDistList(periodCountryDist, periodCountryTotalDist));
                periodDetail.put("phoneServerIpRateDist", toRateDistList(periodServerIpDist, periodServerIpTotalDist));
                blockedDetailByPeriod.put(key, periodDetail);
            }

            Map<String, Object> blockedDetail = new HashMap<>();
            blockedDetail.put("androidVersionDist", toDistList(blockedAndroidDist));
            blockedDetail.put("behaviorDist", toDistList(blockedBehaviorDist));
            blockedDetail.put("tiktokVersionDist", toDistList(blockedTiktokDist));
            blockedDetail.put("countryDist", toDistList(blockedCountryDist));
            blockedDetail.put("phoneServerIpDist", toDistList(blockedServerIpDist));
            data.put("blockedDetail", blockedDetail);
            data.put("blockedDetailByPeriod", blockedDetailByPeriod);

            result.put("success", true);
            result.put("data", data);
        } catch (Exception e) {
            log.error("获取封号率统计失败", e);
            result.put("success", false);
            result.put("message", "获取统计失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 封号率最近7天趋势（与上面的卡片无关联，只看每天这批 2FA 成功账号的当前封号率）
     * @param queryDate 可选，作为“今天”的参考，不传则默认当前日期
     */
    public Map<String, Object> getBlockRateTrend(LocalDate queryDate) {
        Map<String, Object> result = new HashMap<>();
        try {
            LocalDate today = queryDate != null ? queryDate : LocalDate.now();
            DateTimeFormatter iso = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            DateTimeFormatter labelFmt = DateTimeFormatter.ofPattern("MM-dd");

            List<Map<String, Object>> trend = new ArrayList<>();
            // 最近7天：从6天前到今天（含），每一天看这批2FA成功账号当前封号率
            for (int i = 6; i >= 0; i--) {
                LocalDate baseDate = today.minusDays(i);
                LocalDateTime start = baseDate.atStartOfDay();
                LocalDateTime end = baseDate.atTime(23, 59, 59);

                long total = ttAccountRegisterRepository.count2faSuccessByDate(start, end);
                long blocked = ttAccountRegisterRepository.countBlockedByDate(start, end);
                double rate = total > 0 ? Math.round((double) blocked / total * 10000.0) / 100.0 : 0;

                Map<String, Object> item = new HashMap<>();
                item.put("date", baseDate.format(iso));
                item.put("label", baseDate.format(labelFmt));
                item.put("total", total);
                item.put("blocked", blocked);
                item.put("blockRate", rate);
                trend.add(item);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("queryDate", today.format(iso));
            data.put("lastUpdateTime", LocalDateTime.now());
            data.put("trend", trend);

            result.put("success", true);
            result.put("data", data);
        } catch (Exception e) {
            log.error("获取封号率趋势统计失败", e);
            result.put("success", false);
            result.put("message", "获取封号率趋势统计失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 留存日记：按日期范围查看留存执行记录 + 成功率（分页）
     */
    public Map<String, Object> getRetentionRecords(LocalDate queryDate, int page, int size) {
        Map<String, Object> result = new HashMap<>();
        try {
            LocalDate day = (queryDate != null ? queryDate : LocalDate.now());
            LocalDateTime start = day.atStartOfDay();
            LocalDateTime end = day.atTime(23, 59, 59);

            // 最近一天统计：留存脚本/备份执行情况
            Map<String, Object> stat = ttRetentionRecordRepository.statByCreatedBetween(start, end);
            long total = stat != null ? getLong(stat, "total_cnt") : 0;
            long ok = stat != null ? getLong(stat, "script_ok") : 0;
            long backupOk = stat != null ? getLong(stat, "backup_ok") : 0;
            double successRate = total > 0 ? Math.round((double) ok / total * 10000.0) / 100.0 : 0.0;
            double backupRate = total > 0 ? Math.round((double) backupOk / total * 10000.0) / 100.0 : 0.0;

            // 当天 cohort（脚本+备份都成功）的封号情况
            Map<String, Object> cohortStat = ttRetentionRecordRepository.statCohortBlockWithBackup(start, end);
            long cohortTotal = cohortStat != null ? getLong(cohortStat, "total_cnt") : 0;
            long cohortBlocked = cohortStat != null ? getLong(cohortStat, "blocked_cnt") : 0;
            long cohortLogout = cohortStat != null ? getLong(cohortStat, "logout_cnt") : 0;
            double cohortBlockRate = cohortTotal > 0
                    ? Math.round((double) cohortBlocked / cohortTotal * 10000.0) / 100.0
                    : 0.0;

            // 全部 cohort（历史上脚本+备份都成功）的封号情况
            Map<String, Object> allCohortStat = ttRetentionRecordRepository.statAllCohortBlockWithBackup();
            long allCohortTotal = allCohortStat != null ? getLong(allCohortStat, "total_cnt") : 0;
            long allCohortBlocked = allCohortStat != null ? getLong(allCohortStat, "blocked_cnt") : 0;
            long allCohortLogout = allCohortStat != null ? getLong(allCohortStat, "logout_cnt") : 0;
            double allCohortBlockRate = allCohortTotal > 0
                    ? Math.round((double) allCohortBlocked / allCohortTotal * 10000.0) / 100.0
                    : 0.0;

            int offset = (page - 1) * size;
            List<com.cpa.entity.TtRetentionRecord> records =
                    ttRetentionRecordRepository.listByCreatedBetween(start, end, offset, size);

            List<Map<String, Object>> list = new ArrayList<>();
            if (records != null) {
                DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");
                for (com.cpa.entity.TtRetentionRecord r : records) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", r.getId());
                    row.put("taskId", r.getTaskId());
                    row.put("phoneId", r.getPhoneId());
                    row.put("phoneServerIp", r.getPhoneServerIp());
                    row.put("accountRegisterId", r.getAccountRegisterId());
                    row.put("gaid", r.getGaid());
                    row.put("scriptSuccess", r.getScriptSuccess());
                    row.put("backupSuccess", r.getBackupSuccess());
                    row.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().format(tsFmt) : null);
                    list.add(row);
                }
            }

            // 账号最终留存状态：按当天全量留存记录关联账号统计（不受分页影响）
            long retention2faSuccess = ttRetentionRecordRepository.countRetention2faSuccessByCreatedBetween(start, end);
            long retentionLogout = ttRetentionRecordRepository.countRetentionLogoutByCreatedBetween(start, end);

            Map<String, Object> data = new HashMap<>();
            data.put("date", day.format(DateTimeFormatter.ISO_LOCAL_DATE));
            data.put("total", total);
            data.put("scriptSuccessCount", ok);
            data.put("backupSuccessCount", backupOk);
            data.put("successRate", successRate);
            data.put("backupRate", backupRate);
            data.put("cohortTotal", cohortTotal);
            data.put("cohortBlocked", cohortBlocked);
            data.put("cohortLogout", cohortLogout);
            data.put("cohortBlockRate", cohortBlockRate);
            data.put("allCohortTotal", allCohortTotal);
            data.put("allCohortBlocked", allCohortBlocked);
            data.put("allCohortLogout", allCohortLogout);
            data.put("allCohortBlockRate", allCohortBlockRate);
            data.put("retention2faSuccess", retention2faSuccess);
            data.put("retentionLogout", retentionLogout);
            data.put("records", list);
            data.put("page", page);
            data.put("size", size);
            data.put("lastUpdateTime", LocalDateTime.now());

            result.put("success", true);
            result.put("data", data);
        } catch (Exception e) {
            log.error("获取留存日记统计失败", e);
            result.put("success", false);
            result.put("message", "获取留存记录失败: " + e.getMessage());
        }
        return result;
    }
}
