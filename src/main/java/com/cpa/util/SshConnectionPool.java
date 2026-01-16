package com.cpa.util;

import com.jcraft.jsch.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SSH连接池管理器
 * 复用SSH连接，避免频繁创建和断开连接
 */
@Slf4j
public class SshConnectionPool {
    
    /**
     * 每个Host的一组连接（多Session池）
     */
    private static class HostConnectionPool {
        private final List<ConnectionInfo> connections = new ArrayList<>();
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger nextIndex = new AtomicInteger(0);

        public ReentrantLock getLock() {
            return lock;
        }

        public List<ConnectionInfo> getConnections() {
            return connections;
        }

        /**
         * 获取可用的连接（优先选择空闲的Session）
         * 策略：1. 先找空闲的Session（锁可用且连接有效且健康）
         *       2. 如果都忙，则轮询选择（跳过不健康的连接）
         */
        public ConnectionInfo getConnectionForUse() {
            if (connections.isEmpty()) {
                return null;
            }
            
            // 策略1：优先选择空闲的Session（锁可用且连接有效且健康）
            for (ConnectionInfo connInfo : connections) {
                ReentrantLock lock = connInfo.getLock();
                if (lock.tryLock()) {
                    try {
                        Session session = connInfo.getSession();
                        if (session != null && session.isConnected() && connInfo.isHealthy()) {
                            // 找到空闲且健康的Session，立即返回
                            return connInfo;
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            }
            
            // 策略2：如果都忙，使用轮询策略（跳过不健康的连接）
            // 尝试最多size次，找到第一个健康的连接
            int size = connections.size();
            int startIndex = Math.floorMod(nextIndex.getAndIncrement(), size);
            for (int i = 0; i < size; i++) {
                int index = Math.floorMod(startIndex + i, size);
                ConnectionInfo connInfo = connections.get(index);
                // 跳过不健康的连接
                if (!connInfo.isHealthy()) {
                    continue;
                }
                return connInfo;
            }
            
            // 如果所有连接都不健康，返回null
            return null;
        }
    }
    
    /**
     * 连接信息
     */
    private static class ConnectionInfo {
        private Session session;
        private long lastUsedTime;
        private long lastHeartbeatTime; // 上次心跳检测时间
        private boolean isHealthy; // 连接健康状态
        private int heartbeatFailureCount = 0; // 心跳检测连续失败次数
        private final ReentrantLock lock = new ReentrantLock();
        
        public ConnectionInfo(String key) {
            this.lastUsedTime = System.currentTimeMillis();
            this.lastHeartbeatTime = System.currentTimeMillis();
            this.isHealthy = true;
            this.heartbeatFailureCount = 0;
        }
        
        public Session getSession() {
            return session;
        }
        
        public void setSession(Session session) {
            this.session = session;
            this.lastUsedTime = System.currentTimeMillis();
        }
        
        public long getLastUsedTime() {
            return lastUsedTime;
        }
        
        public void updateLastUsedTime() {
            this.lastUsedTime = System.currentTimeMillis();
        }
        
        public ReentrantLock getLock() {
            return lock;
        }
        
        public long getLastHeartbeatTime() {
            return lastHeartbeatTime;
        }
        
        public void setLastHeartbeatTime(long lastHeartbeatTime) {
            this.lastHeartbeatTime = lastHeartbeatTime;
        }
        
        public boolean isHealthy() {
            return isHealthy;
        }
        
        public void setHealthy(boolean healthy) {
            isHealthy = healthy;
            if (healthy) {
                // 连接恢复健康时，重置失败计数
                this.heartbeatFailureCount = 0;
            }
        }
        
        public int getHeartbeatFailureCount() {
            return heartbeatFailureCount;
        }
        
        public void setHeartbeatFailureCount(int count) {
            this.heartbeatFailureCount = count;
        }
        
        public void incrementHeartbeatFailureCount() {
            this.heartbeatFailureCount++;
        }
    }
    
    // 连接池：key格式为 "jumpHost:jumpPort:jumpUser:targetHost:targetPort:targetUser"
    // 一个key下维护多个ConnectionInfo，实现多Session池
    private static final ConcurrentHashMap<String, HostConnectionPool> connectionPool = new ConcurrentHashMap<>();
    
    // 连接最大空闲时间（毫秒），默认10分钟（增加空闲时间，减少连接重建）
    private static final long MAX_IDLE_TIME = 10 * 60 * 1000;
    
    // 心跳检测最小间隔（毫秒），如果连接最近被使用过，跳过心跳检测
    // 避免在 Session 正在使用时执行心跳检测导致误判
    private static final long HEARTBEAT_SKIP_IF_RECENTLY_USED = 60 * 1000; // 1分钟内使用过，跳过心跳检测
    
    // 连接保活间隔（毫秒），默认15秒（更频繁的保活，确保连接稳定）
    private static final long KEEP_ALIVE_INTERVAL = 15 * 1000;
    
    // 单个Host下最多维护的SSH会话数量（多Session池大小上限）
    // 增加到50，支持30台云手机并发执行注册脚本，确保每个设备都有足够的Session可用
    private static final int MAX_SESSIONS_PER_HOST = 50;
    
    // 连接池最大大小（防止连接过多）
    private static final int MAX_POOL_SIZE = 500;
    
    // 信号量：限制同时获取连接的数量（防止连接池过载）
    private static final Semaphore connectionSemaphore = new Semaphore(100, true); // 最多100个并发获取连接
    
    // 最大等待时间（毫秒），默认3秒
    private static final long MAX_WAIT_TIME_MS = 3000;
    
    // 心跳检测间隔（毫秒），默认30秒
    private static final long HEARTBEAT_INTERVAL = 30 * 1000;
    
    // 连接健康检查超时时间（毫秒），增加到3秒以适应K8s环境的网络延迟
    private static final int HEALTH_CHECK_TIMEOUT = 10000;
    
    // 连接验证缓存持续时间（毫秒），5秒内不重复验证
    private static final long VALIDATION_CACHE_DURATION_MS = 5000;
    
    // 心跳检测连续失败次数阈值，超过此阈值才标记为不健康（避免误判）
    private static final int HEARTBEAT_FAILURE_THRESHOLD = 3;
    
    // 统计信息
    private static final AtomicInteger totalConnectionsCreated = new AtomicInteger(0);
    private static final AtomicInteger totalConnectionsFailed = new AtomicInteger(0);
    private static final AtomicInteger waitingRequests = new AtomicInteger(0);
    
    // 连接验证缓存，避免频繁验证
    private static final ConcurrentHashMap<String, Long> lastValidationTime = new ConcurrentHashMap<>();
    
    /**
     * 生成连接key
     */
    private static String generateKey(String jumpHost, int jumpPort, String jumpUsername,
                                     String targetHost, int targetPort, String targetUsername) {
        if (jumpHost != null && !jumpHost.isEmpty()) {
            return String.format("%s:%d:%s:%s:%d:%s", jumpHost, jumpPort, jumpUsername, 
                               targetHost, targetPort, targetUsername);
        } else {
            return String.format("direct:%s:%d:%s", targetHost, targetPort, targetUsername);
        }
    }
    
    /**
     * 获取或创建SSH会话（跳板机连接）
     * 使用信号量控制并发获取连接的数量
     * 优化：信号量在连接真正关闭时才释放，而不是在获取连接后立即释放
     */
    public static Session getOrCreateJumpSession(String jumpHost, int jumpPort, String jumpUsername,
                                                String privateKey, String passphrase, int timeout) {
        waitingRequests.incrementAndGet();
        
        try {
            // 检查连接池大小，如果超过限制，先清理空闲连接
            if (getPoolSize() >= MAX_POOL_SIZE) {
                log.warn("连接池大小达到上限 {}，清理空闲连接", MAX_POOL_SIZE);
                cleanupIdleConnections();
            }
            
            String key = generateKey(jumpHost, jumpPort, jumpUsername, null, 0, null);
            HostConnectionPool hostPool = connectionPool.computeIfAbsent(key, k -> new HostConnectionPool());

            // 1. 使用轮询策略从当前Host的连接列表中选择可复用连接（不占用全局信号量）
            // 这样可以避免所有任务都拿到同一个session，实现负载均衡
            Session reusableSession = null;
            hostPool.getLock().lock();
            try {
                List<ConnectionInfo> connections = hostPool.getConnections();
                if (!connections.isEmpty()) {
                    // 使用轮询策略选择session，而不是总是选第一个
                    ConnectionInfo selectedConnInfo = hostPool.getConnectionForUse();
                    if (selectedConnInfo != null) {
                        ReentrantLock lock = selectedConnInfo.getLock();
                        if (lock.tryLock()) {
                            try {
                                Session session = selectedConnInfo.getSession();
                                if (session != null && session.isConnected() && isConnectionValid(selectedConnInfo, session)) {
                                    selectedConnInfo.updateLastUsedTime();
                                    reusableSession = session;
                                    if (log.isDebugEnabled()) {
                                        log.debug("复用跳板机连接（轮询选择）: {}@{}:{}", jumpUsername, jumpHost, jumpPort);
                                    }
                                }
                            } finally {
                                lock.unlock();
                            }
                        }
                    }
                    
                    // 如果轮询选中的session不可用，尝试遍历所有session找一个可用的
                    if (reusableSession == null) {
                        for (ConnectionInfo connInfo : connections) {
                            ReentrantLock lock = connInfo.getLock();
                            if (!lock.tryLock()) {
                                continue;
                            }
                            try {
                                Session session = connInfo.getSession();
                                if (session != null && session.isConnected() && isConnectionValid(connInfo, session)) {
                                    connInfo.updateLastUsedTime();
                                    reusableSession = session;
                                    if (log.isDebugEnabled()) {
                                        log.debug("复用跳板机连接（遍历找到）: {}@{}:{}", jumpUsername, jumpHost, jumpPort);
                                    }
                                    break;
                                }
                            } finally {
                                lock.unlock();
                            }
                        }
                    }
                }
            } finally {
                hostPool.getLock().unlock();
            }

            if (reusableSession != null) {
                return reusableSession;
            }
            
            // 2. 尝试获取信号量（控制并发连接数）
            if (!connectionSemaphore.tryAcquire(MAX_WAIT_TIME_MS, TimeUnit.MILLISECONDS)) {
                log.warn("获取SSH连接信号量超时，当前可用: {}", connectionSemaphore.availablePermits());
                totalConnectionsFailed.incrementAndGet();
                return null;
            }
            
            try {
                // 3. 在Host层面再次检查是否需要创建新连接（避免多个线程同时创建）
                hostPool.getLock().lock();
                try {
                    List<ConnectionInfo> connections = hostPool.getConnections();
                    // 如果此时已经有其它线程新建了可用连接，则优先复用
                    for (ConnectionInfo connInfo : connections) {
                        ReentrantLock lock = connInfo.getLock();
                        if (!lock.tryLock()) {
                            continue;
                        }
                        try {
                            Session session = connInfo.getSession();
                            if (session != null && session.isConnected() && isConnectionValid(connInfo, session)) {
                                connInfo.updateLastUsedTime();
                                connectionSemaphore.release(); // 复用已有连接，释放此次获取的许可
                                if (log.isDebugEnabled()) {
                                    log.debug("另一个线程已创建跳板机连接，复用: {}@{}:{}", jumpUsername, jumpHost, jumpPort);
                                }
                                return session;
                            }
                        } finally {
                            lock.unlock();
                        }
                    }

                    // 没有可用连接，检查是否超过限制
                    // 1. 检查当前Host下的Session数量
                    if (connections.size() >= MAX_SESSIONS_PER_HOST) {
                        log.warn("跳板机连接数已达到上限 {}: {}@{}", MAX_SESSIONS_PER_HOST, jumpUsername, jumpHost);
                        connectionSemaphore.release();
                        totalConnectionsFailed.incrementAndGet();
                        return null;
                    }
                    
                    // 2. 检查全局连接池大小
                    int currentPoolSize = getPoolSize();
                    if (currentPoolSize >= MAX_POOL_SIZE) {
                        log.warn("全局连接池已达到上限 {}，当前连接数: {}", MAX_POOL_SIZE, currentPoolSize);
                        connectionSemaphore.release();
                        totalConnectionsFailed.incrementAndGet();
                        return null;
                    }

                    // 4. 创建新连接
                    log.info("创建新的跳板机连接: {}@{}:{} (当前全局连接数: {}/{})", 
                            jumpUsername, jumpHost, jumpPort, currentPoolSize, MAX_POOL_SIZE);
                    Session session = createJumpSession(jumpHost, jumpPort, jumpUsername, privateKey, passphrase, timeout);
                    
                    if (session != null) {
                        ConnectionInfo newConnInfo = new ConnectionInfo(key);
                        newConnInfo.setSession(session);
                        newConnInfo.setHealthy(true);
                        newConnInfo.setLastHeartbeatTime(System.currentTimeMillis());
                        
                        connections.add(newConnInfo);
                        totalConnectionsCreated.incrementAndGet();
                        // 信号量保持占用状态，在连接关闭时释放
                        return session;
                    } else {
                        totalConnectionsFailed.incrementAndGet();
                        connectionSemaphore.release();
                        return null;
                    }
                } finally {
                    hostPool.getLock().unlock();
                }
            } catch (Exception e) {
                log.error("创建跳板机连接时出错: {}@{}:{}", jumpUsername, jumpHost, jumpPort, e);
                connectionSemaphore.release();
                totalConnectionsFailed.incrementAndGet();
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("获取SSH连接信号量被中断");
            totalConnectionsFailed.incrementAndGet();
            return null;
        } finally {
            waitingRequests.decrementAndGet();
        }
    }
    
    /**
     * 创建跳板机会话
     */
    private static Session createJumpSession(String jumpHost, int jumpPort, String jumpUsername,
                                            String privateKey, String passphrase, int timeout) {
        try {
            JSch jsch = new JSch();
            
            // 添加私钥
            byte[] privateKeyBytes = privateKey.getBytes("UTF-8");
            if (passphrase != null && !passphrase.isEmpty()) {
                jsch.addIdentity("privateKey", privateKeyBytes, null, passphrase.getBytes("UTF-8"));
            } else {
                jsch.addIdentity("privateKey", privateKeyBytes, null, null);
            }
            
            Session session = jsch.getSession(jumpUsername, jumpHost, jumpPort);
            
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            // 设置保活参数
            // 增加 ServerAliveCountMax 到 20，允许更长时间的网络波动（15秒 * 20 = 5分钟）
            // 这样即使有短暂网络波动，连接也不会断开
            config.put("ServerAliveInterval", String.valueOf(KEEP_ALIVE_INTERVAL / 1000));
            config.put("ServerAliveCountMax", "20");
            // 优化连接参数
            config.put("TCPKeepAlive", "yes");
            config.put("Compression", "no"); // 禁用压缩，减少CPU开销
            session.setConfig(config);
            session.setTimeout(timeout);
            
            session.connect();
            log.info("跳板机连接成功: {}@{}:{}", jumpUsername, jumpHost, jumpPort);
            
            return session;
        } catch (Exception e) {
            log.error("创建跳板机连接失败: {}@{}:{} - {}", jumpUsername, jumpHost, jumpPort, e.getMessage(), e);
            throw new RuntimeException("创建跳板机连接失败", e);
        }
    }
    
    /**
     * 获取或创建SSH会话（直接连接）
     * 使用信号量控制并发获取连接的数量
     * 优化：信号量在连接真正关闭时才释放，而不是在获取连接后立即释放
     */
    public static Session getOrCreateDirectSession(String targetHost, int targetPort, String targetUsername,
                                                  String privateKey, String passphrase, int timeout) {
        waitingRequests.incrementAndGet();
        
        try {
            // 检查连接池大小，如果超过限制，先清理空闲连接
            if (getPoolSize() >= MAX_POOL_SIZE) {
                log.warn("连接池大小达到上限 {}，清理空闲连接", MAX_POOL_SIZE);
                cleanupIdleConnections();
            }
            
            String key = generateKey(null, 0, null, targetHost, targetPort, targetUsername);
            HostConnectionPool hostPool = connectionPool.computeIfAbsent(key, k -> new HostConnectionPool());

            // 1. 使用轮询策略从当前Host的连接列表中选择可复用连接（不占用全局信号量）
            // 这样可以避免所有任务都拿到同一个session，实现负载均衡
            Session reusableSession = null;
            hostPool.getLock().lock();
            try {
                List<ConnectionInfo> connections = hostPool.getConnections();
                if (!connections.isEmpty()) {
                    // 使用轮询策略选择session，而不是总是选第一个
                    ConnectionInfo selectedConnInfo = hostPool.getConnectionForUse();
                    if (selectedConnInfo != null) {
                        ReentrantLock lock = selectedConnInfo.getLock();
                        if (lock.tryLock()) {
                            try {
                                Session session = selectedConnInfo.getSession();
                                if (session != null && session.isConnected() && isConnectionValid(selectedConnInfo, session)) {
                                    selectedConnInfo.updateLastUsedTime();
                                    reusableSession = session;
                                    if (log.isDebugEnabled()) {
                                        log.debug("复用直接连接（轮询选择）: {}@{}:{}", targetUsername, targetHost, targetPort);
                                    }
                                }
                            } finally {
                                lock.unlock();
                            }
                        }
                    }
                    
                    // 如果轮询选中的session不可用，尝试遍历所有session找一个可用的
                    if (reusableSession == null) {
                        for (ConnectionInfo connInfo : connections) {
                            ReentrantLock lock = connInfo.getLock();
                            if (!lock.tryLock()) {
                                continue;
                            }
                            try {
                                Session session = connInfo.getSession();
                                if (session != null && session.isConnected() && isConnectionValid(connInfo, session)) {
                                    connInfo.updateLastUsedTime();
                                    reusableSession = session;
                                    if (log.isDebugEnabled()) {
                                        log.debug("复用直接连接（遍历找到）: {}@{}:{}", targetUsername, targetHost, targetPort);
                                    }
                                    break;
                                }
                            } finally {
                                lock.unlock();
                            }
                        }
                    }
                }
            } finally {
                hostPool.getLock().unlock();
            }

            if (reusableSession != null) {
                return reusableSession;
            }
            
            // 2. 尝试获取信号量（控制并发连接数）
            if (!connectionSemaphore.tryAcquire(MAX_WAIT_TIME_MS, TimeUnit.MILLISECONDS)) {
                log.warn("获取SSH连接信号量超时，当前可用: {}", connectionSemaphore.availablePermits());
                totalConnectionsFailed.incrementAndGet();
                return null;
            }
            
            try {
                // 3. 在Host层面再次检查是否需要创建新连接（避免多个线程同时创建）
                hostPool.getLock().lock();
                try {
                    List<ConnectionInfo> connections = hostPool.getConnections();
                    // 如果此时已经有其它线程新建了可用连接，则优先复用
                    for (ConnectionInfo connInfo : connections) {
                        ReentrantLock lock = connInfo.getLock();
                        if (!lock.tryLock()) {
                            continue;
                        }
                        try {
                            Session session = connInfo.getSession();
                            if (session != null && session.isConnected() && isConnectionValid(connInfo, session)) {
                                connInfo.updateLastUsedTime();
                                connectionSemaphore.release(); // 复用已有连接，释放此次获取的许可
                                if (log.isDebugEnabled()) {
                                    log.debug("另一个线程已创建连接，复用: {}@{}:{}", targetUsername, targetHost, targetPort);
                                }
                                return session;
                            }
                        } finally {
                            lock.unlock();
                        }
                    }

                    // 没有可用连接，检查是否超过限制
                    // 1. 检查当前Host下的Session数量
                    if (connections.size() >= MAX_SESSIONS_PER_HOST) {
                        log.warn("SSH连接数已达到上限 {}: {}@{}", MAX_SESSIONS_PER_HOST, targetUsername, targetHost);
                        connectionSemaphore.release();
                        totalConnectionsFailed.incrementAndGet();
                        return null;
                    }
                    
                    // 2. 检查全局连接池大小
                    int currentPoolSize = getPoolSize();
                    if (currentPoolSize >= MAX_POOL_SIZE) {
                        log.warn("全局连接池已达到上限 {}，当前连接数: {}", MAX_POOL_SIZE, currentPoolSize);
                        connectionSemaphore.release();
                        totalConnectionsFailed.incrementAndGet();
                        return null;
                    }

                    // 4. 创建新连接
                    log.info("创建新的SSH连接: {}@{}:{} (当前全局连接数: {}/{})", 
                            targetUsername, targetHost, targetPort, currentPoolSize, MAX_POOL_SIZE);
                    Session session = createDirectSession(targetHost, targetPort, targetUsername, privateKey, passphrase, timeout);
                    
                    if (session != null) {
                        ConnectionInfo newConnInfo = new ConnectionInfo(key);
                        newConnInfo.setSession(session);
                        newConnInfo.setHealthy(true);
                        newConnInfo.setLastHeartbeatTime(System.currentTimeMillis());
                        
                        connections.add(newConnInfo);
                        totalConnectionsCreated.incrementAndGet();
                        // 信号量保持占用状态，在连接关闭时释放
                        return session;
                    } else {
                        totalConnectionsFailed.incrementAndGet();
                        connectionSemaphore.release();
                        return null;
                    }
                } finally {
                    hostPool.getLock().unlock();
                }
            } catch (Exception e) {
                log.error("创建SSH连接时出错: {}@{}:{}", targetUsername, targetHost, targetPort, e);
                connectionSemaphore.release();
                totalConnectionsFailed.incrementAndGet();
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("获取SSH连接信号量被中断");
            totalConnectionsFailed.incrementAndGet();
            return null;
        } finally {
            waitingRequests.decrementAndGet();
        }
    }
    
    /**
     * 创建直接会话
     */
    private static Session createDirectSession(String targetHost, int targetPort, String targetUsername,
                                              String privateKey, String passphrase, int timeout) {
        try {
            JSch jsch = new JSch();
            
            // 添加私钥
            byte[] privateKeyBytes = privateKey.getBytes("UTF-8");
            if (passphrase != null && !passphrase.isEmpty()) {
                jsch.addIdentity("privateKey", privateKeyBytes, null, passphrase.getBytes("UTF-8"));
            } else {
                jsch.addIdentity("privateKey", privateKeyBytes, null, null);
            }
            
            Session session = jsch.getSession(targetUsername, targetHost, targetPort);
            
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            // 设置保活参数
            // 增加 ServerAliveCountMax 到 20，允许更长时间的网络波动（15秒 * 20 = 5分钟）
            // 这样即使有短暂网络波动，连接也不会断开
            config.put("ServerAliveInterval", String.valueOf(KEEP_ALIVE_INTERVAL / 1000));
            config.put("ServerAliveCountMax", "20");
            // 优化连接参数
            config.put("TCPKeepAlive", "yes");
            config.put("Compression", "no"); // 禁用压缩，减少CPU开销
            session.setConfig(config);
            session.setTimeout(timeout);
            
            session.connect();
            log.info("SSH连接成功: {}@{}:{}", targetUsername, targetHost, targetPort);
            
            return session;
        } catch (Exception e) {
            log.error("创建SSH连接失败: {}@{}:{} - {}", targetUsername, targetHost, targetPort, e.getMessage(), e);
            throw new RuntimeException("创建SSH连接失败", e);
        }
    }
    
    /**
     * 检查连接是否有效（带缓存的快速检查）
     */
    private static boolean isConnectionValid(ConnectionInfo connInfo, Session session) {
        if (session == null || !session.isConnected() || !connInfo.isHealthy()) {
            String reason = session == null ? "session为null" : 
                           (!session.isConnected() ? "session未连接" : "连接已标记为不健康");
            log.debug("连接验证失败: {}, sessionId: {}", reason, session != null ? session.toString() : "null");
            return false;
        }
        
        // 检查缓存，避免频繁验证
        String sessionId = session.toString();
        long currentTime = System.currentTimeMillis();
        Long lastValidated = lastValidationTime.get(sessionId);
        
        if (lastValidated != null && (currentTime - lastValidated) < VALIDATION_CACHE_DURATION_MS) {
            log.debug("使用缓存验证结果，sessionId: {}, 上次验证时间: {}ms前", 
                    sessionId, currentTime - lastValidated);
            return true; // 使用缓存结果
        }
        
        // 如果连接最近被使用过（1分钟内），跳过心跳检测，直接认为连接有效
        // 避免在 Session 正在执行长时间命令时，心跳检测失败导致连接被误判为不健康
        long timeSinceLastUsed = currentTime - connInfo.getLastUsedTime();
        if (timeSinceLastUsed < HEARTBEAT_SKIP_IF_RECENTLY_USED) {
            // 连接最近被使用过，跳过心跳检测，直接认为有效
            log.debug("连接最近被使用过（{}ms前），跳过心跳检测，直接认为有效, sessionId: {}", 
                    timeSinceLastUsed, sessionId);
            lastValidationTime.put(sessionId, currentTime);
            return true;
        }
        
        // 检查是否需要心跳检测
        long timeSinceLastHeartbeat = currentTime - connInfo.getLastHeartbeatTime();
        if (timeSinceLastHeartbeat > HEARTBEAT_INTERVAL) {
            // 如果连接正在使用（锁被占用），跳过心跳检测，避免干扰正在执行的命令
            ReentrantLock lock = connInfo.getLock();
            if (lock.isLocked() && !lock.tryLock()) {
                // 锁被占用且无法获取，说明连接正在使用中
                log.debug("连接正在使用中，跳过心跳检测, sessionId: {}, 距离上次心跳: {}ms", 
                        sessionId, timeSinceLastHeartbeat);
                // 更新心跳时间，避免频繁检测
                connInfo.setLastHeartbeatTime(currentTime);
                lastValidationTime.put(sessionId, currentTime);
                return true; // 认为连接有效
            } else if (lock.isLocked()) {
                // 如果tryLock成功，说明锁没有被占用，立即释放
                lock.unlock();
            }
            
            log.info("开始执行心跳检测, sessionId: {}, 距离上次心跳: {}ms, 距离上次使用: {}ms, 当前失败次数: {}", 
                    sessionId, timeSinceLastHeartbeat, timeSinceLastUsed, connInfo.getHeartbeatFailureCount());
            boolean isValid = performHeartbeat(session);
            if (isValid) {
                log.info("心跳检测成功, sessionId: {}, 重置失败计数", sessionId);
                connInfo.setLastHeartbeatTime(currentTime);
                connInfo.setHeartbeatFailureCount(0); // 重置失败计数
                lastValidationTime.put(sessionId, currentTime);
            } else {
                // 心跳检测失败，增加失败计数
                connInfo.incrementHeartbeatFailureCount();
                int failureCount = connInfo.getHeartbeatFailureCount();
                
                // 如果连接最近被使用过，可能是心跳检测时 Session 正在被使用
                // 即使失败也不增加失败计数（或减少计数），避免误判
                if (timeSinceLastUsed < HEARTBEAT_SKIP_IF_RECENTLY_USED * 2) {
                    // 如果2分钟内使用过，可能是误判，减少失败计数（但不能小于0）
                    if (failureCount > 0) {
                        connInfo.setHeartbeatFailureCount(Math.max(0, failureCount - 1));
                    }
                    log.warn("心跳检测失败但连接最近被使用过（{}ms前），可能是误判，失败计数: {} -> {}, sessionId: {}", 
                            timeSinceLastUsed, failureCount, connInfo.getHeartbeatFailureCount(), sessionId);
                    connInfo.setLastHeartbeatTime(currentTime); // 更新心跳时间，避免频繁检测
                    lastValidationTime.put(sessionId, currentTime);
                    return true; // 认为连接有效
                } else {
                    // 长时间未使用且心跳失败
                    log.warn("心跳检测失败, sessionId: {}, 距离上次使用: {}ms, 失败次数: {}/{}", 
                            sessionId, timeSinceLastUsed, failureCount, HEARTBEAT_FAILURE_THRESHOLD);
                    
                    // 只有连续失败次数超过阈值才标记为不健康
                    if (failureCount >= HEARTBEAT_FAILURE_THRESHOLD) {
                        log.error("心跳检测连续失败{}次，标记连接为不健康, sessionId: {}, 距离上次使用: {}ms", 
                                failureCount, sessionId, timeSinceLastUsed);
                        connInfo.setHealthy(false);
                        lastValidationTime.remove(sessionId);
                    } else {
                        // 失败次数未达阈值，更新心跳时间，等待下次检测
                        connInfo.setLastHeartbeatTime(currentTime);
                        lastValidationTime.put(sessionId, currentTime);
                        log.debug("心跳检测失败但未达阈值，保留连接, sessionId: {}, 失败次数: {}/{}", 
                                sessionId, failureCount, HEARTBEAT_FAILURE_THRESHOLD);
                    }
                }
            }
            return isValid;
        }
        
        // 更新验证时间
        log.debug("距离上次心跳检测时间未到（{}ms < {}ms），跳过心跳检测, sessionId: {}", 
                timeSinceLastHeartbeat, HEARTBEAT_INTERVAL, sessionId);
        lastValidationTime.put(sessionId, currentTime);
        return true;
    }
    
    /**
     * 执行心跳检测（轻量级验证）
     * 使用 echo 'test' 命令，500ms 超时
     */
    private static boolean performHeartbeat(Session session) {
        if (session == null || !session.isConnected()) {
            log.warn("心跳检测失败: session为null或未连接, sessionId: {}", 
                    session != null ? session.toString() : "null");
            return false;
        }
        
        String sessionId = session.toString();
        String host = session.getHost();
        int port = session.getPort();
        
        try {
            log.debug("开始执行心跳检测, sessionId: {}, host: {}:{}", sessionId, host, port);
            Channel channel = session.openChannel("exec");
            if (channel instanceof ChannelExec) {
                ChannelExec execChannel = (ChannelExec) channel;
                execChannel.setCommand("echo 'test'");
                execChannel.setInputStream(null);
                
                long startTime = System.currentTimeMillis();
                log.debug("尝试连接心跳检测通道, sessionId: {}, host: {}:{}", sessionId, host, port);
                execChannel.connect(HEALTH_CHECK_TIMEOUT);
                long connectTime = System.currentTimeMillis() - startTime;
                
                // 快速检查
                if (connectTime > HEALTH_CHECK_TIMEOUT) {
                    log.warn("心跳检测连接超时, sessionId: {}, host: {}:{}, 耗时: {}ms, 超时阈值: {}ms", 
                            sessionId, host, port, connectTime, HEALTH_CHECK_TIMEOUT);
                    execChannel.disconnect();
                    return false;
                }
                
                log.debug("心跳检测成功, sessionId: {}, host: {}:{}, 连接耗时: {}ms", 
                        sessionId, host, port, connectTime);
                execChannel.disconnect();
                return true;
            } else {
                log.warn("心跳检测失败: 通道类型不是ChannelExec, sessionId: {}, host: {}:{}", 
                        sessionId, host, port);
                return false;
            }
        } catch (Exception e) {
            log.error("心跳检测异常, sessionId: {}, host: {}:{}, 错误: {}", 
                    sessionId, host, port, e.getMessage(), e);
        }
        
        return false;
    }
    
    /**
     * 立即清理无效连接
     */
    private static void cleanupInvalidConnection(String key, ConnectionInfo connInfo) {
        try {
            HostConnectionPool hostPool = connectionPool.get(key);
            if (hostPool == null) {
                return;
            }

            hostPool.getLock().lock();
            try {
                if (!hostPool.getConnections().remove(connInfo)) {
                    return;
                }

                // 清理验证缓存
                if (connInfo.getSession() != null) {
                    lastValidationTime.remove(connInfo.getSession().toString());
                }
                
                // 关闭连接
                Session session = connInfo.getSession();
                if (session != null && session.isConnected()) {
                    session.disconnect();
                }
                
                // 释放信号量
                connectionSemaphore.release();
                log.debug("清理无效连接: {}", key);

                // 如果该Host下已无连接，则移除Host条目
                if (hostPool.getConnections().isEmpty()) {
                    connectionPool.remove(key, hostPool);
                }
            } finally {
                hostPool.getLock().unlock();
            }
        } catch (Exception e) {
            log.warn("清理无效连接时出错: {}", key, e);
            // 即使清理失败，也要尝试释放信号量
            try {
                connectionSemaphore.release();
            } catch (Exception releaseException) {
                log.error("释放信号量失败: {}", key, releaseException);
            }
        }
    }
    
    /**
     * 检查并清理空闲连接
     * 优化：清理时释放信号量，并检查信号量计数一致性
     */
    public static void cleanupIdleConnections() {
        long currentTime = System.currentTimeMillis();
        
        connectionPool.forEach((key, hostPool) -> {
            hostPool.getLock().lock();
            try {
                List<ConnectionInfo> connections = hostPool.getConnections();
                connections.removeIf(connInfo -> {
                    ReentrantLock lock = connInfo.getLock();
                    if (!lock.tryLock()) {
                        // 无法获取锁，说明连接正在使用中，即使标记为不健康也保留
                        log.debug("连接正在使用中，跳过清理, key: {}", key);
                        return false; // 无法获取锁，保留连接
                    }
                    try {
                        Session session = connInfo.getSession();
                        long idleTime = currentTime - connInfo.getLastUsedTime();

                        // 清理条件：
                        // 1. 连接为null或已断开
                        // 2. 连接标记为不健康（被markSessionUnhealthy标记的）且空闲时间超过20分钟（避免清理正在使用的连接）
                        // 3. 空闲时间超过MAX_IDLE_TIME（10分钟）
                        boolean shouldCleanup = false;
                        String reason = "";
                        
                        if (session == null) {
                            shouldCleanup = true;
                            reason = "session为null";
                        } else if (!session.isConnected()) {
                            shouldCleanup = true;
                            reason = "session已断开";
                        } else if (!connInfo.isHealthy()) {
                            // 标记为不健康时，需要额外检查空闲时间，避免清理正在使用的连接
                            // 如果空闲时间超过20分钟，才清理（给正在使用的连接留出时间）
                            long unhealthyIdleTime = 20 * 60 * 1000; // 20分钟
                            if (idleTime > unhealthyIdleTime) {
                                shouldCleanup = true;
                                reason = String.format("session标记为不健康且空闲时间超过%d分钟", unhealthyIdleTime / 1000 / 60);
                            } else {
                                log.debug("连接标记为不健康但空闲时间较短（{}ms），可能正在使用，暂不清理, key: {}", 
                                        idleTime, key);
                                return false; // 保留连接
                            }
                        } else if (idleTime > MAX_IDLE_TIME) {
                            shouldCleanup = true;
                            reason = "空闲时间超过" + (MAX_IDLE_TIME / 1000 / 60) + "分钟";
                        }
                        
                        if (shouldCleanup) {
                            // 清理验证缓存
                            if (session != null) {
                                lastValidationTime.remove(session.toString());
                            }
                            
                            if (session != null && session.isConnected()) {
                                try {
                                    session.disconnect();
                                    log.info("清理连接: {} (原因: {}, session={})", key, reason, session);
                                } catch (Exception e) {
                                    log.warn("清理连接时出错: {}", e.getMessage());
                                }
                            }
                            
                            // 释放信号量
                            connectionSemaphore.release();
                            return true; // 移除这个连接
                        }
                        return false;
                    } finally {
                        lock.unlock();
                    }
                });

                // 如果该Host下已无连接，则从全局池中移除
                if (connections.isEmpty()) {
                    connectionPool.remove(key, hostPool);
                }
            } finally {
                hostPool.getLock().unlock();
            }
        });
        
        // 检查信号量计数一致性
        checkSemaphoreConsistency();
    }
    
    /**
     * 检查信号量计数一致性
     */
    private static void checkSemaphoreConsistency() {
        int activeConnections = getPoolSize();
        int availablePermits = connectionSemaphore.availablePermits();
        int expectedTotal = 100; // MAX_SSH_CONNECTIONS
        int actualTotal = totalConnectionsCreated.get() - (expectedTotal - availablePermits);
        
        if (Math.abs(actualTotal - activeConnections) > 10) {
            log.warn("信号量计数可能不一致 - 活跃连接: {}, 可用许可: {}, 总创建: {}", 
                    activeConnections, availablePermits, totalConnectionsCreated.get());
        }
    }
    
    /**
     * 标记特定session为不健康（用于单个session失败时，不影响其他session）
     * 
     * @param session 要标记为不健康的session
     */
    public static void markSessionUnhealthy(Session session) {
        if (session == null) {
            return;
        }
        
        // 遍历所有Host连接池，找到包含该session的连接
        connectionPool.forEach((key, hostPool) -> {
            hostPool.getLock().lock();
            try {
                List<ConnectionInfo> connections = hostPool.getConnections();
                for (ConnectionInfo connInfo : connections) {
                    ReentrantLock lock = connInfo.getLock();
                    if (!lock.tryLock()) {
                        continue; // 无法获取锁，跳过
                    }
                    try {
                        if (connInfo.getSession() == session) {
                            // 找到匹配的session，标记为不健康
                            connInfo.setHealthy(false);
                            // 清理验证缓存
                            lastValidationTime.remove(session.toString());
                            log.warn("标记session为不健康: {} (session={})", key, session);
                            return; // 找到后退出
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            } finally {
                hostPool.getLock().unlock();
            }
        });
    }
    
    /**
     * 关闭指定连接（保留此方法以兼容旧代码，但不推荐使用）
     * 推荐使用 markSessionUnhealthy 来标记单个session失败
     */
    public static void closeConnection(String jumpHost, int jumpPort, String jumpUsername,
                                      String targetHost, int targetPort, String targetUsername) {
        String key = generateKey(jumpHost, jumpPort, jumpUsername, targetHost, targetPort, targetUsername);
        HostConnectionPool hostPool = connectionPool.remove(key);
        
        if (hostPool != null) {
            hostPool.getLock().lock();
            try {
                for (ConnectionInfo connInfo : hostPool.getConnections()) {
                    ReentrantLock lock = connInfo.getLock();
                    lock.lock();
                    try {
                        Session session = connInfo.getSession();
                        if (session != null && session.isConnected()) {
                            session.disconnect();
                            log.info("关闭SSH连接: {} (session={})", key, session);
                        }
                    } finally {
                        lock.unlock();
                    }
                }
                hostPool.getConnections().clear();
            } finally {
                hostPool.getLock().unlock();
            }
        }
    }
    
    /**
     * 关闭所有连接
     */
    public static void closeAllConnections() {
        connectionPool.forEach((key, hostPool) -> {
            hostPool.getLock().lock();
            try {
                for (ConnectionInfo connInfo : hostPool.getConnections()) {
                    ReentrantLock lock = connInfo.getLock();
                    lock.lock();
                    try {
                        Session session = connInfo.getSession();
                        if (session != null && session.isConnected()) {
                            session.disconnect();
                        }
                    } finally {
                        lock.unlock();
                    }
                }
                hostPool.getConnections().clear();
            } finally {
                hostPool.getLock().unlock();
            }
        });
        connectionPool.clear();
        log.info("已关闭所有SSH连接");
    }
    
    /**
     * 获取连接池大小
     */
    public static int getPoolSize() {
        return connectionPool.values().stream()
                .mapToInt(hostPool -> hostPool.getConnections().size())
                .sum();
    }
    
    /**
     * 获取连接池统计信息
     */
    public static String getConnectionPoolStats() {
        int activeConnections = getPoolSize();
        int availablePermits = connectionSemaphore.availablePermits();
        int waitingRequestsCount = waitingRequests.get();
        int totalCreated = totalConnectionsCreated.get();
        int totalFailed = totalConnectionsFailed.get();
        
        return String.format(
            "SSH连接池统计: [活跃连接: %d, 可用许可: %d, 等待请求: %d, " +
            "总创建: %d, 总失败: %d, 验证缓存大小: %d]",
            activeConnections, availablePermits, waitingRequestsCount,
            totalCreated, totalFailed, lastValidationTime.size()
        );
    }
    
    /**
     * 记录连接池统计信息（用于日志）
     */
    public static void logConnectionPoolStats() {
        int activeCount = getPoolSize();
        int availablePermits = connectionSemaphore.availablePermits();
        int waiting = waitingRequests.get();
        
        log.info("SSH连接池状态: {}/{} 活跃连接, {} 可用许可, {} 等待请求", 
                activeCount, MAX_POOL_SIZE, availablePermits, waiting);
    }
}

