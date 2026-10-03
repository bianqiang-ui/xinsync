package com.wugui.datatx.core.rpc;

import com.wugui.datax.rpc.remoting.net.impl.netty_http.server.NettyHttpServerHandler;
import com.wugui.datax.rpc.remoting.provider.XxlRpcProviderFactory;
import com.wugui.datax.rpc.util.RpcAccessDecision;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 批次 10-B：9999 端口上的 `/services` 服务清单。
 *
 * 这条分支历史上挂在令牌校验之外 —— 匿名 GET 就能拿到"这台执行器暴露了哪些 RPC 接口、
 * 由哪个 Bean 实现"，是一张现成的攻击面地图。它不属于 HTTP 管理端那条 Spring 过滤器链，
 * 所以 admin 侧的鉴权接缝门禁管不到它，只能在这里按行为钉。
 *
 * 用 EmbeddedChannel 直接喂一个真的 FullHttpRequest 给真的 handler，读真的出向响应：
 * 断言的是"状态码 +  body 里到底有没有服务名"，不是"代码里有没有一行 if"。
 */
public class ServiceListingAccessTest {

    /** 测试里让 handler 在调用线程上同步跑完，免得读响应要先等池子 */
    private static final class DirectExecutorPool extends ThreadPoolExecutor {
        DirectExecutorPool() {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private XxlRpcProviderFactory provider(String accessToken, boolean allowEmpty) {
        XxlRpcProviderFactory factory = new XxlRpcProviderFactory();
        factory.setAccessToken(accessToken);
        factory.setAllowEmptyAccessToken(allowEmpty);
        factory.addService("com.wugui.datax.rpc.executor.biz.ExecutorBiz", null, new Object());
        return factory;
    }

    private FullHttpResponse ask(XxlRpcProviderFactory factory, String uri, String headerToken) {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
        if (headerToken != null) {
            request.headers().set(RpcAccessDecision.SERVICE_LISTING_HEADER, headerToken);
        }
        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyHttpServerHandler(factory, new DirectExecutorPool()));
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        Assert.assertNotNull("请求进来却没有任何出向响应，等于把调用方挂住", response);
        return response;
    }

    private String bodyOf(FullHttpResponse response) {
        return response.content().toString(CharsetUtil.UTF_8);
    }

    @Test
    public void anonymousListingIsRefusedAndLeaksNoServiceName() {
        FullHttpResponse response = ask(provider("s3cr3t", false), "/services", null);
        Assert.assertEquals(403, response.status().code());
        String body = bodyOf(response);
        Assert.assertFalse("拒绝时一个服务名都不许出现在响应体里", body.contains("ExecutorBiz"));
        Assert.assertTrue(body.contains(RpcAccessDecision.DENY_TOKEN_WRONG));
    }

    @Test
    public void wrongTokenListingIsRefused() {
        FullHttpResponse response = ask(provider("s3cr3t", false), "/services", "guessed");
        Assert.assertEquals(403, response.status().code());
        Assert.assertFalse(bodyOf(response).contains("ExecutorBiz"));
    }

    @Test
    public void correctTokenStillGetsTheWholeServiceMap() {
        FullHttpResponse response = ask(provider("s3cr3t", false), "/services", "s3cr3t");
        Assert.assertEquals("带对令牌时必须照旧给清单，否则就是把排障通道关掉", 200, response.status().code());
        Assert.assertTrue(bodyOf(response).contains("ExecutorBiz"));
    }

    /** 逃生口：老部署显式允许空令牌时，清单照旧可得 —— 守卫不得替运维改部署语义 */
    @Test
    public void legacyDeploymentWithoutTokenKeepsWorkingThroughTheEscapeHatch() {
        FullHttpResponse response = ask(provider("", true), "/services", null);
        Assert.assertEquals(200, response.status().code());
        Assert.assertTrue(bodyOf(response).contains("ExecutorBiz"));
    }

    /** 没配令牌 = fail-closed：这才是出厂默认，9999 不该是匿名入口（CVE-2022-46478 同一族） */
    @Test
    public void providerWithoutTokenFailsClosed() {
        FullHttpResponse response = ask(provider(null, false), "/services", null);
        Assert.assertEquals(403, response.status().code());
        Assert.assertTrue(bodyOf(response).contains(RpcAccessDecision.DENY_TOKEN_NOT_CONFIGURED));
        Assert.assertFalse(bodyOf(response).contains("ExecutorBiz"));
    }
}
