package com.wugui.datatx.core.util;

import com.wugui.datatx.core.biz.model.TriggerParam;
import org.apache.commons.lang3.StringUtils;

import java.text.SimpleDateFormat;

/**
 * 作业参数（JVM 参数 / 增量替换参数 / 增量时间格式 / 分区信息）的校验。
 *
 * <h2>为什么这几个字段要单独判</h2>
 * 它们不是数据，是<b>命令行片段</b>。执行器把它们拼成 <code>-j"..." -p"..."</code> 交给
 * datax.py，而 datax.py 收尾是 <code>subprocess.Popen(startCommand, shell=True)</code>
 * （本仓库 <code>doc/datax-web/datax-python3/datax.py</code> 与官方发行版一致）。
 * 于是一个带反引号或 <code>$( )</code> 的 JVM 参数，就是"在执行器主机上以执行器用户的身份
 * 跑任意命令"。这跟 SQL 注入不同层，加权限判定挡不住 —— 权限只管"谁能存"，不管"存进去的东西
 * 被执行时是什么"。
 *
 * <h2>为什么放在 datax-core，判在两处</h2>
 * 只在管理端入库前判是不够的：库里可以有历史数据，批量建任务还会把模板里的 jvmParam 原样拷进
 * 新任务。真正挨着 shell 的那道关口在执行器拼命令的那一刻，所以两边都调这同一份实现：
 * <ul>
 *   <li>管理端 add/update：拒收并回文案给前端；</li>
 *   <li>执行器 BuildCommand：拒绝拼这条命令（抛异常，任务记为失败并写进日志），
 *       历史脏数据也跑不出去。</li>
 * </ul>
 *
 * <h2>判定范围刻意收得很窄</h2>
 * 只禁"双引号内依旧会被 shell 解释"的字符。<code>&lt;</code> <code>&gt;</code>
 * <code>;</code> <code>&amp;</code> 在双引号里是普通字面量，而用户的 WHERE 条件
 * （<code>id&gt;=%s</code>）本来就常用它们；把合法写法一并禁掉属于守卫过宽。
 */
public final class JobParamSafety {

    /** 双引号内依旧会被 shell 解释的字符：闭合引号、命令替换、变量展开、转义 */
    private static final char[] SHELL_ACTIVE_IN_QUOTES = {'"', '`', '$', '\\'};

    /**
     * 增量时间格式里"不走日期样式"的那个取值。
     *
     * 比较方式严格照抄 BuildCommand 里的 <code>replaceParamType.equals("Timestamp")</code>
     * —— 连大小写一起照抄。判得比执行器宽松没有意义：执行器认为不是 Timestamp 的串，
     * 就会拿它去 new SimpleDateFormat，非法样式照样炸。
     */
    private static final String TIMESTAMP_TYPE = "Timestamp";

    private JobParamSafety() {
    }

    /**
     * 执行器侧入口： TriggerParam 上就是那四个字段，直接取。
     *
     * @return 放行返回 null；拒绝返回可直接写进任务日志/回给前端的文案
     */
    public static String denyMessage(TriggerParam tgParam) {
        if (tgParam == null) {
            return null;
        }
        return denyMessage(tgParam.getJvmParam(), tgParam.getReplaceParam(),
                tgParam.getReplaceParamType(), tgParam.getPartitionInfo());
    }

    /**
     * @return 放行返回 null；拒绝返回可直接写进任务日志/回给前端的文案
     */
    public static String denyMessage(String jvmParam, String replaceParam,
                                     String replaceParamType, String partitionInfo) {
        String deny = checkShellChars("JVM 参数", jvmParam);
        if (deny == null) {
            deny = checkShellChars("增量替换参数", replaceParam);
        }
        if (deny == null) {
            deny = checkTimeFormat("增量时间格式", replaceParamType);
        }
        if (deny == null) {
            deny = checkPartitionInfo(partitionInfo);
        }
        return deny;
    }

