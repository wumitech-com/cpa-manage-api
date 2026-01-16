package com.cpa.service;

import com.cpa.repository.TtAccountDataOutlookRepository;
import com.cpa.repository.TtAccountDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
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
}
