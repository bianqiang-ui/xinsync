package com.wugui.datax.admin.tool.datax;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.wugui.datax.admin.core.conf.JobAdminConfig;
import com.wugui.datax.admin.entity.JobDatasource;
import com.wugui.datax.admin.util.AESUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * job_json 里的数据源账密占位符：落库、回给前端、进模板的那份 JSON 只带"指向哪一行数据源"，
 * 真正的账密在派发给执行器的那一刻（{@link com.wugui.datax.admin.core.trigger.JobTrigger}）才还原。
 *
 * <h2>要收的是哪一面</h2>
 * {@code /api/dataxJson/buildJson} 的返回体、以及它落进 {@code job_info.job_json} 的那一串，
 * 原先直接带着 {@code jdbcUsername}/{@code jdbcPassword} 在库里的值。那是 AES 密文而不是明文
 * （{@code JobDatasource} 没开 {@code @TableName(autoResultMap = true)}，typeHandler 只作用于写入侧，
 * select 出来仍是密文），但 {@code datasource.aes.key} 有出厂默认值且写在本仓库里 ——
 * 对拿着公开源码的人来说，密文和明文只差"读一次源码"这一步。
 * 换成占位符之后，这一面一个凭据字节都不出，也不再依赖那个默认密钥。
 *
 * <h2>为什么不能改成"这个接口只给管理员"</h2>
 * 建作业向导（选源 → 选表 → 选列 → 生成 JSON）是普通用户的日常主流程，收归管理员等于把功能关掉。
 * 收口点在"产出里不放凭据"，不在"谁能调"。
 *
 * <h2>存量任务</h2>
 * 老 job_json 里存的还是密文，派发时走 {@code JSONUtils.changeJson(...)} 那条既有解密路径；
 * 本类碰到不含占位符的 JSON 原样返回，两条路互不干扰。要清掉库里的历史密文，把任务重新保存一次即可。
 */
public final class DsSecretPlaceholder {

    /** 出现这个子串就说明 JSON 里有待还原的账密引用。 */
    public static final String TOKEN_PREFIX = "@@DATAX_DS_";

    private static final String KIND_USER = "USER";
    private static final String KIND_PWD = "PWD";

    /** 整串匹配才算占位符：口令里恰好含这段字符的合法值不会被误伤。 */
    private static final Pattern TOKEN = Pattern.compile(TOKEN_PREFIX + "(USER|PWD):(\\d+)@@");

    private DsSecretPlaceholder() {
    }

    public static String userToken(long datasourceId) {
        return TOKEN_PREFIX + KIND_USER + ":" + datasourceId + "@@";
    }

    public static String pwdToken(long datasourceId) {
        return TOKEN_PREFIX + KIND_PWD + ":" + datasourceId + "@@";
    }

    /**
     * 建 JSON 时要写进 job_json 的用户名。
     *
     * 两种情况退回原值、不生成占位符：对象没有 id（不是从库里 load 出来的内存对象，往后再也查不回来）、
     * 用户名本来就是空（免认证实例）。后者保持和改动前完全一致的输出。
     */
    public static String usernameForJson(JobDatasource datasource) {
        if (datasource == null || datasource.getId() == null || StringUtils.isBlank(datasource.getJdbcUsername())) {
            return datasource == null ? null : datasource.getJdbcUsername();
        }
        return userToken(datasource.getId());
    }

    /** 见 {@link #usernameForJson}。 */
    public static String passwordForJson(JobDatasource datasource) {
        if (datasource == null || datasource.getId() == null || StringUtils.isBlank(datasource.getJdbcPassword())) {
            return datasource == null ? null : datasource.getJdbcPassword();
        }
        return pwdToken(datasource.getId());
    }

    /**
     * 派发点专用：从库里把那行数据源取回来（select 出来是密文），解出明文再填进 JSON。
     */
    public static String resolveForDispatch(String json) {
        return resolve(json, id -> JobAdminConfig.getAdminConfig().getJobDatasourceMapper().selectById(id));
    }

    /**
     * @param rowLoader 按 id 取 {@code job_jdbc_datasource} 那一行，取不到返回 null
     * @return 没有占位符时原样返回入参；还原不了抛 {@link IllegalStateException}，由调用方记成可见的失败
     */
    public static String resolve(String json, LongFunction<JobDatasource> rowLoader) {
        if (json == null || !json.contains(TOKEN_PREFIX)) {
            return json;
        }
        // OrderedField：还原只做值替换，不能让 reader/writer 的键序变化影响可读性
        JSONObject root = JSON.parseObject(json, Feature.OrderedField);
        Map<Long, JobDatasource> loaded = new HashMap<>();
        if (!replaceTokens(root, rowLoader, loaded)) {
            return json;
        }
        return root.toJSONString();
    }

    private static boolean replaceTokens(Object node, LongFunction<JobDatasource> rowLoader,
                                         Map<Long, JobDatasource> loaded) {
        boolean changed = false;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            for (String key : new ArrayList<>(obj.keySet())) {
                Object value = obj.get(key);
                if (value instanceof String) {
                    String plain = plainValue((String) value, rowLoader, loaded);
                    if (plain != null) {
                        obj.put(key, plain);
                        changed = true;
                    }
                } else if (value instanceof JSONObject || value instanceof JSONArray) {
                    changed |= replaceTokens(value, rowLoader, loaded);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.size(); i++) {
                Object value = arr.get(i);
                if (value instanceof String) {
                    String plain = plainValue((String) value, rowLoader, loaded);
                    if (plain != null) {
                        arr.set(i, plain);
                        changed = true;
                    }
                } else if (value instanceof JSONObject || value instanceof JSONArray) {
                    changed |= replaceTokens(value, rowLoader, loaded);
                }
            }
        }
        return changed;
    }

    /**
     * @return 不是占位符返回 null（调用方据此判断"这个值不用动"）
     */
    private static String plainValue(String value, LongFunction<JobDatasource> rowLoader,
                                     Map<Long, JobDatasource> loaded) {
        Matcher matcher = TOKEN.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        boolean isPassword = KIND_PWD.equals(matcher.group(1));
        long datasourceId = Long.parseLong(matcher.group(2));
        JobDatasource datasource = loaded.get(datasourceId);
        if (datasource == null) {
            datasource = rowLoader.apply(datasourceId);
            if (datasource == null) {
                throw new IllegalStateException("数据源 id=" + datasourceId
                        + " 已经不存在，job_json 里的账密引用还原不了，任务未下发");
            }
            loaded.put(datasourceId, datasource);
        }
        String stored = isPassword ? datasource.getJdbcPassword() : datasource.getJdbcUsername();
        if (StringUtils.isBlank(stored)) {
            // 建任务时有口令、现在库里是空的：静默塞个空串只会让执行器报一句"认证失败"，
            // 排查方向还会被带到目标库上去，所以这里必须把话说清在派发这一步。
            throw new IllegalStateException("数据源 id=" + datasourceId + " 的"
                    + (isPassword ? "口令" : "用户名") + "已被清空，任务未下发；请重新生成该作业的 JSON");
        }
        // 库里也可能是明文（早期版本或手工 SQL 写入的行），解不开就用原值——和 JSONUtils 的历史口径一致
        String plain = AESUtil.decrypt(stored);
        return plain == null ? stored : plain;
    }
}
