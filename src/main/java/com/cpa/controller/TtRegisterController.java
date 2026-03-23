package com.cpa.controller;

import com.cpa.service.TtRegisterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TT账号批量注册控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/tt-register")
@RequiredArgsConstructor
public class TtRegisterController {

    private final TtRegisterService ttRegisterService;

    /**
     * 批量注册TT账号
     * 
     * 请求参数格式:
     * {
     *   "phoneIds": ["phone_id_1", "phone_id_2"],
     *   "serverIp": "10.7.107.224",
     *   "resetParams": {
     *     "country": "BR",
     *     "sdk": "33",
     *     "imagePath": "uhub.service.ucloud.cn/phone/android13_cpu:20251120",
     *     "gaidTag": "20250410",
     *     "dynamicIpChannel": "closeli",
     *     "staticIpChannel": "",
     *     "biz": ""
     *   }
     * }
     */
    @PostMapping("/batch")
    public Map<String, Object> batchRegisterTtAccounts(@RequestBody Map<String, Object> request) {
        log.info("接收到批量注册TT账号请求，参数: {}", request);
        
        @SuppressWarnings("unchecked")
        List<String> phoneIds = (List<String>) request.get("phoneIds");
        String serverIp = (String) request.get("serverIp");
        
        if (phoneIds == null || phoneIds.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "phoneIds参数不能为空");
            return errorResponse;
        }
        
