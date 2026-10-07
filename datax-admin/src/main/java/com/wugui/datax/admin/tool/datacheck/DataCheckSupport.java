package com.wugui.datax.admin.tool.datacheck;

import com.wugui.datax.admin.entity.JobDatasource;
import com.wugui.datax.admin.tool.query.BaseQueryTool;
import com.wugui.datax.admin.tool.query.QueryToolFactory;
import com.wugui.datax.admin.util.SqlSafeIdentifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一致性比对（批次17，闭源商业包的公开侧接缝）。
 *
 * <h2>它做与不做</h2>
 * 公开侧只提供"发起一次比对所需的最小原语"：数据源配对校验、共有表清单、逐表精确行数、
 * checksum SQL 模板。真正的比对执行器（pk 分块 CRC32、抽样策略、差异行定位、报告落盘）
 * 在闭源 pro 依赖里（spring.factories 接线，JAR 放进 lib/ 即生效、拿走即消失）。
 * <ol>
 * <li>这样切的原因：比对算法与报告格式是商业资产；而"怎么连数据源、怎么校验标识符、
 *     怎么拿表清单"是平台已有的能力，闭源侧复用而不是自带 JDBC（一套凭据解密口径）；</li>
 * <li>公开版没有 pro JAR 时，比对发起直接给出"未安装"的明确报错 —— 同步全链路零影响；
 *     红线：公开版真能独立用，商业版挡的是"交不掉"，不是"跑不了"。</li>
 * </ol>
 */
public final class DataCheckSupport {

    private DataCheckSupport() {
    }

    /**
     * 校验"这一对数据源能不能发起比对"：两边都必须是 MySQL 系（mysql/tdsql，
     * TDSQL proxy 走 MySQL 协议）。返回错误消息；null = 可以发起。
     */
    public static String checkPair(JobDatasource source, JobDatasource target) {
        if (source == null || target == null) {
            return "来源与目标数据源都必须给出";
        }
        String sErr = mysqlFamilyOrWhy(source, "来源");
        if (sErr != null) {
            return sErr;
        }
        return mysqlFamilyOrWhy(target, "目标");
    }

    /**
     * 两边共有的表清单（按名归一，小写比较；两边都有才可比对）。
     * 任何一侧连不上/查不到表，异常原样抛出（发起比对失败要看得见原因）。
     */
    public static List<String> commonTables(JobDatasource source, JobDatasource target) {
        BaseQueryTool sTool = QueryToolFactory.getByDbType(source);
        BaseQueryTool tTool = QueryToolFactory.getByDbType(target);
        List<String> sTables = lowerSet(sTool.getTableNames());
        List<String> tTables = lowerSet(tTool.getTableNames());

        List<String> common = new ArrayList<>();
        for (String t : sTables) {
            if (tTables.contains(t)) {
                common.add(t);
            }
        }
        return common;
    }

    /**
     * 逐表精确行数比对。
     *
     * @return {表名: {source: n, target: m, match: true/false}}
     */
    public static Map<String, Map<String, Object>> countCompare(
            JobDatasource source, JobDatasource target, List<String> tables) {
        // 先过标识符闸口（纯前置、零连接），再建数据源连接 —— 错误输入不该消耗连接
        List<String> checked = new ArrayList<>();
        for (String table : tables) {
            checked.add(SqlSafeIdentifier.check(table, "比对表名"));
        }
        BaseQueryTool sTool = QueryToolFactory.getByDbType(source);
        BaseQueryTool tTool = QueryToolFactory.getByDbType(target);
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (String table : checked) {
            Map<String, Object> row = new HashMap<>();
            row.put("source", sTool.countTable(table));
            row.put("target", tTool.countTable(table));
            row.put("match", row.get("source").equals(row.get("target")));
            result.put(table, row);
        }
        return result;
    }

    /**
     * 逐表 checksum 模板（BIT_XOR(CRC32(CONCAT_WS('#', 列...)))）：
     * 公开侧只出 SQL 文本；分块大小、超时、并发与差异行定位在闭源执行器里 ——
     * 模板是公开常识，策略是资产。
     */
    public static String checksumSql(String table, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("checksum 必须给出列清单（从 getColumns 取）");
        }
        String checkedTable = SqlSafeIdentifier.check(table, "比对表名");
        StringBuilder sb = new StringBuilder("SELECT BIT_XOR(CRC32(CONCAT_WS('#'");
        for (String c : columns) {
            String checked = SqlSafeIdentifier.check(c, "比对列名");
            sb.append(", IFNULL(CAST(").append(checked).append(" AS CHAR), 'NULL')");
        }
        sb.append("))) FROM ").append(checkedTable);
        return sb.toString();
    }

    private static String mysqlFamilyOrWhy(JobDatasource ds, String side) {
        String type = ds.getDatasource();
        if (type == null || !("mysql".equalsIgnoreCase(type.trim())
                || "tdsql".equalsIgnoreCase(type.trim()))) {
            return side + "数据源类型必须是 mysql 或 tdsql（当前：" + type + "）";
        }
        return null;
    }

    private static List<String> lowerSet(List<String> names) {
        List<String> out = new ArrayList<>();
        if (names != null) {
            for (String n : names) {
                out.add(n == null ? "" : n.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
