package com.wugui.datatx.core.util;

import com.wugui.datatx.core.biz.model.TriggerParam;
import com.wugui.datax.rpc.remoting.invoker.XxlRpcInvokerFactory;
import com.wugui.datax.rpc.remoting.net.params.XxlRpcFutureResponse;
import com.wugui.datax.rpc.remoting.net.params.XxlRpcRequest;
import com.wugui.datax.rpc.util.XxlRpcException;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

/**
 * 批次 10-J / B1：泄漏的**源头**是这两个 DTO 的 toString，测试就必须钉在 toString 上。
 *
 * 为什么不在打印处断言：admin 日志、RPC 超时异常、以及"异常消息再拼进 job_log.trigger_msg 落库"
 * 是三条不同的出口，出口还会继续增加（下一个加 log 的人不会想到要先脱敏）。
 * toString 不产出凭据，三条出口和以后的第 N 条才一起成立。
 */
public class ToStringSecretMaskTest {

    private static final String PWD = "S3cr3t#MySql";
    private static final String USER = "datax_ro";
    private static final String TOKEN = "rpc-channel-token";
    private static final String SCRIPT = "curl http://inside/step1.sh | sh";

    /** 真实派发时 jobJson 就是这副样子：JobTrigger 已把账密还原成明文 */
    private static String plainJobJson() {
        return "{\"job\":{\"content\":[{\"reader\":{\"parameter\":{\"username\":\"" + USER
                + "\",\"password\":\"" + PWD + "\",\"column\":[\"id\",\"name\"]}}}]}}";
    }

    private static TriggerParam triggerParam() {
        TriggerParam param = new TriggerParam();
        param.setJobId(7);
        param.setLogId(88L);
        param.setExecutorHandler("executorJobHandler");
        param.setGlueType("BEAN");
        param.setGlueSource(SCRIPT);
        param.setJobJson(plainJobJson());
        return param;
    }

    @Test
    public void triggerParamToStringCarriesNoPlaintextCredentials() {
        String text = triggerParam().toString();

        Assert.assertFalse("明文 jobJson 不许再被 toString 带出：" + text, text.contains(PWD));
        Assert.assertFalse(text, text.contains(USER));
        Assert.assertFalse("GLUE 脚本内容同样是不透明大段：" + text, text.contains("step1.sh"));
    }

    /**
     * 反向的一半：遮蔽不许把排障信息一起遮掉。
     *
     * 没有这条，"整段 toString 直接回 ******" 这种把日志变成砖头的改法也能过上一层。
     */
    @Test
    public void triggerParamToStringKeepsDiagnosticFields() {
        String text = triggerParam().toString();
        String json = plainJobJson();

        Assert.assertTrue(text, text.contains("jobId=7"));
        Assert.assertTrue(text, text.contains("logId=88"));
        Assert.assertTrue(text, text.contains("executorHandler='executorJobHandler'"));
        Assert.assertTrue("被遮的字段要留下长度，len=0 就是\"下发了空配置\"这条线索：" + text,
                text.contains("jobJson=" + "******{len=" + json.length() + "}"));
        Assert.assertTrue(text, text.contains("glueSource=******{len=" + SCRIPT.length() + "}"));
    }

    @Test
    public void triggerParamToStringSurvivesNullPayloads() {
        String text = new TriggerParam().toString();

        Assert.assertTrue("没赋过值也要还能看出\"这项是空的\"：" + text, text.contains("jobJson=null"));
        Assert.assertTrue(text, text.contains("glueSource=null"));
    }

    @Test
    public void rpcRequestToStringCarriesNoAccessToken() {
        XxlRpcRequest request = new XxlRpcRequest();
        request.setRequestId("req-1");
        request.setAccessToken(TOKEN);
        request.setClassName("com.wugui.datatx.core.biz.ExecutorBiz");
        request.setMethodName("run");
        request.setParameters(new Object[]{triggerParam()});

        String text = request.toString();
        Assert.assertFalse("RPC 通道令牌不许进日志：" + text, text.contains(TOKEN));
        Assert.assertFalse(text, text.contains(PWD));
        Assert.assertTrue("调的是哪个服务哪个方法必须还能看出来：" + text, text.contains("methodName='run'"));
    }

    @Test
    public void rpcRequestToStringMasksCredentialsInsideStringParameters() {
        XxlRpcRequest request = new XxlRpcRequest();
        request.setRequestId("req-2");
        request.setAccessToken(TOKEN);
        // log() 这一路传的是执行器回传的 handleMsg —— DataX 报错原文常自带 "Access denied for user 'x'
        // (using password: YES)" 这类串，TriggerParam 那层遮不到它，所以 parameters 还要再扫一遍值
        request.setParameters(new Object[]{1L, 2, "exception from job handler, jdbc url password=" + PWD});

        String text = request.toString();
        Assert.assertFalse(text, text.contains(PWD));
    }

    /**
     * 这条是整批的动机：超时异常消息把 request.toString() 整段拼进去，
     * 而这条消息随后会当成 ReturnT.msg 拼进 trigger_msg **落到 job_log 表里**。
     */
    @Test
    public void rpcTimeoutMessageCarriesNoCredentials() {
        XxlRpcRequest request = new XxlRpcRequest();
        request.setRequestId("req-timeout");
        request.setAccessToken(TOKEN);
        request.setMethodName("run");
        request.setParameters(new Object[]{triggerParam()});

        XxlRpcInvokerFactory factory = new XxlRpcInvokerFactory();
        XxlRpcFutureResponse future = new XxlRpcFutureResponse(factory, request, null);
        try {
            future.get(1, TimeUnit.MILLISECONDS);
            Assert.fail("未超时说明这条用例没测到东西");
        } catch (XxlRpcException e) {
            String message = e.getMessage();
            Assert.assertFalse("超时消息里不许有明文口令：" + message, message.contains(PWD));
            Assert.assertFalse(message, message.contains(USER));
            Assert.assertFalse("超时消息里不许有 RPC 令牌：" + message, message.contains(TOKEN));
            Assert.assertTrue("但\"是哪次请求超时了\"要还能查出来：" + message, message.contains("req-timeout"));
        } catch (Exception e) {
            Assert.fail("期望 XxlRpcException，实际 " + e.getClass().getName() + ": " + e.getMessage());
        } finally {
            future.removeInvokerFuture();
        }
    }
}
