package com.cpa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cpa.entity.TtTaskBatch;
import com.cpa.entity.TtTaskExecutionLog;
import com.cpa.entity.TtTaskInstance;
import com.cpa.entity.TtTaskTemplate;
import com.cpa.repository.TtTaskBatchRepository;
import com.cpa.repository.TtTaskExecutionLogRepository;
import com.cpa.repository.TtTaskInstanceRepository;
import com.cpa.repository.TtTaskTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskCenterService {

    private final TtTaskTemplateRepository templateRepository;
    private final TtTaskBatchRepository batchRepository;
    private final TtTaskInstanceRepository instanceRepository;
    private final TtTaskExecutionLogRepository executionLogRepository;

    public Map<String, Object> listTemplates(String appCode, String taskType, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = (safePage - 1) * safeSize;
        String safeApp = trimToNull(appCode);
        String safeType = trimToNull(taskType);
        List<TtTaskTemplate> list = templateRepository.selectList(
                new LambdaQueryWrapper<TtTaskTemplate>()
                        .eq(safeApp != null, TtTaskTemplate::getAppCode, safeApp)
                        .eq(safeType != null, TtTaskTemplate::getTaskType, safeType)
                        .orderByDesc(TtTaskTemplate::getCreatedAt)
                        .last("LIMIT " + offset + "," + safeSize)
        );
        long total = templateRepository.selectCount(
                new LambdaQueryWrapper<TtTaskTemplate>()
                        .eq(safeApp != null, TtTaskTemplate::getAppCode, safeApp)
                        .eq(safeType != null, TtTaskTemplate::getTaskType, safeType)
        );
        return Map.of("list", list, "total", total, "page", safePage, "size", safeSize);
    }

    @Transactional
    public TtTaskTemplate createTemplate(TtTaskTemplate body) {
        if (trimToNull(body.getTemplateCode()) == null) throw new IllegalArgumentException("template_code 不能为空");
        if (trimToNull(body.getName()) == null) throw new IllegalArgumentException("name 不能为空");
        if (trimToNull(body.getAppCode()) == null) throw new IllegalArgumentException("app_code 不能为空");
        if (trimToNull(body.getTaskType()) == null) throw new IllegalArgumentException("task_type 不能为空");
        if (trimToNull(body.getExecutorType()) == null) throw new IllegalArgumentException("executor_type 不能为空");
        if (templateRepository.findByTemplateCode(body.getTemplateCode()) != null) {
            throw new IllegalArgumentException("template_code 已存在");
        }
        body.setTemplateCode(body.getTemplateCode().trim());
        body.setName(body.getName().trim());
        body.setAppCode(body.getAppCode().trim().toUpperCase());
        body.setTaskType(body.getTaskType().trim().toUpperCase());
        body.setExecutorType(body.getExecutorType().trim().toUpperCase());
        body.setStatus(trimToNull(body.getStatus()) == null ? "DRAFT" : body.getStatus().trim().toUpperCase());
        LocalDateTime now = LocalDateTime.now();
        body.setCreatedAt(now);
        body.setUpdatedAt(now);
        templateRepository.insert(body);
        return body;
    }

    @Transactional
    public Map<String, Object> createBatch(Map<String, Object> request) {
        String templateCode = trimToNull(asString(request.get("templateCode")));
        if (templateCode == null) throw new IllegalArgumentException("templateCode 不能为空");
        TtTaskTemplate template = templateRepository.findByTemplateCode(templateCode);
        if (template == null) throw new IllegalArgumentException("模板不存在");
        int instanceCount = parseInt(request.get("instanceCount"), 1);
        if (instanceCount <= 0 || instanceCount > 10000) throw new IllegalArgumentException("instanceCount 需在 1~10000");

        TtTaskBatch batch = new TtTaskBatch();
        batch.setBatchId("batch-" + UUID.randomUUID());
        batch.setTemplateCode(template.getTemplateCode());
        batch.setAppCode(template.getAppCode());
        batch.setTaskType(template.getTaskType());
        batch.setExecutorType(template.getExecutorType());
        batch.setIdempotencyKey(trimToNull(asString(request.get("idempotencyKey"))));
        batch.setStatus("READY");
        batch.setTotalCount(instanceCount);
        batch.setSuccessCount(0);
        batch.setFailCount(0);
        batch.setSubmittedBy(trimToNull(asString(request.get("submittedBy"))));
        LocalDateTime now = LocalDateTime.now();
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batchRepository.insert(batch);

        for (int i = 0; i < instanceCount; i++) {
            TtTaskInstance instance = new TtTaskInstance();
            instance.setInstanceId("inst-" + UUID.randomUUID());
            instance.setBatchId(batch.getBatchId());
            instance.setTemplateCode(batch.getTemplateCode());
            instance.setAppCode(batch.getAppCode());
            instance.setTaskType(batch.getTaskType());
            instance.setExecutorType(batch.getExecutorType());
            instance.setResourceServerIp(trimToNull(asString(request.get("resourceServerIp"))));
            instance.setResourcePhoneId(trimToNull(asString(request.get("resourcePhoneId"))));
            instance.setStatus("READY");
            instance.setPriority(parseInt(request.get("priority"), 0));
            instance.setPayloadJson(trimToNull(asString(request.get("payloadJson"))));
            instance.setCreatedAt(now);
            instance.setUpdatedAt(now);
            instanceRepository.insert(instance);
        }
        return Map.of("batchId", batch.getBatchId(), "totalCount", instanceCount);
    }

    public Map<String, Object> listInstances(String batchId, String status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = (safePage - 1) * safeSize;
        String safeBatchId = trimToNull(batchId);
        String safeStatus = trimToNull(status);
        List<TtTaskInstance> list = instanceRepository.selectList(
                new LambdaQueryWrapper<TtTaskInstance>()
                        .eq(safeBatchId != null, TtTaskInstance::getBatchId, safeBatchId)
                        .eq(safeStatus != null, TtTaskInstance::getStatus, safeStatus)
                        .orderByDesc(TtTaskInstance::getCreatedAt)
                        .last("LIMIT " + offset + "," + safeSize)
        );
        long total = instanceRepository.selectCount(
                new LambdaQueryWrapper<TtTaskInstance>()
                        .eq(safeBatchId != null, TtTaskInstance::getBatchId, safeBatchId)
                        .eq(safeStatus != null, TtTaskInstance::getStatus, safeStatus)
        );
        return Map.of("list", list, "total", total, "page", safePage, "size", safeSize);
    }

    @Transactional
    public Map<String, Object> updateInstanceStatus(String instanceId, String status, String message) {
        TtTaskInstance instance = instanceRepository.findByInstanceId(instanceId);
        if (instance == null) throw new IllegalArgumentException("实例不存在");
        String safeStatus = trimToNull(status);
        if (safeStatus == null) throw new IllegalArgumentException("status 不能为空");
        instance.setStatus(safeStatus.toUpperCase());
        instance.setUpdatedAt(LocalDateTime.now());
        instanceRepository.updateById(instance);

        TtTaskExecutionLog log = new TtTaskExecutionLog();
        log.setInstanceId(instanceId);
        log.setLogLevel("INFO");
        log.setStepName("STATUS_CHANGE");
        log.setMessage(trimToNull(message) == null ? "status updated" : message.trim());
        log.setCreatedAt(LocalDateTime.now());
        executionLogRepository.insert(log);
        return Map.of("instanceId", instanceId, "status", instance.getStatus());
    }

    public List<TtTaskExecutionLog> listExecutionLog(String instanceId, int limit) {
        if (trimToNull(instanceId) == null) return List.of();
        return executionLogRepository.listByInstanceId(instanceId.trim(), limit);
    }

    private int parseInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
