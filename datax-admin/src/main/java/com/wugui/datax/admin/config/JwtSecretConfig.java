package com.wugui.datax.admin.config;

import com.wugui.datax.admin.util.JwtTokenUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 把 JWT 签名密钥从配置注入到 JwtTokenUtils，源码中不再保留固定密钥。
 */
@Component
public class JwtSecretConfig {

    @Value("${datax.jwt.secret:}")
    private String jwtSecret;

    @PostConstruct
    public void init() {
        JwtTokenUtils.configureSecret(jwtSecret);
    }

}
