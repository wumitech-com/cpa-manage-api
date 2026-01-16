package com.cpa.util;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * SSH命令执行限流器
 * 使用信号量控制SSH命令的并发执行数量
 */
@Slf4j
public class SshRateLimiter {
    
    // 每个服务器的限流信号量（key: serverIp, value: Semaphore）
    private static final ConcurrentHashMap<String, Semaphore> rateLimiters = new ConcurrentHashMap<>();
    
    // 默认每个服务器最多同时执行50个SSH命令（增加并发数以支持更多设备并行注册）
    private static final int DEFAULT_MAX_CONCURRENT_COMMANDS = 50;
    
    // 获取信号量的超时时间（秒）
    private static final int ACQUIRE_TIMEOUT_SECONDS = 30;
    
    /**
     * 获取指定服务器的限流信号量
     */
    private static Semaphore getSemaphore(String serverIp) {
        return rateLimiters.computeIfAbsent(serverIp, 
            k -> new Semaphore(DEFAULT_MAX_CONCURRENT_COMMANDS, true));
    }
    
    /**
     * 尝试获取执行权限（非阻塞）
     * @param serverIp 服务器IP
     * @return 是否获取成功
     */
    public static boolean tryAcquire(String serverIp) {
        Semaphore semaphore = getSemaphore(serverIp);
        return semaphore.tryAcquire();
    }
    
    /**
     * 获取执行权限（阻塞，带超时）
     * @param serverIp 服务器IP
     * @return 是否获取成功
     */
    public static boolean acquire(String serverIp) {
        try {
            Semaphore semaphore = getSemaphore(serverIp);
            boolean acquired = semaphore.tryAcquire(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("获取SSH命令执行权限超时: {} (当前可用: {})", 
                    serverIp, semaphore.availablePermits());
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取SSH命令执行权限被中断: {}", serverIp, e);
            return false;
        }
    }
    
    /**
     * 释放执行权限
     * @param serverIp 服务器IP
     */
    public static void release(String serverIp) {
        Semaphore semaphore = rateLimiters.get(serverIp);
        if (semaphore != null) {
            semaphore.release();
        }
    }
    
    /**
     * 获取当前可用权限数
     * @param serverIp 服务器IP
     * @return 可用权限数
     */
    public static int getAvailablePermits(String serverIp) {
        Semaphore semaphore = rateLimiters.get(serverIp);
        return semaphore != null ? semaphore.availablePermits() : DEFAULT_MAX_CONCURRENT_COMMANDS;
    }
    
    /**
     * 设置指定服务器的最大并发命令数
     * @param serverIp 服务器IP
     * @param maxConcurrent 最大并发数
     */
    public static void setMaxConcurrent(String serverIp, int maxConcurrent) {
        Semaphore oldSemaphore = rateLimiters.remove(serverIp);
        if (oldSemaphore != null) {
            // 释放旧的信号量中的所有权限
            int permits = DEFAULT_MAX_CONCURRENT_COMMANDS - oldSemaphore.availablePermits();
            oldSemaphore.release(permits);
        }
        rateLimiters.put(serverIp, new Semaphore(maxConcurrent, true));
        log.info("设置服务器 {} 的最大并发SSH命令数为: {}", serverIp, maxConcurrent);
    }
    
    /**
     * 清理指定服务器的限流器
     * @param serverIp 服务器IP
     */
    public static void remove(String serverIp) {
        rateLimiters.remove(serverIp);
    }
    
    /**
     * 清理所有限流器
     */
    public static void clear() {
        rateLimiters.clear();
    }
}