    /** 名字写在拒绝文案里，不回显用户原字符（避免响应里出现可被前端二次渲染的注入串） */
    private static String describe(char c) {
        switch (c) {
            case '"':
                return "双引号";
            case '`':
                return "反引号（shell 命令替换）";
            case '$':
                return "美元符（shell 变量展开，它后面跟括号还能变成命令替换）";
            case '\\':
                return "反斜杠（shell 转义，可用来绕过上面几个字符）";
            default:
                return "字符 " + c;
        }
    }

    private static String checkShellChars(String fieldLabel, String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            for (char forbidden : SHELL_ACTIVE_IN_QUOTES) {
                if (c == forbidden) {
                    return fieldLabel + " 含有 " + describe(c) + "，该参数会被拼进 datax.py 的命令行执行，"
                            + "存在命令注入风险，请去掉后重试";
                }
            }
        }
        // 换行不是 shell 元字符，但它会让这条命令行在半途结束、余下的部分另起一条 —— 同样拒
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return fieldLabel + " 含有换行符，该参数会被拼进一行命令行，请把多个参数用空格分隔";
        }
        return null;
    }

    /**
     * 分区信息格式是 <code>分区字段,天数偏移,日期格式</code>（执行器按这个顺序取下标）。
     *
     * 这条同时修一个"报错比被报的错先炸"的老问题：原先少一段或非数字会一路走到执行器的
     * <code>Integer.parseInt</code> / <code>List.get</code>，任务日志里只有一行堆栈，
     * 前端显示"系统异常"，谁也看不出是自己填错了分区配置。
     */
    private static String checkPartitionInfo(String partitionInfo) {
        if (StringUtils.isBlank(partitionInfo)) {
            return null;
        }
        String[] parts = partitionInfo.split(",");
        if (parts.length != 3) {
            return "分区信息格式应为「分区字段,天数偏移,日期格式」，当前是 " + parts.length + " 段";
        }
        String[] labels = {"分区字段", "天数偏移", "日期格式"};
        for (int i = 0; i < parts.length; i++) {
            if (StringUtils.isBlank(parts[i])) {
                return "分区信息的" + labels[i] + "为空，格式应为「分区字段,天数偏移,日期格式」";
            }
        }
        try {
            Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return "分区信息的天数偏移必须是整数，当前为「" + parts[1].trim() + "」";
        }
        String deny = denyBadDatePattern("分区信息的日期格式", parts[2]);
        if (deny != null) {
            return deny;
        }
        // 三段最终都会拼进 -p"..." 那一段命令行（字段名与等号直接拼接），照样要过 shell 字符判定
        for (int i = 0; i < parts.length; i++) {
            deny = checkShellChars("分区信息的" + labels[i], parts[i]);
            if (deny != null) {
                return deny;
            }
        }
        return null;
    }

    /**
     * 增量时间格式会被直接喂给 <code>new SimpleDateFormat(...)</code>，非法样式在执行器线程抛
     * IllegalArgumentException，任务日志只剩一行堆栈。这里提前判掉。
     *
     * "Timestamp" 是前端下拉里的另一个取值（走毫秒时间戳分支），不是样式，放过。
     */
    private static String checkTimeFormat(String fieldLabel, String pattern) {
        if (TIMESTAMP_TYPE.equals(StringUtils.trimToEmpty(pattern))) {
            return null;
        }
        return denyBadDatePattern(fieldLabel, pattern);
    }

    private static String denyBadDatePattern(String fieldLabel, String pattern) {
        if (StringUtils.isBlank(pattern)) {
            return null;
        }
        String trimmed = pattern.trim();
        try {
            new SimpleDateFormat(trimmed);
        } catch (IllegalArgumentException e) {
            // 不指出是哪个字母：SimpleDateFormat 自己也不指出，回显原文足够定位
            return fieldLabel + "「" + trimmed + "」不是可用的日期格式（如 yyyy-MM-dd HH:mm:ss）";
        }
        return null;
    }
}
