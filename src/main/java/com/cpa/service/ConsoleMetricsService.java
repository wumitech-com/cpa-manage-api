package com.cpa.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class ConsoleMetricsService {

    public void ingestPerf(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        log.info("console_perf name={} path={} durationMs={} success={}",
                payload.getOrDefault("name", ""),
                payload.getOrDefault("path", ""),
                payload.getOrDefault("durationMs", 0),
                payload.getOrDefault("success", false));
    }
}
