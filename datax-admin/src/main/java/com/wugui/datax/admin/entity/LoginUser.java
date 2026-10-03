package com.wugui.datax.admin.entity;

import lombok.Data;
import lombok.ToString;

/**
 * Created by jingwk on 2019-11-17
 */
// 这个对象是登录请求体反序列化出来的，password 栏里就是**用户此刻的明文口令**。
// @Data 会把它一起生成进 toString()，将来任何一处 logger.debug("{}", loginUser) 就是一条口令泄漏。
// 与 JwtUser 同一口径：整段不进 toString，连长度都不给（带长度的掩码只适合 RPC 令牌那种排查场景）。
@Data
@ToString(exclude = "password")
public class LoginUser {

    private String username;
    private String password;
    private Integer rememberMe;

}
