package com.wugui.datax.admin.tool.tdsql;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.wugui.datax.admin.entity.TdsqlShardRule;
import com.wugui.datax.admin.util.SqlSafeIdentifier;

import java.util.List;
import java.util.Locale;

/**
 * 批次16（T2-B）：把 SHARDING_BROADCAST 下发的 jobJson 按**来源侧**切成每执行器一片。
 *
 * <p>四条红线（施工单 tmp/draft/b16-t2b-construction-order.md）：
 * <ol>
 * <li>只做来源侧切片（splitPk 列取模），<b>绝不实现目标侧路由</b>——TDSQL 内核的分片算法未实测校准，
 *     自研路由 = 静默写错分片。写入 proxy 的逻辑表，路由交给内核（模式 A）。</li>
 * <li>reader 是 querySql 自由文本时<b>明确拒绝</b>，绝不静默改写别人的 SQL。</li>
 * <li>调用方（JobTrigger）在取不到启用规则时必须保持现状行为（N 份全量），本类不负责"找不到规则"的分支。</li>
 * <li>本类只处理 jobJson 的内容；广播语义（broadcastIndex/Total）仍由触发链路写入。</li>
 * </ol>
 *
 * <p>reader JSON 形状（BaseReaderPlugin.build 实测）：{@code parameter.connection[0]} 里
 * {@code querySql}（数组）与 {@code table}（数组）二选一；{@code parameter.where} 仅在
 * whereParam 非空时存在；{@code parameter.splitPk} 无条件存在。切片 = 追加
 * {@code AND (pk % total) = index}：pk 列来自规则的 pkColumns 快照第一列，经
 * {@link SqlSafeIdentifier} 校验后拼入（标识符位置唯一闸口，与全仓口径一致）。
 */
public final class TdsqlShardSlicer {

    private TdsqlShardSlicer() {
    }

    /**
     * @param jobJson 派发前的完整 jobJson（reader/writer/settings），非 null
     * @param rule    该目标逻辑表唯一启用的一条 SHARD 规则（tableType=SHARD 由调用方保证）
     * @param index   当前分片号，[0, total)
     * @param total   分片总数，≥1
     * @return 每片一份的新 jobJson（深拷贝后仅改 reader 侧，writer/settings 原样）
     * @throws IllegalArgumentException jobJson 形状不支持切片时，原因唯一且明确
     */
    public static String slice(String jobJson, TdsqlShardRule rule, int index, int total) {
        if (jobJson == null || jobJson.trim().isEmpty()) {
            throw new IllegalArgumentException("jobJson 为空，无从切片");
        }
        if (rule == null) {
            throw new IllegalArgumentException("切片必须给出目标表的分片规则（SHARD 类型）");
        }
        if (total < 1) {
            throw new IllegalArgumentException("分片总数必须 ≥1（当前：" + total + "）");
        }
        if (index < 0 || index >= total) {
            throw new IllegalArgumentException("分片号必须在 [0," + total + ") 内（当前：" + index + "）");
        }

        JSONObject root;
        try {
            root = JSONObject.parseObject(jobJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("jobJson 不是合法 JSON，无法切片：" + e.getMessage());
        }

        // ---- reader 侧定位：job.content[*].reader.parameter.connection[0] ----
        JSONArray content = root.getJSONArray("content");
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("jobJson 没有 content 数组，形状不支持切片");
        }
        if (content.size() > 1) {
            throw new IllegalArgumentException("content 里有 " + content.size()
                    + " 个任务元素，多表任务的分片口径未定义，拒绝猜测（当前只支持单表切片）");
        }
        JSONObject reader = content.getJSONObject(0).getJSONObject("reader");
        if (reader == null) {
            throw new IllegalArgumentException("content[0] 没有 reader，形状不支持切片");
        }
        JSONObject readerParam = reader.getJSONObject("parameter");
        if (readerParam == null) {
            throw new IllegalArgumentException("reader 没有 parameter，形状不支持切片");
        }
        JSONArray connections = readerParam.getJSONArray("connection");
        if (connections == null || connections.isEmpty()) {
            throw new IllegalArgumentException("reader.parameter 没有 connection 数组，形状不支持切片");
        }
        if (connections.size() > 1) {
            throw new IllegalArgumentException("reader 有 " + connections.size()
                    + " 组 connection，多连接的分片口径未定义，拒绝猜测");
        }
        JSONObject conn = connections.getJSONObject(0);

        // ---- 红线2：querySql 自由文本拒绝切片 ----
        if (conn.containsKey("querySql")) {
            throw new IllegalArgumentException("reader 使用 querySql 自由文本模式，切片会改写用户 SQL —— "
                    + "拒绝切片（改用表模式：目标表配 where 条件，或将表名/条件写进任务配置）");
        }
        JSONArray tables = conn.getJSONArray("table");
        if (tables == null || tables.isEmpty()) {
            throw new IllegalArgumentException("reader.connection[0] 没有 table 数组，形状不支持切片");
        }
        if (tables.size() > 1) {
            throw new IllegalArgumentException("reader 一次读 " + tables.size() + " 张表（" + tables
                    + "），多表切片口径未定义，拒绝猜测");
        }

        // ---- 表名必须与规则一致（同 TdsqlDdlGenerator 的对表口径，大小写不敏感）----
        String readerTable = tables.getString(0);
        if (!sameName(readerTable, rule.getLogicTable())) {
            throw new IllegalArgumentException("reader 读的是 " + readerTable + "，规则登记的是 "
                    + rule.getLogicDb() + "." + rule.getLogicTable() + " —— 表名对不上，拒绝切片");
        }

        // ---- splitPk 列：pkColumns 快照第一列，过标识符闸口 ----
        String pkColumns = rule.getPkColumns();
        if (pkColumns == null || pkColumns.trim().isEmpty()) {
            throw new IllegalArgumentException("规则 id=" + rule.getId() + "（" + rule.getLogicDb() + "."
                    + rule.getLogicTable() + "）没有 pkColumns 快照，无法确定切片列 —— 拒绝切片，不猜主键");
        }
        List<String> pks = SqlSafeIdentifier.splitAndCheck(pkColumns, "分片规则 pkColumns");
        if (pks.isEmpty()) {
            throw new IllegalArgumentException("规则 pkColumns 快照解析不出任何合法列名，拒绝切片");
        }
        String sliceColumn = pks.get(0);

        // ---- 追加取模条件：where 已存在 → AND 拼接；不存在 → 新增 where 键 ----
        String modulus = "(" + sliceColumn + " % " + total + ") = " + index;
        String existingWhere = readerParam.getString("where");
        if (existingWhere != null && !existingWhere.trim().isEmpty()) {
            readerParam.put("where", existingWhere + " AND " + modulus);
        } else {
            readerParam.put("where", modulus);
        }

        return root.toJSONString();
    }

    private static boolean sameName(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        // 与 TdsqlShardRules.sameName 同口径：库里 utf8mb4_general_ci 大小写不敏感
        return a.trim().toUpperCase(Locale.ROOT).equals(b.trim().toUpperCase(Locale.ROOT));
    }
}
