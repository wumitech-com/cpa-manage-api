package com.cpa.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ConsoleMetaService {

    public Map<String, Object> bootstrap(String username) {
        List<String> permissions = List.of(
                "view:overview",
                "view:block-rate",
                "view:task",
                "task:stop",
                "task:resume",
                "task:update",
                "view:account",
                "view:window",
                "view:retention",
                "view:device"
        );

        List<Map<String, String>> menus = List.of(
                Map.of("label", "总览看板", "path", "/overview", "permission", "view:overview"),
                Map.of("label", "封号率分析", "path", "/block-rate", "permission", "view:block-rate"),
                Map.of("label", "任务管理", "path", "/task", "permission", "view:task"),
                Map.of("label", "账号管理", "path", "/account", "permission", "view:account"),
                Map.of("label", "开窗管理", "path", "/window", "permission", "view:window"),
                Map.of("label", "留存信息", "path", "/retention", "permission", "view:retention"),
                Map.of("label", "设备巡检", "path", "/device", "permission", "view:device")
        );

        return Map.of(
                "username", username == null || username.isBlank() ? "unknown" : username,
                "permissions", permissions,
                "menus", menus,
                "featureFlags", Map.of(
                        "enableTaskBulkOps", true,
                        "enableAuditPanel", true
                )
        );
    }
}
