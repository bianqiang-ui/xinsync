package com.wugui.datax.rpc.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志/异常文案里的凭据遮蔽：唯一实现处。
 *
 * <h2>为什么要在 toString 里挡，而不是在打印处挡</h2>
 * {@code TriggerParam} 与 {@code XxlRpcRequest} 的 {@code toString()} 会把
 * 派发用的 jobJson（**已解密的数据源明文账密**）、glueSource 与 RPC accessToken 原样拼进去。
 * 这三样一旦进过 {@code toString()}，下游有几条出口就漏几次：
 * <ul>
 *   <li>{@code XxlRpcReferenceBean} 的三条 {@code logger.info(..., xxlRpcRequest)} —— 进 admin 日志文件；</li>
 *   <li>{@code XxlRpcFutureResponse.get(..)} 超时抛的 {@code XxlRpcException("... request:" + request.toString())}
 *       —— 异常消息会被 {@code JobTrigger} 摘出来当 {@code ReturnT.msg}，再拼进 triggerMsg 落到
 *       {@code job_log.trigger_msg} 列，等于把口令持久化进库；</li>
 *   <li>任何后续新增的打印点 —— 打印处逐个加脱敏永远追不上出口逐个增加。</li>
 * </ul>
 * 所以收口点在**值的来源**：toString 根本不产出凭据，新增出口自动继承这条保护。
 *
 * <h2>为什么不能整段丢弃</h2>
 * 运维要靠日志判断"当时到底下发了什么"。所以不透明的大字段换成
 * {@code ******{len=N}}：内容一个字节不出，长度与"是不是空"仍然留得住 ——
 * {@code len=0} 本身就是一条很有用的线索（下发的是空配置）。
 *
 * <h2>本类同时被入库前的第二层复用</h2>
 * {@code JobTrigger.sanitizeTriggerMsg()} 直接调 {@link #maskSecretValues(String)}，
 * 不再自己抄一份正则。**库里与历史日志里已有脏行**，那些文本里引号是被转义过的
 * （{@code \"password\":\"xxx\"}），所以下面的规则对未转义与转义两种形态都判。
 */
public final class SensitiveLogMask {

    /** 掩码本体：与数据源读接口回给前端的取值保持一致，别让人以为是两种东西 */
    public static final String MASK = "******";

    /**
     * 需要遮蔽的键名。口径来自三条真实产出路径，不是随手列的：
     * MySQL/PG 等 reader-writer 写 {@code username}/{@code password}，
     * MongoDB 写 {@code userName}/{@code userPassword}，RPC 通道带 {@code accessToken}，
     * JDBC URL 形态带 {@code user=}/{@code password=}（异常消息里最常见的就是这串）。
     *
     * 顺序有讲究：正则的分支是"同位置先到先得"，长键必须排在短键前面，
     * 所以 {@code username} 在 {@code user} 之前、{@code jdbcUsername} 也在它之前。
     */
    private static final String SECRET_KEYS =
            "password|passwd|username|userName|userPassword|jdbcPassword|jdbcUsername|accessToken|user";

    /** 文本里成对的裸引号 */
    private static final String QUOTE = "\"";
    /** 文本里被转义过的引号（JSON 当作字符串嵌进另一段文本时的形态） */
    private static final String ESCAPED_QUOTE = "\\\"";

    /**
     * 未转义形态：{@code "password":"a\"b"}。
     * 值体用 {@code (?:\\.|[^"\\])*?} —— 反斜杠连同后一个字符整体吃掉，
     * 所以口令里带转义引号也不会提前收尾，把尾巴留在文本里。
     */
    private static final Pattern JSON_PAIR = Pattern.compile(
            "(^|[^\\\\])\"(" + SECRET_KEYS + ")\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*?\"",
            Pattern.CASE_INSENSITIVE);

    /** 转义形态：{@code \"password\":\"a\\\"b\"}，见 {@link #JSON_PAIR} 的说明 */
    private static final Pattern JSON_PAIR_ESCAPED = Pattern.compile(
            "(^|[^\\\\])\\\\\"(" + SECRET_KEYS + ")\\\\\"\\s*:\\s*\\\\\"(?:\\\\.|[^\"\\\\])*?\\\\\"",
            Pattern.CASE_INSENSITIVE);

    /** URL / query 形态：{@code password=abc&}。引号、反斜杠、与号、空白都是值串的结束符 */
    private static final Pattern URL_PAIR = Pattern.compile(
            "\\b(" + SECRET_KEYS + ")\\s*=\\s*([^&\\s\"'\\\\]+)", Pattern.CASE_INSENSITIVE);

    private SensitiveLogMask() {
    }

    /**
     * 整段不透明内容换成"掩码 + 长度"。
     *
     * @return null 仍回 {@code null}（与改动前 toString 的可读性一致），空串回 {@code ******{len=0}}
     */
    public static String describeBlob(String blob) {
        if (blob == null) {
            return "null";
        }
        return MASK + "{len=" + blob.length() + "}";
    }

    /**
     * 单个凭据字段（如 RPC accessToken）换成"掩码 + 长度"。
     * 空值直接回空串而不是掩码：{@code accessToken=} 说明这通道压根没配令牌，
     * 和"配了但被遮蔽"是两回事，别把这条排障线索遮掉。
     */
    public static String describeSecret(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() == 0) {
            return "";
        }
        return MASK + "{len=" + value.length() + "}";
    }

    /**
     * 把一段自由文本里所有 JSON / URL 形态的凭据值换成掩码，键名保留。
     *
     * 只换值、不改结构，也不要求传进来的整体是合法 JSON —— 这条规则的用途是"兜住别人抄来的整段异常消息"，
     * 那种文本外面还裹着 {@code TriggerParam{...}} 的 toString 外壳，本来就不是合法 JSON。
     */
    public static String maskSecretValues(String text) {
        if (text == null || text.length() == 0) {
            return text;
        }
        String masked = replaceJsonPairs(JSON_PAIR, text, false);
        masked = replaceJsonPairs(JSON_PAIR_ESCAPED, masked, true);
        return URL_PAIR.matcher(masked).replaceAll("$1=" + MASK);
    }

    /**
     * @param escaped true 时按转义形态写回（{@code \"key\":\"******\"}），
     *                免得替换完的这段文本再被拼回 JSON 字符串时"凭空脱了一层转义"
     */
    private static String replaceJsonPairs(Pattern pattern, String text, boolean escaped) {
        String quote = escaped ? ESCAPED_QUOTE : QUOTE;
        Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(1)
                    + quote + matcher.group(2) + quote
                    + ":"
                    + quote + MASK + quote;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
