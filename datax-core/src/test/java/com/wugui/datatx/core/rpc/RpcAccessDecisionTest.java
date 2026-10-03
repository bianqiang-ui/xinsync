package com.wugui.datatx.core.rpc;

import com.wugui.datax.rpc.util.RpcAccessDecision;
import org.junit.Assert;
import org.junit.Test;

/**
 * 批次 10-B：RPC 服务端令牌判定的唯一实现处。
 *
 * 判定的两侧都要钉：既不许"该拒的放行"（匿名调用 / 匿名读服务清单），
 * 也不许"该放的拒掉"（配了正确令牌的管理端、以及显式打开兼容逃生口的旧部署）——
 * 后者一旦判错，就是把执行器注册与回调整条链路关掉。
 */
public class RpcAccessDecisionTest {

    @Test
    public void providerWithoutTokenIsFailClosedByDefault() {
        Assert.assertEquals(RpcAccessDecision.DENY_TOKEN_NOT_CONFIGURED,
                RpcAccessDecision.denyReason(null, false, null));
        Assert.assertEquals(RpcAccessDecision.DENY_TOKEN_NOT_CONFIGURED,
                RpcAccessDecision.denyReason("", false, "whatever"));
        Assert.assertEquals("只空格也算未配置",
                RpcAccessDecision.DENY_TOKEN_NOT_CONFIGURED, RpcAccessDecision.denyReason("   ", false, null));
    }

    @Test
    public void emptyProviderTokenIsOnlyOpenThroughTheExplicitEscapeHatch() {
        Assert.assertNull("旧部署显式置 true 时不得被守卫拦死（那是它的逃生口）",
                RpcAccessDecision.denyReason("", true, null));
    }

    @Test
    public void configuredTokenRequiresExactMatch() {
        Assert.assertNull(RpcAccessDecision.denyReason("s3cr3t", false, "s3cr3t"));
        Assert.assertEquals(RpcAccessDecision.DENY_TOKEN_WRONG,
                RpcAccessDecision.denyReason("s3cr3t", false, null));
        Assert.assertEquals(RpcAccessDecision.DENY_TOKEN_WRONG,
                RpcAccessDecision.denyReason("s3cr3t", false, ""));
        Assert.assertEquals(RpcAccessDecision.DENY_TOKEN_WRONG,
                RpcAccessDecision.denyReason("s3cr3t", false, "S3CR3T"));
        Assert.assertEquals("换错令牌时即使打开兼容逃生口也必须拒绝",
                RpcAccessDecision.DENY_TOKEN_WRONG, RpcAccessDecision.denyReason("s3cr3t", true, "wrong"));
    }

    /**
     * 服务端配置值两端的空格是配置文件里常见的手滑：比对时按 trim 取，
     * 但调用方给的值不 trim —— 历史上就是这么比的，改动它会让既有部署突然全拒。
     */
    @Test
    public void providerSideIsTrimmedButPresentedSideIsNot() {
        Assert.assertNull(RpcAccessDecision.denyReason("  s3cr3t  ", false, "s3cr3t"));
        Assert.assertEquals(RpcAccessDecision.DENY_TOKEN_WRONG,
                RpcAccessDecision.denyReason("s3cr3t", false, " s3cr3t "));
    }

    /** 服务清单的令牌载体必须是请求头：写进 URL 就会落进代理与访问日志，等于换个地方泄漏 */
    @Test
    public void listingTokenTravelsInAHeaderNotInTheQueryString() {
        Assert.assertTrue(RpcAccessDecision.SERVICE_LISTING_HEADER.indexOf('=') < 0);
        Assert.assertEquals("X-Xxl-Rpc-Access-Token", RpcAccessDecision.SERVICE_LISTING_HEADER);
    }
}
