package com.cpa.service;

import io.grpc.ManagedChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import phone_center.PhoneCenterGrpc;
import phone_center.PhoneCenterOuterClass;

import java.util.UUID;

/**
 * PhoneCenter gRPC服务
 */
@Slf4j
@Service
public class PhoneCenterGrpcService {

    private final ManagedChannel channel;
    
    @Value("${grpc.phone-center.default-sdk:}")
    private String defaultSdk;
    
    @Value("${grpc.phone-center.default-country-code:US}")
    private String defaultCountryCode;
    
    @Value("${grpc.phone-center.default-app-id:}")
    private String defaultAppId;

    public PhoneCenterGrpcService(@Qualifier("phoneCenterChannel") ManagedChannel channel) {
        this.channel = channel;
    }

    /**
     * 获取快速换机JSON
     * 
     * @param sdk SDK版本
     * @param countryCode 国家代码
     * @param appId 应用ID
     * @param needInstallApps 是否需要安装的app列表
     * @return 换机JSON字符串
     */
    public String getFastSwitchJson(String sdk, String countryCode, String appId, boolean needInstallApps) {
        try {
            PhoneCenterGrpc.PhoneCenterBlockingStub stub = PhoneCenterGrpc.newBlockingStub(channel);
            
            // 创建Atom（trace_id）
            PhoneCenterOuterClass.Atom atom = PhoneCenterOuterClass.Atom.newBuilder()
                    .setTraceId(UUID.randomUUID().toString())
                    .build();
            
            // 创建请求
            PhoneCenterOuterClass.GetFastSwitchJsonRequest.Builder requestBuilder = 
                    PhoneCenterOuterClass.GetFastSwitchJsonRequest.newBuilder();
            requestBuilder.setAtom(atom);
            
            // 设置其他参数
            // sdk参数
            if (sdk != null && !sdk.isEmpty()) {
                requestBuilder.setSdk(sdk);
            } else if (defaultSdk != null && !defaultSdk.isEmpty()) {
                requestBuilder.setSdk(defaultSdk);
            } else {
                // 如果都没有，使用默认值33
                requestBuilder.setSdk("33");
            }
            
            // country_code参数
            if (countryCode != null && !countryCode.isEmpty()) {
                requestBuilder.setCountryCode(countryCode);
            } else {
                requestBuilder.setCountryCode(defaultCountryCode);
            }
            
            // app_id参数（必填，不能为空）
            if (appId != null && !appId.isEmpty()) {
                requestBuilder.setAppId(appId);
            } else if (defaultAppId != null && !defaultAppId.isEmpty()) {
                requestBuilder.setAppId(defaultAppId);
            } else {
                // 如果都没有，使用TikTok的默认包名
                requestBuilder.setAppId("com.zhiliaoapp.musically");
                log.warn("appId参数为空，使用默认值: com.zhiliaoapp.musically");
            }
            
            requestBuilder.setNeedInstallApps(needInstallApps);
            
            PhoneCenterOuterClass.GetFastSwitchJsonRequest request = requestBuilder.build();
            
            // 调用gRPC服务
            log.info("调用GetFastSwitchJson, sdk: {}, countryCode: {}, appId: {}, needInstallApps: {}", 
                    request.getSdk(), request.getCountryCode(), request.getAppId(), request.getNeedInstallApps());
            
            PhoneCenterOuterClass.GetFastSwitchJsonResponse response = stub.getFastSwitchJson(request);
            
            String phoneJson = response.getPhoneJson();
            log.info("获取换机JSON成功, 耗时: {}ms, phoneJson长度: {}", 
                    response.getDurationMs(), phoneJson != null ? phoneJson.length() : 0);
            
            return phoneJson;
            
        } catch (Exception e) {
            log.error("获取换机JSON失败", e);
            throw new RuntimeException("获取换机JSON失败: " + e.getMessage(), e);
        }
    }
}

