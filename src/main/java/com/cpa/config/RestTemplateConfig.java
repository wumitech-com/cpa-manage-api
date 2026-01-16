package com.cpa.config;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate配置（使用Apache HttpClient连接池）
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(ClientHttpRequestFactory factory) {
        return new RestTemplate(factory);
    }

    @Bean
    public ClientHttpRequestFactory httpRequestFactory() {
        // 配置连接池
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(200); // 最大连接数
        connectionManager.setDefaultMaxPerRoute(50); // 每个路由的最大连接数（针对同一目标主机的并发连接数）
        
        // 配置请求超时
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(60)) // 连接超时60秒
                .setConnectionRequestTimeout(Timeout.ofSeconds(30)) // 从连接池获取连接的超时时间30秒
                .setResponseTimeout(Timeout.ofSeconds(600)) // 响应超时10分钟（ResetPhoneEnv可能需要较长时间）
                .build();
        
        // 创建HttpClient
        HttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictIdleConnections(Timeout.ofSeconds(30)) // 清理空闲连接
                .evictExpiredConnections() // 清理过期连接
                .build();
        
        // 使用HttpComponentsClientHttpRequestFactory
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        return factory;
    }
}

