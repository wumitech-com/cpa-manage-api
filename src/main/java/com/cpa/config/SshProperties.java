package com.cpa.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SSH配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class SshProperties {

    /**
     * SSH连接超时时间（毫秒）
     */
    private Integer sshTimeout = 30000;

    /**
     * SSH私钥内容（支持多行字符串）
     */
    private String sshPrivateKey;

    /**
     * SSH公钥
     */
    private String sshPublicKey;

    /**
     * 私钥密码（如果私钥有密码）
     */
    private String sshPassphrase;

    /**
     * 默认SSH用户名
     */
    private String sshUsername = "root";

    /**
     * 默认SSH端口
     */
    private Integer sshPort = 22;

    /**
     * 跳板机主机（如果使用跳板机）
     */
    private String sshJumpHost;

    /**
     * 跳板机端口
     */
    private Integer sshJumpPort = 22;

    /**
     * 跳板机用户名
     */
    private String sshJumpUsername = "ubuntu";

    /**
     * 跳板机密码（如果不用私钥）
     */
    private String sshJumpPassword;

    /**
     * 目标服务器主机
     */
    private String sshTargetHost;

    /**
     * 目标服务器端口
     */
    private Integer sshTargetPort = 22;
}
