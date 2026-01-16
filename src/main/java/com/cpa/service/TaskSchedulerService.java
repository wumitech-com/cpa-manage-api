package com.cpa.service;

import com.cpa.entity.TtAccountData;
import com.cpa.entity.TtAccountDataOutlook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务调度服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSchedulerService {

    private final DeviceService deviceService;
    private final ScriptService scriptService;

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
}
