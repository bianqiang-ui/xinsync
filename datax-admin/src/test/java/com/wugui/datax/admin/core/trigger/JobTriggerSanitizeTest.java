package com.wugui.datax.admin.core.trigger;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 批次 10-J / B1：trigger_msg 入库前那道脱敏是**第二层**，它兜的是"来源不受我们控制"的文本
 * （升级前的老执行器回传的异常、别的模块自己拼出来的消息串）。
 *
 * 主防线已经挪到 {@code TriggerParam}/{@code XxlRpcRequest} 的 toString 里，所以这里的用例
 * 只钉两件事：这一层仍然认得各种形态的凭据；以及它和主防线用的是**同一份**判定（不再自己抄正则）。
 */
public class JobTriggerSanitizeTest {

    private static final String PWD = "S3cr3t#MySql";
    private static final String USER = "datax_ro";

    /**
     * 用原生反射而不是 ReflectionTestUtils.invokeMethod：那个重载把第一个参数当**实例**，
     * 传 Class 进去它就去 java.lang.Class 上找方法，且 null 实参会被推成 Object 签名，
     * 结果是五条用例全报 "Method not found: java.lang.Class.sanitizeTriggerMsg(java.lang.Object)" ——
     * 看起来像守卫坏了，其实是测试自己调错了工具。签名写死在这里，方法一改名测试就红。
     */
    private static String sanitize(String msg) {
        try {
            Method method = JobTrigger.class.getDeclaredMethod("sanitizeTriggerMsg", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, msg);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("JobTrigger.sanitizeTriggerMsg(String) 已经不在了 —— "
                    + "trigger_msg 入库前那道第二层脱敏的接缝被拆掉，本用例应当随之改写而不是静默放行", e);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void masksPlainFormBeforePersisting() {
        String masked = sanitize("run fail, jobJson={\"username\":\"" + USER + "\",\"password\":\"" + PWD + "\"}");

        Assert.assertFalse(masked, masked.contains(PWD));
        Assert.assertFalse(masked, masked.contains(USER));
    }

    /**
     * 转义形态。历史实现只有未转义那一条规则，这一形态整段穿过去落进 job_log.trigger_msg，
     * 是本批把它接进共享实现的原因。
     *
     * 这里的字面串写的是"JSON 被当作字符串再嵌一层"之后的样子：文本里是
     * {@code jobJson="{\"password\":\"…\"}"}（每个引号前带一个反斜杠）。
     */
    @Test
    public void masksEscapedForm() {
        String masked = sanitize("request:TriggerParam{jobJson=\"{\\\"password\\\":\\\"" + PWD + "\\\",\\\"username\\\":\\\"" + USER + "\\\"}\"}");

        Assert.assertFalse("转义过的 JSON 不许从第二层漏掉：" + masked, masked.contains(PWD));
        Assert.assertFalse(masked, masked.contains(USER));
    }

    @Test
    public void masksUrlFormUsername() {
        String masked = sanitize("connect fail: jdbc:mysql://h/db?user=" + USER + "&password=" + PWD);

        Assert.assertFalse(masked, masked.contains(PWD));
        Assert.assertFalse("旧规则这条只列了 password/accessToken：" + masked, masked.contains(USER));
    }

    /** 反向的一半：定位信息得留下，否则日志页只剩一行"******"，故障查不动 */
    @Test
    public void keepsDiagnosticContext() {
        String masked = sanitize("XxlRpcException: xxl-rpc, request timeout at:1730000000000, "
                + "request:TriggerParam{jobId=7, logId=88, executorHandler='executorJobHandler'}");

        Assert.assertTrue(masked, masked.contains("XxlRpcException"));
        Assert.assertTrue(masked, masked.contains("jobId=7"));
        Assert.assertTrue(masked, masked.contains("logId=88"));
    }

    @Test
    public void passesNullThrough() {
        Assert.assertNull(sanitize(null));
    }
}
