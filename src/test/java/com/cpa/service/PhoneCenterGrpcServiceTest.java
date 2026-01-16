package com.cpa.service;

import com.cpa.CpaManageApiApplication;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PhoneCenter gRPC服务测试
 * 
 * 注意：运行此测试需要确保gRPC服务器(10.13.16.221:28080)可访问
 */
@Slf4j
@SpringBootTest(classes = CpaManageApiApplication.class)
@ActiveProfiles("dev")
public class PhoneCenterGrpcServiceTest {

    @Autowired
    private PhoneCenterGrpcService phoneCenterGrpcService;

    /**
     * 测试获取换机JSON
     */
    @Test
    public void testGetFastSwitchJson() {
        try {
            log.info("==================== 开始测试获取换机JSON ====================");
            
            // 调用gRPC服务获取换机json
            // 参数可以根据实际情况调整
            String phoneJson = phoneCenterGrpcService.getFastSwitchJson(
                null,           // sdk - 使用默认值或传null
                "US",           // countryCode - 国家代码
                null,           // appId - 使用默认值或传null
                false           // needInstallApps - 是否需要安装的app列表
            );
            
            log.info("==================== 获取换机JSON成功！ ====================");
            log.info("换机JSON长度: {}", phoneJson != null ? phoneJson.length() : 0);
            
            if (phoneJson != null && phoneJson.length() > 500) {
                log.info("换机JSON内容（前500个字符）: {}", phoneJson.substring(0, 500));
            } else {
                log.info("换机JSON内容: {}", phoneJson);
            }
            
            // 验证结果 - 使用JUnit断言
            assertNotNull(phoneJson, "换机JSON不能为空");
            assertFalse(phoneJson.isEmpty(), "换机JSON不能为空字符串");
            
            log.info("==================== 测试通过！换机JSON获取成功 ====================");
            
        } catch (Exception e) {
            log.error("==================== 测试失败 ====================", e);
            log.error("错误信息: {}", e.getMessage());
            
            // 如果是连接错误，给出提示
            if (e.getMessage() != null && e.getMessage().contains("UNAVAILABLE")) {
                log.error("无法连接到gRPC服务器，请检查：");
                log.error("1. gRPC服务器地址是否正确: 10.13.16.221:28080");
                log.error("2. 网络是否可达");
                log.error("3. gRPC服务器是否已启动");
            }
            
            throw new RuntimeException("测试失败: " + e.getMessage(), e);
        }
    }

    /**
     * 测试获取换机JSON（带完整参数）
     */
    @Test
    public void testGetFastSwitchJsonWithParams() {
        try {
            log.info("==================== 开始测试获取换机JSON（带完整参数） ====================");
            
            // 调用gRPC服务获取换机json，传入完整参数
            String phoneJson = phoneCenterGrpcService.getFastSwitchJson(
                "33",           // sdk - SDK版本
                "US",           // countryCode - 国家代码
                "com.zhiliaoapp.musically",  // appId - TikTok应用ID
                true            // needInstallApps - 需要安装的app列表
            );
            
            log.info("==================== 获取换机JSON成功！ ====================");
            log.info("换机JSON长度: {}", phoneJson != null ? phoneJson.length() : 0);
            
            // 验证结果
            assertNotNull(phoneJson, "换机JSON不能为空");
            assertFalse(phoneJson.isEmpty(), "换机JSON不能为空字符串");
            
            log.info("==================== 测试通过！ ====================");
            
        } catch (Exception e) {
            log.error("==================== 测试失败 ====================", e);
            log.error("错误信息: {}", e.getMessage());
            
            // 如果是连接错误，给出提示
            if (e.getMessage() != null && e.getMessage().contains("UNAVAILABLE")) {
                log.error("无法连接到gRPC服务器，请检查：");
                log.error("1. gRPC服务器地址是否正确: 10.13.16.221:28080");
                log.error("2. 网络是否可达");
                log.error("3. gRPC服务器是否已启动");
            }
            
            throw new RuntimeException("测试失败: " + e.getMessage(), e);
        }
    }
}

