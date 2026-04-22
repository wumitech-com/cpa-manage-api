package com.cpa.service;

import com.cpa.entity.TtRegisterDispatchLog;
import com.cpa.entity.TtRegisterTask;
import com.cpa.repository.TtRegisterDispatchLogRepository;
import com.cpa.repository.TtRegisterTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RegisterDispatchService {

    private static final Set<String> ALLOWED_FILTER_STATUSES = Set.of("STOPPED", "COMPLETED", "FAILED");
    private static final Set<String> ALLOWED_UPDATE_FROM_STATUSES = Set.of("STOPPED", "COMPLETED", "FAILED");

    private final TtRegisterTaskRepository registerTaskRepository;
    private final TtRegisterDispatchLogRepository dispatchLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> listCandidates(CandidateQuery request) {
        if (request == null) {
            request = new CandidateQuery();
        }
        int page = request.page == null || request.page < 1 ? 1 : request.page;
        int size = request.size == null || request.size < 1 ? 20 : Math.min(request.size, 200);
        int offset = (page - 1) * size;
        List<String> statuses = normalizeStatuses(request.statuses);
        String serverIp = trimToNull(request.serverIp);
        String phoneId = trimToNull(request.phoneId);

        List<TtRegisterTask> list = registerTaskRepository.listDispatchCandidates(statuses, serverIp, phoneId, size, offset);
        long total = registerTaskRepository.countDispatchCandidates(statuses, serverIp, phoneId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    @Transactional
    public Map<String, Object> batchUpdate(BatchUpdateRequest request) {
        validateUpdateRequest(request);
        String batchId = "dispatch-update-" + UUID.randomUUID();
        int successCount = 0;
        int skipCount = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        Integer targetCount = request.continuous != null && request.continuous ? 0 : request.targetCount;

        for (Long id : request.taskIds) {
            TtRegisterTask task = registerTaskRepository.selectById(id);
            if (task == null) {
                skipCount++;
                details.add(detail(id, "SKIPPED", "任务不存在"));
                insertLog(batchId, null, null, null, targetCount, request, "SKIPPED", "任务不存在", now);
                continue;
            }
            if (!ALLOWED_UPDATE_FROM_STATUSES.contains(task.getStatus())) {
                skipCount++;
                details.add(detail(id, "SKIPPED", "状态非已停止/已完成/失败，当前=" + task.getStatus()));
                insertLog(batchId, task, task.getStatus(), task.getStatus(), targetCount, request, "SKIPPED", "状态不允许更新", now);
                continue;
            }

            String oldStatus = task.getStatus();
            task.setTaskType(request.taskType);
            task.setTargetCount(targetCount);
            task.setTiktokVersionDir(trimToNull(request.tiktokVersionDir));
            task.setCountry(trimToNull(request.country));
            task.setSdk(trimToNull(request.sdk));
            task.setImagePath(trimToNull(request.imagePath));
            task.setDynamicIpChannel(trimToNull(request.dynamicIpChannel));
            task.setStaticIpChannel(trimToNull(request.staticIpChannel));
            task.setStatus("PENDING");
            task.setUpdatedAt(now);
            registerTaskRepository.updateById(task);
            successCount++;
            details.add(detail(id, "UPDATED", "已更新并重置为 PENDING"));
            insertLog(batchId, task, oldStatus, "PENDING", targetCount, request, "UPDATED", "批量更新成功", now);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);
        result.put("successCount", successCount);
        result.put("skipCount", skipCount);
        result.put("details", details);
        return result;
    }

    public Map<String, Object> listLogs(LogQuery request) {
        if (request == null) {
            request = new LogQuery();
        }
        int page = request.page == null || request.page < 1 ? 1 : request.page;
        int size = request.size == null || request.size < 1 ? 20 : Math.min(request.size, 200);
        int offset = (page - 1) * size;
        String taskId = trimToNull(request.taskId);
        String serverIp = trimToNull(request.serverIp);
        String phoneId = trimToNull(request.phoneId);
        List<TtRegisterDispatchLog> list = dispatchLogRepository.listLogs(taskId, serverIp, phoneId, size, offset);
        long total = dispatchLogRepository.countLogs(taskId, serverIp, phoneId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    private void validateUpdateRequest(BatchUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        request.taskType = trimToNull(request.taskType);
        if (request.taskType == null || (!"FAKE_EMAIL".equals(request.taskType) && !"REAL_EMAIL".equals(request.taskType))) {
            throw new IllegalArgumentException("taskType 仅支持 FAKE_EMAIL / REAL_EMAIL");
        }
        if (request.taskIds == null || request.taskIds.isEmpty()) {
            throw new IllegalArgumentException("请至少勾选1条任务");
        }
        Set<Long> dedup = new LinkedHashSet<>();
        for (Long taskId : request.taskIds) {
            if (taskId != null && taskId > 0) {
                dedup.add(taskId);
            }
        }
        request.taskIds = new ArrayList<>(dedup);
        if (request.taskIds.isEmpty()) {
            throw new IllegalArgumentException("请至少勾选1条任务");
        }
        if (request.continuous == null) {
            request.continuous = Boolean.FALSE;
        }
        if (!request.continuous) {
            int target = request.targetCount == null ? 1 : request.targetCount;
            if (target <= 0) {
                throw new IllegalArgumentException("非持续注册时，注册轮次必须大于0");
            }
            request.targetCount = target;
        }
    }

    private List<String> normalizeStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of("STOPPED", "COMPLETED");
        }
        List<String> result = new ArrayList<>();
        for (String status : statuses) {
            String value = trimToNull(status);
            if (value != null && ALLOWED_FILTER_STATUSES.contains(value)) {
                result.add(value);
            }
        }
        return result.isEmpty() ? List.of("STOPPED", "COMPLETED") : result;
    }

    private void insertLog(String batchId,
                           TtRegisterTask task,
                           String oldStatus,
                           String newStatus,
                           Integer targetCount,
                           BatchUpdateRequest request,
                           String result,
                           String message,
                           LocalDateTime now) {
        try {
            TtRegisterDispatchLog log = new TtRegisterDispatchLog();
            log.setBatchId(batchId);
            if (task != null) {
                log.setRegisterTaskId(task.getId());
                log.setTaskId(task.getTaskId());
                log.setServerIp(task.getServerIp());
                log.setPhoneId(task.getPhoneId());
            }
            log.setOldStatus(oldStatus);
            log.setNewStatus(newStatus);
            log.setTaskType(request.taskType);
            log.setTargetCount(targetCount);
            log.setIsContinuous(request.continuous != null && request.continuous ? 1 : 0);
            log.setPayloadJson(objectMapper.writeValueAsString(request));
            log.setResult(result);
            log.setMessage(message);
            log.setCreatedAt(now);
            dispatchLogRepository.insert(log);
        } catch (Exception ignored) {
            // 日志写入不阻塞主流程
        }
    }

    private Map<String, Object> detail(Long id, String result, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("result", result);
        item.put("message", message);
        return item;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    @Data
    public static class CandidateQuery {
        private List<String> statuses;
        private String serverIp;
        private String phoneId;
        private Integer page;
        private Integer size;
    }

    @Data
    public static class BatchUpdateRequest {
        private List<Long> taskIds;
        private String taskType;
        private String tiktokVersionDir;
        private String country;
        private String sdk;
        private String imagePath;
        private String dynamicIpChannel;
        private String staticIpChannel;
        private Boolean continuous;
        private Integer targetCount;
    }

    @Data
    public static class LogQuery {
        private String taskId;
        private String serverIp;
        private String phoneId;
        private Integer page;
        private Integer size;
    }
}
