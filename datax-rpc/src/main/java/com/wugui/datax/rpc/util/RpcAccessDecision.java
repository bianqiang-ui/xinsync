package com.wugui.datax.rpc.util;

/**
 * RPC 服务端"这次调用能不能受理"的唯一判定处。
 *
 * 放在 datax-rpc 是因为它同时管两条入口：9999 端口上的服务调用（`invokeService`）
 * 和 `/services` 服务清单查询 —— 后者历史上完全不校验，匿名 GET 就能把整张 RPC 服务表
 * （接口全限定名 + 实现 Bean）读走。两条入口必须共用这一份判定，各写一份就会漂移
 * （上一版 `sanitizeTriggerMsg` 自己重抄脱敏正则，漏掉转义形态就是这么坏的）。
 */
public final class RpcAccessDecision {

    /**
     * 服务清单查询的令牌载体：请求头。
     * 不走 URL 查询参数 —— 令牌进 query 就会落进 nginx / 代理 / 浏览器历史里，
     * 而这条接口本来就是给人用 curl 排查用的。
     */
    public static final String SERVICE_LISTING_HEADER = "X-Xxl-Rpc-Access-Token";

    public static final String DENY_TOKEN_NOT_CONFIGURED = "The access token is not configured on provider side.";
    public static final String DENY_TOKEN_WRONG = "The access token is wrong.";

    private RpcAccessDecision() {
    }

    /**
     * @param providerAccessToken   服务端配置的令牌
     * @param allowEmptyAccessToken 兼容旧部署的显式逃生口：true 才允许空令牌匿名受理
     * @param presentedAccessToken  调用方给的令牌（可为 null）
     * @return null 表示放行；否则返回要回给调用方的拒绝理由（措辞沿用历史值，不另起一套）
     */
    public static String denyReason(String providerAccessToken, boolean allowEmptyAccessToken,
                                    String presentedAccessToken) {
        if (providerAccessToken == null || providerAccessToken.trim().length() == 0) {
            // 默认拒绝：未配置令牌时 9999 端口不应成为匿名入口（CVE-2022-46478）
            return allowEmptyAccessToken ? null : DENY_TOKEN_NOT_CONFIGURED;
        }
        return providerAccessToken.trim().equals(presentedAccessToken) ? null : DENY_TOKEN_WRONG;
    }
}
