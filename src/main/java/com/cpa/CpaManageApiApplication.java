package com.cpa;

import com.cpa.config.CpaGaidPoolProperties;
import com.cpa.config.SshProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CPA管理后台API主启动类
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({SshProperties.class, CpaGaidPoolProperties.class})
public class CpaManageApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CpaManageApiApplication.class, args);
    }

}
