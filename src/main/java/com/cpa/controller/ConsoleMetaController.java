package com.cpa.controller;

import com.cpa.service.AuthService;
import com.cpa.service.ConsoleMetaService;
import com.cpa.service.ConsoleMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/console")
@RequiredArgsConstructor
public class ConsoleMetaController {

    private final ConsoleMetaService metaService;
    private final ConsoleMetricsService metricsService;
    private final AuthService authService;

    @GetMapping("/meta/bootstrap")
    public Map<String, Object> bootstrap(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        String username = authService.getUsernameByToken(token);
        return ok(metaService.bootstrap(username));
    }

    @PostMapping("/metrics/perf")
    public Map<String, Object> ingestPerf(@RequestBody(required = false) Map<String, Object> payload) {
        metricsService.ingestPerf(payload);
        return ok(null);
    }

    private Map<String, Object> ok(Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", data);
        return result;
    }
}
