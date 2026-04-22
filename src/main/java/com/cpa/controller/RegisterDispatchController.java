package com.cpa.controller;

import com.cpa.service.RegisterDispatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/register-dispatch")
@RequiredArgsConstructor
public class RegisterDispatchController {

    private final RegisterDispatchService registerDispatchService;

    @PostMapping("/candidates")
    public Map<String, Object> candidates(@RequestBody RegisterDispatchService.CandidateQuery request) {
        try {
            return ok(registerDispatchService.listCandidates(request));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @PostMapping("/batch-update")
    public Map<String, Object> batchUpdate(@RequestBody RegisterDispatchService.BatchUpdateRequest request) {
        try {
            return ok(registerDispatchService.batchUpdate(request));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @PostMapping("/logs")
    public Map<String, Object> logs(@RequestBody RegisterDispatchService.LogQuery request) {
        try {
            return ok(registerDispatchService.listLogs(request));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
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
