package com.cpa.util;

import com.jcraft.jsch.*;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSH工具类（仅支持私钥认证）
 */
@Slf4j
public class SshUtil {
    /**
     * 生成限流key。
     * 有跳板机时按“跳板机 -> 目标机”维度限流，避免所有目标机共享同一个桶。
     */
    private static String buildRateLimitKey(String host, String jumpHost) {
        if (jumpHost != null && !jumpHost.isEmpty()) {
            return jumpHost + "->" + host;
        }
        return host;
    }

    /**
     * 执行SSH命令（使用私钥认证，支持跳板机）
     *
     * @param host          服务器地址
     * @param port          端口
     * @param username      用户名
     * @param privateKey    私钥内容（PEM格式字符串）
     * @param passphrase    私钥密码（如果有）
     * @param command       要执行的命令
     * @param timeout       超时时间（毫秒）
     * @return 命令执行结果
     */
    public static SshResult executeCommandWithPrivateKey(String host, int port, String username, 
                                                        String privateKey, String passphrase,
                                                        String command, int timeout) {
        return executeCommandWithPrivateKey(host, port, username, privateKey, passphrase, 
                                          command, timeout, null, 0, null, null);
    }

    /**
     * 执行SSH命令（使用私钥认证，支持跳板机）
     *
     * @param host          目标服务器地址
     * @param port          目标端口
     * @param username      目标用户名
     * @param privateKey    私钥内容（PEM格式字符串）
     * @param passphrase    私钥密码（如果有）
     * @param command       要执行的命令
     * @param timeout       超时时间（毫秒）
     * @param jumpHost      跳板机地址（可选）
     * @param jumpPort      跳板机端口
     * @param jumpUsername  跳板机用户名
     * @param jumpPassword  跳板机密码（可选，如果跳板机也用私钥则为null）
     * @return 命令执行结果
     */
    public static SshResult executeCommandWithPrivateKey(String host, int port, String username, 
                                                        String privateKey, String passphrase,
                                                        String command, int timeout,
                                                        String jumpHost, int jumpPort, 
                                                        String jumpUsername, String jumpPassword) {
        // 限流：获取执行权限
        String serverKey = buildRateLimitKey(host, jumpHost);
        if (!SshRateLimiter.acquire(serverKey)) {
            SshResult result = new SshResult();
            result.setSuccess(false);
            result.setErrorMessage("获取SSH命令执行权限超时");
            log.error("SSH命令执行被限流: {} -> {}", serverKey, command);
            return result;
        }
        
        Session jumpSession = null;
        Session session = null;
        ChannelExec channel = null;
        SshResult result = new SshResult();
        
        try {
            // 如果有跳板机，使用连接池获取跳板机会话
            if (jumpHost != null && !jumpHost.isEmpty()) {
                try {
                    jumpSession = SshConnectionPool.getOrCreateJumpSession(
                        jumpHost, jumpPort, jumpUsername, privateKey, passphrase, timeout);

                    if (jumpSession == null) {
                        // 连接池可能因为并发/上限等原因返回 null；这里不要继续 openChannel，避免 NPE
                        log.warn("SSH跳板机会话获取失败: jumpSession为null, jump={}@{} target={}@{}",
                                jumpUsername, jumpHost, username, host);
                        result.setSuccess(false);
                        result.setErrorMessage("SSH跳板机会话获取失败: session为null");
                        return result;
                    }
                    
                    // 减少日志输出
                    if (log.isDebugEnabled()) {
                        log.debug("通过跳板机执行命令: {}@{}:{}", username, host, port);
                        log.debug("执行SSH命令: ssh {}@{} <command>", username, host);
                    }
                    
                    // 在跳板机上执行ssh命令连接目标服务器
                    String sshCommand = String.format("ssh -o StrictHostKeyChecking=no %s@%s '%s'", 
                                                    username, host, command.replace("'", "'\"'\"'"));
                    
                    channel = (ChannelExec) jumpSession.openChannel("exec");
                    channel.setCommand(sshCommand);
                    
                } catch (Exception e) {
                    // 连接池获取失败，记录错误但不关闭连接（让连接池自己清理）
                    log.warn("从连接池获取跳板机会话失败: {}", e.getMessage());
                    throw e;
                }
                
            } else {
                // 直接连接，使用连接池
                try {
                    session = SshConnectionPool.getOrCreateDirectSession(
                        host, port, username, privateKey, passphrase, timeout);

                    if (session == null) {
                        // 连接池可能因为并发/上限等原因返回 null；这里不要继续 openChannel，避免 NPE
                        log.warn("SSH目标会话获取失败: session为null, target={}@{}:{}", username, host, port);
                        result.setSuccess(false);
                        result.setErrorMessage("SSH会话获取失败: session为null（连接池可能已耗尽或等待信号量超时）");
                        return result;
                    }
                    
                    // 减少日志输出
                    if (log.isDebugEnabled()) {
                        log.debug("执行SSH命令: {}@{}:{}", username, host, port);
                    }
                    
                    // 执行命令
                    channel = (ChannelExec) session.openChannel("exec");
                    channel.setCommand(command);
                    
                } catch (Exception e) {
                    // 连接池获取失败，记录错误但不关闭连接（让连接池自己清理）
                    log.warn("从连接池获取SSH会话失败: {}", e.getMessage());
                    throw e;
                }
            }
            
            // 获取输出流
            InputStream inputStream = channel.getInputStream();
            InputStream errorStream = channel.getErrStream();
            
            channel.connect();
            // 减少日志输出，只在debug级别输出命令详情
            if (log.isDebugEnabled()) {
                log.debug("执行命令: {}", command);
            }
            
            // 使用多线程并行读取stdout和stderr，避免阻塞
            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();
            AtomicBoolean stdoutFinished = new AtomicBoolean(false);
            AtomicBoolean stderrFinished = new AtomicBoolean(false);
            
            // 读取标准输出的线程
            Thread stdoutThread = new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append("\n");
                        }
                        if (log.isDebugEnabled()) {
                            log.debug("输出: {}", line);
                        }
                    }
                } catch (Exception e) {
                    log.warn("读取标准输出异常: {}", e.getMessage());
                } finally {
                    stdoutFinished.set(true);
                }
            }, "ssh-stdout-reader");
            
            // 读取错误输出的线程
            Thread stderrThread = new Thread(() -> {
                try {
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(errorStream, "UTF-8"));
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        synchronized (errorOutput) {
                            errorOutput.append(line).append("\n");
                        }
                    }
                } catch (Exception e) {
                    log.warn("读取错误输出异常: {}", e.getMessage());
                } finally {
                    stderrFinished.set(true);
                }
            }, "ssh-stderr-reader");
            
            // 启动读取线程
            stdoutThread.start();
            stderrThread.start();
            
            // 等待通道关闭或超时（最多等待timeout毫秒）
            long startTime = System.currentTimeMillis();
            long maxWaitTime = timeout;
            
            while (!channel.isClosed() && (System.currentTimeMillis() - startTime) < maxWaitTime) {
                try {
                    Thread.sleep(100); // 每100ms检查一次
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("等待命令执行完成被中断");
                    break;
                }
            }
            
            // 等待读取线程完成（最多等待5秒）
            try {
                stdoutThread.join(5000);
                stderrThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待读取线程完成被中断");
            }
            
            // 如果读取线程还在运行，说明可能有问题，记录警告
            if (stdoutThread.isAlive() || stderrThread.isAlive()) {
                log.warn("读取线程未在5秒内完成，可能存在阻塞");
            }
            
            // 获取退出状态
            int exitStatus = channel.getExitStatus();
            
            // 重要：如果通道已关闭但退出状态是0，且读取线程还在运行，说明可能SSH命令提前返回了
            // 但远程脚本可能还在执行。需要额外等待一段时间，确保输出完全读取
            if (channel.isClosed() && exitStatus == 0 && (stdoutThread.isAlive() || stderrThread.isAlive())) {
                log.warn("通道已关闭且退出状态为0，但读取线程仍在运行，可能远程脚本还在执行，继续等待输出...");
                // 额外等待最多30秒，确保输出完全读取
                long extraWaitStart = System.currentTimeMillis();
                long extraWaitTime = 30000; // 30秒
                while ((stdoutThread.isAlive() || stderrThread.isAlive()) && 
                       (System.currentTimeMillis() - extraWaitStart) < extraWaitTime) {
                    try {
                        Thread.sleep(500); // 每500ms检查一次
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                
                // 如果读取线程还在运行，说明可能有问题
                if (stdoutThread.isAlive() || stderrThread.isAlive()) {
                    log.warn("额外等待{}秒后，读取线程仍在运行，可能存在阻塞或远程脚本仍在执行", extraWaitTime / 1000);
                }
            }
            
            // 如果退出状态是-1且通道已关闭，说明可能是跳板机SSH命令的退出状态未正确传递
            if (exitStatus == -1 && channel.isClosed()) {
                log.warn("通道已关闭但退出状态为-1（可能是跳板机SSH命令的退出状态未正确传递），返回-1由调用方判断");
            } else if (exitStatus == -1) {
                log.warn("等待超时后退出状态仍为-1且通道未关闭，返回-1由调用方判断");
            }
            
            log.info("命令执行完成，退出状态: {}, 通道已关闭: {}, 读取线程状态: stdout={}, stderr={}", 
                    exitStatus, channel.isClosed(), stdoutThread.isAlive(), stderrThread.isAlive());
            
            result.setSuccess(exitStatus == 0);
            result.setExitCode(exitStatus);
            result.setOutput(output.toString());
            result.setErrorOutput(errorOutput.toString());
            
        } catch (JSchException e) {
            log.error("SSH连接失败(私钥认证): {}", e.getMessage(), e);
            result.setSuccess(false);
            result.setErrorMessage("SSH连接失败: " + e.getMessage());
            // 标记session为不健康，而不是关闭整个Host的所有连接
            if (jumpSession != null) {
                SshConnectionPool.markSessionUnhealthy(jumpSession);
            }
            if (session != null) {
                SshConnectionPool.markSessionUnhealthy(session);
            }
        } catch (Exception e) {
            log.error("执行命令失败(私钥认证): {}", e.getMessage(), e);
            result.setSuccess(false);
            result.setErrorMessage("执行命令失败: " + e.getMessage());
            // 标记session为不健康，而不是关闭整个Host的所有连接
            if (jumpSession != null) {
                SshConnectionPool.markSessionUnhealthy(jumpSession);
            }
            if (session != null) {
                SshConnectionPool.markSessionUnhealthy(session);
            }
        } finally {
            // 只关闭通道，不关闭会话（会话保留在连接池中）
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
                log.debug("关闭SSH通道");
            }
            
            // 不再在finally中关闭连接，让连接池自己管理
            // 如果session失败，已经在catch块中标记为不健康，连接池的清理机制会自动清理
            
            // 释放限流信号量
            SshRateLimiter.release(serverKey);
        }
        
        return result;
    }

    /**
     * 执行SSH命令（后台异步执行，适合长时间任务）
     * 使用nohup在后台执行，立即返回，不等待命令完成
     *
     * @param host          服务器地址
     * @param port          端口
     * @param username      用户名
     * @param privateKey    私钥内容（PEM格式字符串）
     * @param passphrase    私钥密码（如果有）
     * @param command       要执行的命令
     * @param logFile       日志文件路径（nohup输出）
     * @param timeout       超时时间（毫秒）
     * @return 命令执行结果（包含后台进程ID）
     */
    public static SshResult executeCommandAsync(String host, int port, String username, 
                                               String privateKey, String passphrase,
                                               String command, String logFile, int timeout) {
        return executeCommandAsync(host, port, username, privateKey, passphrase, 
                                 command, logFile, timeout, null, 0, null, null);
    }

    /**
     * 执行SSH命令（后台异步执行，支持跳板机）
     *
     * @param host          目标服务器地址
     * @param port          目标端口
     * @param username      目标用户名
     * @param privateKey    私钥内容（PEM格式字符串）
     * @param passphrase    私钥密码（如果有）
     * @param command       要执行的命令
     * @param logFile       日志文件路径（nohup输出）
     * @param timeout       超时时间（毫秒）
     * @param jumpHost      跳板机地址（可选）
     * @param jumpPort      跳板机端口
     * @param jumpUsername  跳板机用户名
     * @param jumpPassword  跳板机密码（可选）
     * @return 命令执行结果（包含后台进程ID）
     */
    public static SshResult executeCommandAsync(String host, int port, String username, 
                                               String privateKey, String passphrase,
                                               String command, String logFile, int timeout,
                                               String jumpHost, int jumpPort, 
                                               String jumpUsername, String jumpPassword) {
        Session jumpSession = null;
        Session session = null;
        ChannelExec channel = null;
        SshResult result = new SshResult();
        boolean shouldCloseConnection = false; // 标记是否需要关闭连接（仅在异常时关闭）
        
        try {
            // 如果有跳板机，使用连接池获取跳板机会话
            if (jumpHost != null && !jumpHost.isEmpty()) {
                try {
                    jumpSession = SshConnectionPool.getOrCreateJumpSession(
                        jumpHost, jumpPort, jumpUsername, privateKey, passphrase, timeout);

                    if (jumpSession == null) {
                        log.warn("SSH跳板机会话获取失败(异步): jumpSession为null, jump={}@{} target={}@{}",
                                jumpUsername, jumpHost, username, host);
                        result.setSuccess(false);
                        result.setErrorMessage("SSH跳板机会话获取失败(异步): session为null");
                        return result;
                    }
                    
                    log.info("通过跳板机执行异步命令: {}@{}:{}", username, host, port);
                    
                    // 构建nohup命令，在后台执行
                    // 使用双引号,对$!进行转义确保能正确获取后台进程ID
                    String asyncCommand = String.format("bash -c \"nohup %s > %s 2>&1 & echo \\$!\"", 
                        command.replace("\"", "\\\""), logFile);
                    
                    // 在跳板机上执行ssh命令连接目标服务器并执行异步命令
                    String sshCommand = String.format("ssh -o StrictHostKeyChecking=no %s@%s '%s'", 
                                                    username, host, asyncCommand.replace("'", "'\"'\"'"));
                    log.info("执行SSH异步命令: ssh {}@{} nohup <command>", username, host);
                    
                    channel = (ChannelExec) jumpSession.openChannel("exec");
                    channel.setCommand(sshCommand);
                    
                } catch (Exception e) {
                    log.warn("从连接池获取跳板机会话失败，尝试重新创建: {}", e.getMessage());
                    SshConnectionPool.closeConnection(jumpHost, jumpPort, jumpUsername, null, 0, null);
                    shouldCloseConnection = true;
                    throw e;
                }
                
            } else {
                // 直接连接，使用连接池
                try {
                    session = SshConnectionPool.getOrCreateDirectSession(
                        host, port, username, privateKey, passphrase, timeout);

                    if (session == null) {
                        log.warn("SSH目标会话获取失败(异步): session为null, target={}@{}:{}", username, host, port);
                        result.setSuccess(false);
                        result.setErrorMessage("SSH会话获取失败(异步): session为null（连接池可能已耗尽或等待信号量超时）");
                        return result;
                    }
                    
                    log.info("执行SSH异步命令: {}@{}:{}", username, host, port);
                    
                    // 构建nohup命令，在后台执行
                    String asyncCommand = String.format("nohup %s > %s 2>&1 & echo $!", command, logFile);
                    log.info("执行异步命令: {}", asyncCommand);
                    
                    // 执行命令
                    channel = (ChannelExec) session.openChannel("exec");
                    channel.setCommand(asyncCommand);
                    
                } catch (Exception e) {
                    log.warn("从连接池获取SSH会话失败，尝试重新创建: {}", e.getMessage());
                    SshConnectionPool.closeConnection(null, 0, null, host, port, username);
                    shouldCloseConnection = true;
                    throw e;
                }
            }
            
            // 获取输出流
            InputStream inputStream = channel.getInputStream();
            
            channel.connect();
            
            // 读取进程ID
            StringBuilder output = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                log.debug("后台进程ID: {}", line);
            }
            
            // 等待通道关闭
            while (!channel.isClosed()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            int exitStatus = channel.getExitStatus();
            log.info("异步命令提交完成，退出状态: {}", exitStatus);
            
            result.setSuccess(exitStatus == 0);
            result.setExitCode(exitStatus);
            result.setOutput(output.toString().trim()); // 进程ID
            result.setErrorOutput("");
            
        } catch (JSchException e) {
            log.error("SSH连接失败(私钥认证-异步): {}", e.getMessage(), e);
            result.setSuccess(false);
            result.setErrorMessage("SSH连接失败: " + e.getMessage());
            shouldCloseConnection = true;
        } catch (Exception e) {
            log.error("执行异步命令失败: {}", e.getMessage(), e);
            result.setSuccess(false);
            result.setErrorMessage("执行命令失败: " + e.getMessage());
            shouldCloseConnection = true;
        } finally {
            // 只关闭通道，不关闭会话（会话保留在连接池中）
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
                log.debug("关闭SSH通道");
            }
            
            // 仅在异常情况下关闭连接并从池中移除
            if (shouldCloseConnection) {
                if (jumpSession != null && jumpSession.isConnected()) {
                    try {
                        jumpSession.disconnect();
                        SshConnectionPool.closeConnection(jumpHost, jumpPort, jumpUsername, null, 0, null);
                        log.debug("关闭并移除跳板机会话（异常情况）");
                    } catch (Exception e) {
                        log.warn("关闭跳板机会话时出错: {}", e.getMessage());
                    }
                }
                if (session != null && session.isConnected()) {
                    try {
                        session.disconnect();
                        SshConnectionPool.closeConnection(null, 0, null, host, port, username);
                        log.debug("关闭并移除SSH会话（异常情况）");
                    } catch (Exception e) {
                        log.warn("关闭SSH会话时出错: {}", e.getMessage());
                    }
                }
            }
        }
        
        return result;
    }

    /**
     * 检查远程命令是否还在执行
     *
     * @param host          服务器地址
     * @param port          端口
     * @param username      用户名
     * @param privateKey    私钥内容
     * @param passphrase    私钥密码
     * @param pid           进程ID
     * @param timeout       超时时间
     * @return true=正在执行, false=已完成
     */
    public static boolean isProcessRunning(String host, int port, String username, 
                                          String privateKey, String passphrase,
                                          String pid, int timeout) {
        return isProcessRunning(host, port, username, privateKey, passphrase, pid, timeout,
                              null, 0, null, null);
    }

    /**
     * 检查远程命令是否还在执行（支持跳板机）
     */
    public static boolean isProcessRunning(String host, int port, String username, 
                                          String privateKey, String passphrase,
                                          String pid, int timeout,
                                          String jumpHost, int jumpPort, 
                                          String jumpUsername, String jumpPassword) {
        String command = "ps -p " + pid + " > /dev/null 2>&1 && echo 'running' || echo 'stopped'";
        SshResult result = executeCommandWithPrivateKey(host, port, username, privateKey, passphrase, 
                                                       command, timeout, jumpHost, jumpPort, jumpUsername, jumpPassword);
        
        if (result.isSuccess()) {
            return result.getOutput().trim().equals("running");
        }
        return false;
    }

    /**
     * 读取远程日志文件（用于查看异步任务输出）
     *
     * @param host          服务器地址
     * @param port          端口
     * @param username      用户名
     * @param privateKey    私钥内容
     * @param passphrase    私钥密码
     * @param logFile       日志文件路径
     * @param lines         读取最后N行（0=全部）
     * @param timeout       超时时间
     * @return 日志内容
     */
    public static String readRemoteLog(String host, int port, String username, 
                                      String privateKey, String passphrase,
                                      String logFile, int lines, int timeout) {
        return readRemoteLog(host, port, username, privateKey, passphrase, logFile, lines, timeout,
                           null, 0, null, null);
    }

    /**
     * 读取远程日志文件（支持跳板机）
     */
    public static String readRemoteLog(String host, int port, String username, 
                                      String privateKey, String passphrase,
                                      String logFile, int lines, int timeout,
                                      String jumpHost, int jumpPort, 
                                      String jumpUsername, String jumpPassword) {
        String command;
        if (lines > 0) {
            command = "tail -n " + lines + " " + logFile;
        } else {
            command = "cat " + logFile;
        }
        
        SshResult result = executeCommandWithPrivateKey(host, port, username, privateKey, passphrase, 
                                                       command, timeout, jumpHost, jumpPort, jumpUsername, jumpPassword);
        
        if (result.isSuccess()) {
            return result.getOutput();
        } else {
            log.error("读取远程日志失败: {}", logFile);
            return "";
        }
    }

    /**
     * SSH执行结果
     */
    public static class SshResult {
        private boolean success;
        private int exitCode;
        private String output;
        private String errorOutput;
        private String errorMessage;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public int getExitCode() {
            return exitCode;
        }

        public void setExitCode(int exitCode) {
            this.exitCode = exitCode;
        }

        public String getOutput() {
            return output;
        }

        public void setOutput(String output) {
            this.output = output;
        }

        public String getErrorOutput() {
            return errorOutput;
        }

        public void setErrorOutput(String errorOutput) {
            this.errorOutput = errorOutput;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        @Override
        public String toString() {
            return "SshResult{" +
                    "success=" + success +
                    ", exitCode=" + exitCode +
                    ", output='" + output + '\'' +
                    ", errorOutput='" + errorOutput + '\'' +
                    ", errorMessage='" + errorMessage + '\'' +
                    '}';
        }
    }
}
