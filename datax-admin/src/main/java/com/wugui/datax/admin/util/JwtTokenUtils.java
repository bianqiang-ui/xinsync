package com.wugui.datax.admin.util;

import com.alibaba.fastjson.JSON;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import static com.wugui.datatx.core.util.Constants.SPLIT_COMMA;

/**
 * Created by jingwk on 2019/12/01
 */
public class JwtTokenUtils {

    public static final String TOKEN_HEADER = "Authorization";
    public static final String TOKEN_PREFIX = "Bearer ";

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtTokenUtils.class);

    private static final String ISS = "admin";

    /**
     * 签名密钥，必须由 `datax.jwt.secret` 提供；未配置时退化为进程随机密钥（重启即失效）。
     */
    private static volatile byte[] signingKey = randomKey();

    /**
     * 已知的不安全默认值，禁止使用。
     */
    private static final List<String> FORBIDDEN_SECRETS = Arrays.asList("datax_admin", "datax-web", "secret", "123456");

    // 角色的key
    private static final String ROLE_CLAIMS = "rol";

    // 过期时间是3600秒，既是24个小时
    private static final long EXPIRATION = 86400L;

    // 选择了记住我之后的过期时间为7天
    private static final long EXPIRATION_REMEMBER = 7 * EXPIRATION;

    /**
     * 由 Spring 配置在容器启动时注入，避免密钥硬编码在源码中。
     */
    public static void configureSecret(String secret) {
        if (secret == null || secret.trim().length() == 0) {
            LOGGER.warn("datax.jwt.secret is not configured, JWT tokens will be invalidated on every restart. "
                    + "Set a long random value in production.");
            return;
        }
        String value = secret.trim();
        if (FORBIDDEN_SECRETS.contains(value)) {
            LOGGER.error("datax.jwt.secret is a well-known default and will be ignored, a random key is used instead.");
            return;
        }
        if (value.length() < 32) {
            LOGGER.warn("datax.jwt.secret is shorter than 32 characters, please use a longer random value.");
        }
        signingKey = value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] randomKey() {
        byte[] key = new byte[64];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encode(key);
    }

    // 创建token
    public static String createToken(Integer id, String username, String role, boolean isRememberMe) {
        long expiration = isRememberMe ? EXPIRATION_REMEMBER : EXPIRATION;
        HashMap<String, Object> map = new HashMap<>();
        map.put(ROLE_CLAIMS, role);
        return Jwts.builder()
                .signWith(SignatureAlgorithm.HS512, signingKey)
                .setClaims(map)
                .setIssuer(ISS)
                .setSubject(id + SPLIT_COMMA + username)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiration * 1000))
                .compact();
    }

    // 从token中获取用户名
    public static String getUsername(String token) {
        List<String> userInfo = Arrays.asList(getTokenBody(token).getSubject().split(SPLIT_COMMA));
        return userInfo.get(1);
    }

    // 从token中获取用户名
    public static Integer getUserId(String token) {
        String s= JSON.toJSONString(getTokenBody(token).getSubject());
        List<String> userInfo = Arrays.asList(getTokenBody(token).getSubject().split(SPLIT_COMMA));
        return Integer.parseInt(userInfo.get(0));
    }

    // 获取用户角色
    public static String getUserRole(String token) {
        return (String) getTokenBody(token).get(ROLE_CLAIMS);
    }

    // 是否已过期
    public static boolean isExpiration(String token) {
        try {
            return getTokenBody(token).getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        }
    }

    private static Claims getTokenBody(String token) {
        return Jwts.parser()
                .setSigningKey(signingKey)
                .parseClaimsJws(token)
                .getBody();
    }
}
