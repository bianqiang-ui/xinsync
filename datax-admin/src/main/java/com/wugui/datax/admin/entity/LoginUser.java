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

    /**
     * "记住我"是一个开关，不是数字。
     *
     * 上游声明成 Integer：客户端按 JSON 的惯例传 rememberMe:true 时 Jackson 直接抛
     * MismatchedInputException，整份请求体读不进来。实测装机包打 POST /api/auth/login，
     * 传 true/false 得到的是 HTTP 200 + Content-Length: 0（没有 token、没有原因），
     * 用户侧的表现是"点了登录没反应"。
     * Boolean 两种写法都接：装机 UI 发的仍是 1/0，由 Jackson 的整数转布尔接住，
     * 两个形状各有一条回归用例钉着（JwtLoginBodyTest）。
     */
    private Boolean rememberMe;

}