        if (serverIp == null || serverIp.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "serverIp参数不能为空");
            return errorResponse;
        }
        
        // 获取ResetPhoneEnv参数（可选）
        @SuppressWarnings("unchecked")
        Map<String, String> resetParams = (Map<String, String>) request.get("resetParams");
        
        return ttRegisterService.batchRegisterTtAccounts(phoneIds, serverIp, resetParams);
    }

    /**
     * 新增留存任务（task_kind=RETENTION）
     * 请求体: { "phoneId": "tt_farm_xxx", "serverIp": "10.7.136.129", "targetCount": 50, "country": "US", "imagePath": "" }
     */
    @PostMapping("/retention")
    public Map<String, Object> createRetentionTask(@RequestBody Map<String, Object> request) {
        log.info("接收到创建留存任务请求，参数: {}", request);
        String phoneId = (String) request.get("phoneId");
        String serverIp = (String) request.get("serverIp");
        if (phoneId == null || phoneId.isEmpty() || serverIp == null || serverIp.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "phoneId 与 serverIp 不能为空");
            return err;
        }
        Object tc = request.get("targetCount");
        Integer targetCount = tc != null ? (tc instanceof Number ? ((Number) tc).intValue() : null) : null;
        String country = (String) request.get("country");
        String imagePath = (String) request.get("imagePath");
        com.cpa.entity.TtRegisterTask task = ttRegisterService.createRetentionTask(phoneId, serverIp, targetCount, country, imagePath);
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("message", "留存任务已创建");
        resp.put("taskId", task.getTaskId());
        resp.put("taskKind", task.getTaskKind());
        return resp;
    }

    /**
     * 多设备并行注册TT账号（多个设备同时注册，每个设备可注册多个账号）
     * 
     * 请求参数格式:
     * {
     *   "phoneIds": ["phone_id_1", "phone_id_2", "phone_id_3"],
     *   "serverIp": "10.7.107.224",
     *   "maxConcurrency": 10,
     *   "targetCountPerDevice": 1,
     *   "resetParams": {
     *     "country": "BR",
     *     "sdk": "33",
     *     "imagePath": "uhub.service.ucloud.cn/phone/android13_cpu:20251120",
     *     "gaidTag": "20250410",
     *     "dynamicIpChannel": "closeli",
     *     "staticIpChannel": "",
     *     "biz": ""
     *   }
     * }
     */
    @PostMapping("/parallel")
    public Map<String, Object> parallelRegisterMultipleDevices(@RequestBody Map<String, Object> request) {
        log.info("接收到多设备并行注册请求，参数: {}", request);
        
        @SuppressWarnings("unchecked")
        List<String> phoneIds = (List<String>) request.get("phoneIds");
        String serverIp = (String) request.get("serverIp");
        Object maxConcurrencyObj = request.get("maxConcurrency");
        Object targetCountPerDeviceObj = request.get("targetCountPerDevice");
        
        if (phoneIds == null || phoneIds.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "phoneIds参数不能为空");
            return errorResponse;
        }
        
        if (serverIp == null || serverIp.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "serverIp参数不能为空");
            return errorResponse;
        }
        
        Integer maxConcurrency = null;
        if (maxConcurrencyObj != null) {
            if (maxConcurrencyObj instanceof Integer) {
                maxConcurrency = (Integer) maxConcurrencyObj;
            } else if (maxConcurrencyObj instanceof String) {
                try {
                    maxConcurrency = Integer.parseInt((String) maxConcurrencyObj);
                } catch (NumberFormatException e) {
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("message", "maxConcurrency参数格式错误，必须是数字");
                    return errorResponse;
                }
            }
        }
        
        if (maxConcurrency != null && (maxConcurrency <= 0 || maxConcurrency > 100)) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "maxConcurrency参数必须在1-100之间");
            return errorResponse;
        }
        
        Integer targetCountPerDevice = null;
        if (targetCountPerDeviceObj != null) {
            if (targetCountPerDeviceObj instanceof Integer) {
                targetCountPerDevice = (Integer) targetCountPerDeviceObj;
            } else if (targetCountPerDeviceObj instanceof String) {
                try {
                    targetCountPerDevice = Integer.parseInt((String) targetCountPerDeviceObj);
                } catch (NumberFormatException e) {
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("message", "targetCountPerDevice参数格式错误，必须是数字");
                    return errorResponse;
                }
            }
        }
        
        // 允许0（无限循环）或1-1000之间的值
        if (targetCountPerDevice != null && targetCountPerDevice != 0 && (targetCountPerDevice < 1 || targetCountPerDevice > 1000)) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "targetCountPerDevice参数必须是0（无限循环）或1-1000之间的数字");
            return errorResponse;
        }
        
        // 获取TikTok版本目录（必填）
        String tiktokVersionDir = (String) request.get("tiktokVersionDir");
        if (tiktokVersionDir == null || tiktokVersionDir.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "tiktokVersionDir参数不能为空");
            return errorResponse;
        }
        
        // 获取ResetPhoneEnv参数（可选）
        @SuppressWarnings("unchecked")
        Map<String, String> resetParams = (Map<String, String>) request.get("resetParams");
        
        return ttRegisterService.parallelRegisterMultipleDevices(phoneIds, serverIp, maxConcurrency, targetCountPerDevice, tiktokVersionDir, resetParams);
    }
    
    /**
     * 查询批量注册任务状态
     * 
     * @param taskId 任务ID
     * @return 任务状态
     */
    @GetMapping("/status/{taskId}")
    public Map<String, Object> getTaskStatus(@PathVariable String taskId) {
        log.info("查询批量注册任务状态，taskId: {}", taskId);
        return ttRegisterService.getTaskStatus(taskId);
    }
    
    /**
     * 获取任务日志
     * 
     * @param taskId 任务ID
     * @param lines 读取行数（可选，默认500行）
     * @return 日志内容
     */
    @GetMapping("/log/{taskId}")
    public Map<String, Object> getTaskLog(@PathVariable String taskId,
                                          @RequestParam(required = false, defaultValue = "500") int lines) {
        log.info("查询批量注册任务日志，taskId: {}, lines: {}", taskId, lines);
        return ttRegisterService.getTaskLog(taskId, lines);
    }
    
    /**
     * 停止任务
     * 
     * @param taskId 任务ID
     * @return 停止结果
     */
    @PostMapping("/stop/{taskId}")
    public Map<String, Object> stopTask(@PathVariable String taskId) {
        log.info("停止任务请求，taskId: {}", taskId);
        return ttRegisterService.stopTask(taskId);
    }
    
    /**
     * 获取所有任务列表（分页）
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return 任务列表分页数据
     */
    @GetMapping("/tasks")
    public Map<String, Object> getAllTasks(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        log.info("获取任务列表, page={}, size={}", page, size);
        if (page < 1) {
            page = 1;
        }
        if (size <= 0) {
            size = 20;
        }
        return ttRegisterService.getAllTasks(page, size);
    }

    /**
     * 更新任务配置（任务小窝中编辑）
     */
    @PostMapping("/task/update")
    public Map<String, Object> updateTaskConfig(@RequestBody Map<String, Object> request) {
        log.info("更新任务配置请求: {}", request);
        return ttRegisterService.updateTaskConfig(request);
    }

    /**
     * 恢复任务，将状态改回 PENDING
     */
    @PostMapping("/task/resume/{taskId}")
    public Map<String, Object> resumeTask(@PathVariable String taskId) {
        log.info("恢复任务请求，taskId: {}", taskId);
        return ttRegisterService.resumeTask(taskId);
    }
    
    /**
     * 多设备并行注册Outlook邮箱账号（多个设备同时注册，每个设备可注册多个账号）
     * 
     * 请求参数格式:
     * {
     *   "phoneIds": ["phone_id_1", "phone_id_2", "phone_id_3"],
     *   "serverIp": "10.7.107.224",
     *   "maxConcurrency": 10,
     *   "targetCountPerDevice": 1,
     *   "resetParams": {
     *     "country": "BR",
     *     "sdk": "33",
     *     "imagePath": "uhub.service.ucloud.cn/phone/android13_cpu:20251120",
     *     "gaidTag": "20250410",
     *     "dynamicIpChannel": "closeli",
     *     "staticIpChannel": "",
     *     "biz": ""
     *   }
     * }
     */
    @PostMapping("/outlook/parallel")
    public Map<String, Object> parallelRegisterOutlookAccounts(@RequestBody Map<String, Object> request) {
        log.info("接收到多设备并行注册Outlook账号请求，参数: {}", request);
        
        @SuppressWarnings("unchecked")
        List<String> phoneIds = (List<String>) request.get("phoneIds");
        String serverIp = (String) request.get("serverIp");
        Object maxConcurrencyObj = request.get("maxConcurrency");
        Object targetCountPerDeviceObj = request.get("targetCountPerDevice");
        
        if (phoneIds == null || phoneIds.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "phoneIds参数不能为空");
            return errorResponse;
        }
        
        if (serverIp == null || serverIp.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "serverIp参数不能为空");
            return errorResponse;
        }
        
        Integer maxConcurrency = null;
        if (maxConcurrencyObj != null) {
            if (maxConcurrencyObj instanceof Integer) {
                maxConcurrency = (Integer) maxConcurrencyObj;
            } else if (maxConcurrencyObj instanceof String) {
                try {
                    maxConcurrency = Integer.parseInt((String) maxConcurrencyObj);
                } catch (NumberFormatException e) {
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("message", "maxConcurrency参数格式错误，必须是数字");
                    return errorResponse;
                }
            }
        }
        
        if (maxConcurrency != null && (maxConcurrency <= 0 || maxConcurrency > 100)) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "maxConcurrency参数必须在1-100之间");
            return errorResponse;
        }
        
        Integer targetCountPerDevice = null;
        if (targetCountPerDeviceObj != null) {
            if (targetCountPerDeviceObj instanceof Integer) {
                targetCountPerDevice = (Integer) targetCountPerDeviceObj;
            } else if (targetCountPerDeviceObj instanceof String) {
                try {
                    targetCountPerDevice = Integer.parseInt((String) targetCountPerDeviceObj);
                } catch (NumberFormatException e) {
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("message", "targetCountPerDevice参数格式错误，必须是数字");
                    return errorResponse;
                }
            }
        }
        
        // 允许0（无限循环）或1-1000之间的值
        if (targetCountPerDevice != null && targetCountPerDevice != 0 && (targetCountPerDevice < 1 || targetCountPerDevice > 1000)) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "targetCountPerDevice参数必须是0（无限循环）或1-1000之间的数字");
            return errorResponse;
        }
        
        // 获取TikTok版本目录（必填）
        String tiktokVersionDir = (String) request.get("tiktokVersionDir");
        if (tiktokVersionDir == null || tiktokVersionDir.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "tiktokVersionDir参数不能为空");
            return errorResponse;
        }
        
        // 获取ResetPhoneEnv参数（可选）
        @SuppressWarnings("unchecked")
        Map<String, String> resetParams = (Map<String, String>) request.get("resetParams");
        
        return ttRegisterService.parallelRegisterOutlookAccounts(phoneIds, serverIp, maxConcurrency, targetCountPerDevice, tiktokVersionDir, resetParams);
    }
    
    /**
     * 查询任务列表（从数据库）
     * 
     * @param status 任务状态（可选，PENDING/RUNNING/COMPLETED/FAILED/STOPPED）
     * @param taskType 任务类型（可选，FAKE_EMAIL/REAL_EMAIL）
     * @param serverIp 服务器IP（可选）
     * @param phoneId 设备ID（可选）
     * @param page 页码（可选，默认1）
     * @param size 每页大小（可选，默认20）
     * @return 任务列表
     */
    @GetMapping("/task/list")
    public Map<String, Object> getTaskList(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String serverIp,
            @RequestParam(required = false) String phoneId,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        log.info("查询任务列表，status={}, taskType={}, serverIp={}, phoneId={}, page={}, size={}", 
                status, taskType, serverIp, phoneId, page, size);
        return ttRegisterService.getTaskList(status, taskType, serverIp, phoneId, page, size);
    }
    
    /**
     * 根据任务ID查询任务详情
     * 
     * @param taskId 任务ID
     * @return 任务详情
     */
    @GetMapping("/task/{taskId}")
    public Map<String, Object> getTaskById(@PathVariable String taskId) {
        log.info("查询任务详情，taskId: {}", taskId);
        return ttRegisterService.getTaskById(taskId);
    }
    
    /**
     * 停止任务（数据库任务）
     * 
     * @param taskId 任务ID
     * @return 停止结果
     */
    @PostMapping("/task/stop/{taskId}")
    public Map<String, Object> stopTaskById(@PathVariable String taskId) {
        log.info("停止任务请求，taskId: {}", taskId);
        return ttRegisterService.stopTaskById(taskId);
    }
    
    /**
     * 批量停止任务
     * 
     * @param request 请求参数，包含 taskIds 数组
     * @return 停止结果
     */
    @PostMapping("/task/stop/batch")
    public Map<String, Object> stopTasksBatch(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> taskIds = (List<String>) request.get("taskIds");
        log.info("批量停止任务请求，taskIds: {}", taskIds);
        return ttRegisterService.stopTasksBatch(taskIds);
    }
    
    /**
     * 删除任务
     * 
     * @param taskId 任务ID
     * @return 删除结果
     */
    @DeleteMapping("/task/{taskId}")
    public Map<String, Object> deleteTask(@PathVariable String taskId) {
        log.info("删除任务请求，taskId: {}", taskId);
        return ttRegisterService.deleteTask(taskId);
    }
    
    /**
     * 批量删除任务
     * 
     * @param request 请求参数，包含 taskIds 数组
     * @return 删除结果
     */
    @DeleteMapping("/task/batch")
    public Map<String, Object> deleteTasksBatch(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> taskIds = (List<String>) request.get("taskIds");
        log.info("批量删除任务请求，taskIds: {}", taskIds);
        return ttRegisterService.deleteTasksBatch(taskIds);
    }
    
    /**
     * 重置任务状态为 PENDING（用于重新执行失败的任务）
     * 
     * @param taskId 任务ID
     * @return 重置结果
     */
    @PostMapping("/task/reset/{taskId}")
    public Map<String, Object> resetTask(@PathVariable String taskId) {
        log.info("重置任务状态请求，taskId: {}", taskId);
        return ttRegisterService.resetTask(taskId);
    }
}

