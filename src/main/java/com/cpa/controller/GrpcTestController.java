package com.cpa.controller;

import com.cpa.service.PhoneCenterGrpcService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * gRPC测试控制器（用于测试gRPC连接）
 */
@Slf4j
@RestController
@RequestMapping("/api/grpc-test")
@RequiredArgsConstructor
public class GrpcTestController {

    private final PhoneCenterGrpcService phoneCenterGrpcService;

    /**
     * 测试获取换机JSON
     * 
     * GET /api/grpc-test/get-fast-switch-json
     * 或者
     * POST /api/grpc-test/get-fast-switch-json
     * {
     *   "sdk": "33",
     *   "countryCode": "US",
     *   "appId": "com.zhiliaoapp.musically",
     *   "needInstallApps": false
     * }
     */
    @GetMapping("/get-fast-switch-json")
    @PostMapping("/get-fast-switch-json")
    public Map<String, Object> testGetFastSwitchJson(
            @RequestParam(required = false) String sdk,
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) String appId,
            @RequestParam(required = false, defaultValue = "false") Boolean needInstallApps,
            @RequestBody(required = false) Map<String, Object> body) {
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("收到gRPC测试请求，参数: sdk={}, countryCode={}, appId={}, needInstallApps={}", 
                    sdk, countryCode, appId, needInstallApps);
            
            // 如果body不为空，从body中获取参数
            if (body != null) {
                sdk = sdk != null ? sdk : (String) body.get("sdk");
                countryCode = countryCode != null ? countryCode : (String) body.get("countryCode");
                appId = appId != null ? appId : (String) body.get("appId");
                if (body.get("needInstallApps") != null) {
                    needInstallApps = (Boolean) body.get("needInstallApps");
                }
            }
            
            // 调用gRPC服务
            String phoneJson = phoneCenterGrpcService.getFastSwitchJson(
                    sdk,
                    countryCode != null ? countryCode : "US",
                    appId,
                    needInstallApps != null ? needInstallApps : false
            );
            
            result.put("success", true);
            result.put("message", "获取换机JSON成功");
            result.put("phoneJsonLength", phoneJson != null ? phoneJson.length() : 0);
            
            // 只返回JSON的前1000个字符，避免响应过大
            if (phoneJson != null && phoneJson.length() > 1000) {
                result.put("phoneJsonPreview", phoneJson.substring(0, 1000) + "...");
                result.put("fullJsonAvailable", true);
            } else {
                result.put("phoneJsonPreview", phoneJson);
                result.put("fullJsonAvailable", false);
            }
            
            // 完整JSON可以通过日志查看
            log.info("获取换机JSON成功，长度: {}", phoneJson != null ? phoneJson.length() : 0);
            
        } catch (Exception e) {
            log.error("测试gRPC调用失败", e);
            result.put("success", false);
            result.put("message", "获取换机JSON失败: " + e.getMessage());
            result.put("error", e.getClass().getSimpleName());
            result.put("errorDetail", e.getMessage());
            
            // 如果是连接错误，给出提示
            if (e.getMessage() != null && e.getMessage().contains("UNAVAILABLE")) {
                result.put("hint", "无法连接到gRPC服务器，请检查服务器地址和网络连接");
            }
        }
        
        return result;
    }
}



