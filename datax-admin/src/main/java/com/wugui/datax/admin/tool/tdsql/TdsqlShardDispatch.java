package com.wugui.datax.admin.tool.tdsql;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wugui.datax.admin.core.conf.JobAdminConfig;
import com.wugui.datax.admin.entity.JobDatasource;
import com.wugui.datax.admin.entity.TdsqlShardRule;
import com.wugui.datax.admin.mapper.TdsqlShardRuleMapper;
import com.wugui.datax.admin.tool.datax.DsSecretPlaceholder;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SHARDING_BROADCAST → 每片一份 jobJson 的**接线层**（批次16 / T2-B）。
 *
 * <p>与 {@link TdsqlShardSlicer} 的分工：Slicer 是纯函数（jobJson + 规则 → 切片 jobJson），
 * 本类负责"这个任务该不该切、用哪条规则切"的现场判定，全部失败模式都返回 null 交回调用方
 * 走**现状行为**（N 份全量）——接线层绝不把"取不到规则/识别不了任务"升级成触发失败，
 * 那会把没配规则的老任务挡在门外（施工单红线3）。
 *
 * <p>目标库怎么找：reader 的账密占位符 {@code @@DATAX_DS_<USER|PWD>:<datasourceId>@@}
 * 指向 reader 数据源（即同步的**来源**库）；writer 的占位符指向**目标**库。
 * 分片规则按 datasourceId 登记（{@code tdsql_shard_rule.datasource_id}），所以规则查的是
 * writer 侧的 id——从 writer.parameter.username 里解出。
 * jobJson 里没有占位符（老密文任务）时识别不了目标库，返回 null 走现状（老任务零感知）。
 */
public final class TdsqlShardDispatch {

    /** 从 jobJson 的 writer.parameter.username 里解出目标数据源 id（占位符整串匹配，见 DsSecretPlaceholder.TOKEN）。 */
    private static final Pattern WRITER_DS = Pattern.compile(
            "@@DATAX_DS_(?:USER|PWD):(\\d+)@@");

    /** 结构化取值后判断"这个值是不是占位符"用的子串（DsSecretPlaceholder.TOKEN_PREFIX）。 */
    private static final String TOKEN_INNER = "@@DATAX_DS_";

    private TdsqlShardDispatch() {
    }

