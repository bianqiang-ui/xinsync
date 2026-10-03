package com.wugui.datatx.core.util;

import com.wugui.datax.rpc.util.SensitiveLogMask;
import org.junit.Assert;
import org.junit.Test;

/**
 * 批次 10-J / B1：凭据遮蔽的唯一实现处。
 *
 * 这个类要同时满足两件互相拉扯的事，所以两边都得有用例：
 * 1）**凭据一个字节都不许出**（口令/用户名/RPC 令牌，含被转义过的形态）；
 * 2）**排障线索不许被一起遮掉**（键名、长度、非凭据的字段、以及"这通道压根没配令牌"这件事）。
 * 只测第 1 条的门禁会把功能修坏 —— 反证时第 2 条必须能红。
 */
public class SensitiveLogMaskTest {

    private static final String PWD = "S3cr3t#MySql";
    private static final String USER = "datax_ro";

    @Test
    public void describeBlobHidesContentButKeepsLength() {
        String blob = "{\"password\":\"" + PWD + "\"}";
        String described = SensitiveLogMask.describeBlob(blob);

        Assert.assertFalse("整段内容必须一个字节都不留：" + described, described.contains(PWD));
        Assert.assertTrue("长度要留着，运维靠它判断当时下发的是不是空配置：" + described,
                described.contains("len=" + blob.length()));
    }

    /** null 与空串是两种不同的排障信息，不能都折成同一个掩码 */
    @Test
    public void describeBlobDistinguishesMissingFromEmpty() {
        Assert.assertEquals("null", SensitiveLogMask.describeBlob(null));
        Assert.assertEquals(SensitiveLogMask.MASK + "{len=0}", SensitiveLogMask.describeBlob(""));
    }

    /**
     * accessToken 没配（空串）时必须原样回空串。
     *
     * 折成掩码会伪装成"配了令牌"，而"通道没配令牌"在 CVE-2022-46478 那条默认拒绝分支上是
     * 最关键的一行线索 —— 遮蔽把这条线索遮掉就是守卫把排障能力修坏。
     */
    @Test
    public void describeSecretLeavesUnconfiguredTokenEmpty() {
        Assert.assertEquals("", SensitiveLogMask.describeSecret(""));
        Assert.assertEquals("null", SensitiveLogMask.describeSecret(null));

        String token = "very-secret-rpc-token";
        String described = SensitiveLogMask.describeSecret(token);
        Assert.assertFalse(described, described.contains(token));
        Assert.assertTrue(described, described.contains("len=" + token.length()));
    }

    @Test
    public void masksPlainJsonSecretValues() {
        String text = "parameter:{\"username\":\"" + USER + "\",\"password\":\"" + PWD + "\"}";
        String masked = SensitiveLogMask.maskSecretValues(text);

        Assert.assertFalse(masked, masked.contains(USER));
        Assert.assertFalse(masked, masked.contains(PWD));
        Assert.assertTrue("键名要保留，否则不知道遮的是哪一项：" + masked, masked.contains("\"username\""));
        Assert.assertTrue(masked, masked.contains("\"password\":\"" + SensitiveLogMask.MASK + "\""));
    }

    /**
     * 这条是本批存在的理由：历史实现只有未转义那一条规则，命中不了这里的形态。
     *
     * jobJson 被当作字符串嵌进 TriggerParam.toString()/异常消息里时，引号带反斜杠。
     */
    @Test
    public void masksEscapedJsonSecretValues() {
        String text = "TriggerParam{jobJson=\"{\\\"password\\\":\\\"" + PWD + "\\\",\\\"username\\\":\\\"" + USER + "\\\"}\"}";
        String masked = SensitiveLogMask.maskSecretValues(text);

        Assert.assertFalse("转义形态的口令同样不许出：" + masked, masked.contains(PWD));
        Assert.assertFalse(masked, masked.contains(USER));
        Assert.assertTrue("写回也要保持转义形态，否则这段文本再拼回 JSON 就凭空脱了一层转义：" + masked,
                masked.contains("\\\"password\\\":\\\"" + SensitiveLogMask.MASK + "\\\""));
    }

    /** 口令里带转义引号时不能提前收尾，把尾巴留在文本里 */
    @Test
    public void masksWholeValueWhenItContainsEscapedQuote() {
        String tail = "tail-of-password";
        String text = "{\"password\":\"a\\\"" + tail + "\"}";
        String masked = SensitiveLogMask.maskSecretValues(text);

        Assert.assertFalse("值里的转义引号不该截断遮蔽：" + masked, masked.contains(tail));
    }

    /** MongoDB 的 reader/writer 写的是 userName/userPassword，不是 username/password */
    @Test
    public void masksMongoStyleKeys() {
        String text = "{\"userName\":\"" + USER + "\",\"userPassword\":\"" + PWD + "\"}";
        String masked = SensitiveLogMask.maskSecretValues(text);

        Assert.assertFalse(masked, masked.contains(USER));
        Assert.assertFalse(masked, masked.contains(PWD));
    }

    @Test
    public void masksUrlStyleSecretValues() {
        String text = "jdbc:mysql://h/db?user=" + USER + "&password=" + PWD + "&useSSL=false";
        String masked = SensitiveLogMask.maskSecretValues(text);

        Assert.assertFalse(masked, masked.contains(PWD));
        // 历史实现的 URL 那条只列了 password/accessToken，用户名就从这条缝里出去了
        Assert.assertFalse("URL 形态的 user= 也要认：" + masked, masked.contains(USER));
        Assert.assertTrue("相邻的非凭据参数不许被吃掉：" + masked, masked.contains("useSSL=false"));
    }

    /** 键名匹配要按整词，不能因为含 password 就误伤 */
    @Test
    public void leavesLookalikeKeysAlone() {
        String text = "{\"password_width\":\"8\",\"column\":\"mypassword\"}";
        Assert.assertEquals(text, SensitiveLogMask.maskSecretValues(text));
    }

    @Test
    public void isCaseInsensitiveAndIdempotent() {
        String text = "{\"PASSWORD\":\"" + PWD + "\"}";
        String once = SensitiveLogMask.maskSecretValues(text);
        Assert.assertFalse(once, once.contains(PWD));
        Assert.assertEquals("再遮一次不该改变结果", once, SensitiveLogMask.maskSecretValues(once));
    }

    @Test
    public void passesThroughNullOrPlainTextUntouched() {
        Assert.assertNull(SensitiveLogMask.maskSecretValues(null));
        String text = "command exit value(1) is failed";
        Assert.assertEquals(text, SensitiveLogMask.maskSecretValues(text));
    }
}
