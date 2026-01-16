package com.cpa.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;

/**
 * JVM关闭钩子
 * 在服务异常退出时记录日志
 */
@Slf4j
@Component
public class ShutdownHook {

    @EventListener(ApplicationReadyEvent.class)
    public void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                log.error("=== JVM正在关闭 ===");
                log.error("关闭时间: {}", LocalDateTime.now());
                log.error("可能原因: 系统kill、OOM、未捕获异常或正常关闭");
                
                // 写入独立文件，防止日志系统已关闭
                try (PrintWriter writer = new PrintWriter(new FileWriter("logs/shutdown.log", true))) {
                    writer.println("=== JVM Shutdown at " + LocalDateTime.now() + " ===");
                    writer.println("Available memory: " + Runtime.getRuntime().freeMemory() / 1024 / 1024 + " MB");
                    writer.println("Total memory: " + Runtime.getRuntime().totalMemory() / 1024 / 1024 + " MB");
                    writer.println("Max memory: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MB");
                    writer.println("Active threads: " + Thread.activeCount());
                    writer.println("==========================================");
                    writer.flush();
                }
                
            } catch (Exception e) {
                System.err.println("ShutdownHook执行失败: " + e.getMessage());
            }
        }, "ShutdownHook-Thread"));
        
        log.info("JVM关闭钩子已注册");
    }
}

