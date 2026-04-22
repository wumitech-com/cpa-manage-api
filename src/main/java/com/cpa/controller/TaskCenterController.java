package com.cpa.controller;

import com.cpa.entity.TtTaskTemplate;
import com.cpa.service.TaskCenterService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/task-center")
@RequiredArgsConstructor
public class TaskCenterController {

    private final TaskCenterService taskCenterService;

    @GetMapping("/template/list")
    public Map<String, Object> listTemplates(@RequestParam(required = false) String appCode,
                                             @RequestParam(required = false) String taskType,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return ok(taskCenterService.listTemplates(appCode, taskType, page, size));
    }

    @PostMapping("/template/create")
    public Map<String, Object> createTemplate(@RequestBody TtTaskTemplate body) {
        try {
            return ok(taskCenterService.createTemplate(body));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @PostMapping("/batch/create")
    public Map<String, Object> createBatch(@RequestBody Map<String, Object> request) {
        try {
            return ok(taskCenterService.createBatch(request));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @GetMapping("/instance/list")
    public Map<String, Object> listInstances(@RequestParam(required = false) String batchId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return ok(taskCenterService.listInstances(batchId, status, page, size));
    }

    @PostMapping("/instance/status/{instanceId}")
    public Map<String, Object> updateInstanceStatus(@PathVariable String instanceId,
                                                    @RequestBody Map<String, Object> request) {
        try {
            String status = request.get("status") == null ? null : String.valueOf(request.get("status"));
            String message = request.get("message") == null ? null : String.valueOf(request.get("message"));
            return ok(taskCenterService.updateInstanceStatus(instanceId, status, message));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @GetMapping("/execution-log/{instanceId}")
    public Map<String, Object> listExecutionLog(@PathVariable String instanceId,
                                                @RequestParam(defaultValue = "200") int limit) {
        return ok(taskCenterService.listExecutionLog(instanceId, limit));
    }

    private Map<String, Object> ok(Object data) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("data", data);
        return r;
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("message", message);
        return r;
    }
}