    /**
     * @param jobJson 已还原账密**之前**的派发 JSON（占位符还在，明文还原在 processTrigger 后段）
     * @param glueType 任务的 glueType（BEAN 才有 jobJson 语义）
     * @return 可切片的启用规则；任何一种"不该切/切不了"都返回 null
     */
    public static TdsqlShardRule findSlicableRule(String jobJson, String glueType) {
        if (jobJson == null || jobJson.trim().isEmpty()) {
            return null;
        }
        // 红线4：GLUE 任务没有结构化 jobJson，不切片
        if (glueType == null || !"BEAN".equals(glueType.trim().toUpperCase(java.util.Locale.ROOT))) {
            return null;
        }
        long writerDsId = extractWriterDatasourceId(jobJson);
        if (writerDsId < 0) {
            // 老密文任务（无占位符）：识别不了目标库，保持现状
            return null;
        }
        JobDatasource ds = JobAdminConfig.getAdminConfig().getJobDatasourceMapper().selectById(writerDsId);
        if (ds == null || isBlank(ds.getDatasource())) {
            // 数据源已被删：随后账密还原会失败并留下失败日志，这里不提前改变行为
            return null;
        }
        if (!"tdsql".equalsIgnoreCase(ds.getDatasource().trim())) {
            // 只有目标数据源类型是 TDSQL 的任务才走分片广播切片
            return null;
        }
        String logicDb = ds.getDatabaseName();
        if (isBlank(logicDb)) {
            return null;
        }
        // 逻辑表：规则表按 (datasourceId, logicDb, logicTable) 登记；一张任务一个 writer 表，
        // 从 jobJson 的 writer.connection[0].table[0] 解出
        String logicTable = extractWriterTable(jobJson);
        if (isBlank(logicTable)) {
            return null;
        }
        TdsqlShardRuleMapper mapper = JobAdminConfig.getAdminConfig().getTdsqlShardRuleMapper();
        if (mapper == null) {
            return null;
        }
        QueryWrapper<TdsqlShardRule> wrapper = new QueryWrapper<TdsqlShardRule>();
        wrapper.eq("datasource_id", writerDsId);
        wrapper.eq("logic_db", logicDb.trim());
        wrapper.eq("logic_table", logicTable.trim());
        List<TdsqlShardRule> rows = mapper.selectList(wrapper);
        try {
            // 红线3 的另一面：取不到/多条启用 → null（现状行为），这里不吞 singleEnabled 的
            // 诊断信息——日志一条，仍不改变行为
            return TdsqlShardRules.singleEnabled(logicDb.trim(), logicTable.trim(), rows);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 规则找到后做最后一步校验：类型必须是 SHARD（BROADCAST/SINGLE 切片语义未定义）。 */
    public static boolean isShardRule(TdsqlShardRule rule) {
        if (rule == null || rule.getTableType() == null || !rule.isEnabledRule()) {
            return false;
        }
        return "SHARD".equals(rule.getTableType().trim().toUpperCase(java.util.Locale.ROOT));
    }

    static long extractWriterDatasourceId(String jobJson) {
        if (jobJson == null || jobJson.trim().isEmpty()) {
            return -1;
        }
        // 结构化解析到 writer.parameter 再取值：全文正则会抓到**第一个**占位符——
        // 那是 reader（来源库）的 id，用它查规则 = 拿 A 库的规则改 B 库的表（第一版实测踩中）。
        com.alibaba.fastjson.JSONObject root;
        try {
            root = com.alibaba.fastjson.JSONObject.parseObject(jobJson);
        } catch (Exception e) {
            return -1;
        }
        com.alibaba.fastjson.JSONArray content = root.getJSONArray("content");
        if (content == null || content.isEmpty()) {
            return -1;
        }
        com.alibaba.fastjson.JSONObject writer = content.getJSONObject(0).getJSONObject("writer");
        if (writer == null) {
            return -1;
        }
        com.alibaba.fastjson.JSONObject param = writer.getJSONObject("parameter");
        if (param == null) {
            return -1;
        }
        String username = param.getString("username");
        String password = param.getString("password");
        String token = (username != null && username.contains(TOKEN_INNER)) ? username
                : (password != null && password.contains(TOKEN_INNER)) ? password : null;
        if (token == null) {
            return -1;
        }
        Matcher m = WRITER_DS.matcher(token);
        if (!m.find()) {
            return -1;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String extractWriterTable(String jobJson) {
        if (jobJson == null || jobJson.trim().isEmpty()) {
            return null;
        }
        com.alibaba.fastjson.JSONObject root;
        try {
            root = com.alibaba.fastjson.JSONObject.parseObject(jobJson);
        } catch (Exception e) {
            return null;
        }
        com.alibaba.fastjson.JSONArray content = root.getJSONArray("content");
        if (content == null || content.isEmpty()) {
            return null;
        }
        com.alibaba.fastjson.JSONObject writer = content.getJSONObject(0).getJSONObject("writer");
        if (writer == null) {
            return null;
        }
        com.alibaba.fastjson.JSONObject param = writer.getJSONObject("parameter");
        if (param == null) {
            return null;
        }
        com.alibaba.fastjson.JSONArray connections = param.getJSONArray("connection");
        if (connections == null || connections.isEmpty()) {
            return null;
        }
        com.alibaba.fastjson.JSONArray tables = connections.getJSONObject(0).getJSONArray("table");
        if (tables == null || tables.isEmpty()) {
            return null;
        }
        return tables.getString(0);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
