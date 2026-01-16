package com.cpa.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;

/**
 * gRPC客户端配置
 */
@Slf4j
@Configuration
public class GrpcClientConfig {

    @Value("${grpc.phone-center.host:localhost}")
    private String phoneCenterHost;

    @Value("${grpc.phone-center.port:50051}")
    private int phoneCenterPort;

    private ManagedChannel phoneCenterChannel;

    /**
     * 创建PhoneCenter服务的gRPC通道
     */
    @Bean(name = "phoneCenterChannel")
    public ManagedChannel phoneCenterChannel() {
        if (phoneCenterChannel == null) {
            log.info("创建PhoneCenter gRPC通道: {}:{}", phoneCenterHost, phoneCenterPort);
            phoneCenterChannel = ManagedChannelBuilder.forAddress(phoneCenterHost, phoneCenterPort)
                    .usePlaintext() // 使用明文传输，生产环境建议使用TLS
                    .build();
        }
        return phoneCenterChannel;
    }

    /**
     * 应用关闭时关闭gRPC通道
     */
    @PreDestroy
    public void shutdown() {
        if (phoneCenterChannel != null && !phoneCenterChannel.isShutdown()) {
            log.info("关闭PhoneCenter gRPC通道");
            phoneCenterChannel.shutdown();
        }
    }
}



