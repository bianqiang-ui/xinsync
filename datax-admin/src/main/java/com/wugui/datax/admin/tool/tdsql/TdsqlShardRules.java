package com.wugui.datax.admin.tool.tdsql;

import com.wugui.datax.admin.entity.TdsqlShardRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 规则表读侧的唯一选择口径：一张逻辑表在任意时刻只许有一条**启用**的规则。
 *
 * 为什么不让调用方各自 `list.get(0)`：`tdsql_shard_rule` 的库级唯一索引只约束
 * (logic_db, logic_table)，"停用几条 + 启用一条"是允许的；而手工把唯一索引删掉、
 * 或者导入历史数据时重复插，就会出现多条启用。那时 `get(0)` 取到哪一条取决于数据库返回顺序 ——
 * 同一个作业两次跑出不同 DDL，是最难查的那类错误。所以这里把"取不到"和"取得多"都判成失败。
 */
public final class TdsqlShardRules {

    private TdsqlShardRules() {
    }

    /**
     * @param logicDb     请求的逻辑库名
     * @param logicTable  请求的逻辑表名
     * @param rows        按 (logicDb, logicTable) 查出来的行，可为空列表，不许为 null
     * @return 唯一那条启用的规则
     * @throws IllegalArgumentException 查不到启用规则、有多条启用规则、或返回的行根本不属于请求的表
     */
    public static TdsqlShardRule singleEnabled(String logicDb, String logicTable, List<TdsqlShardRule> rows) {
        if (isBlank(logicDb) || isBlank(logicTable)) {
            throw new IllegalArgumentException(
                    "查分片规则必须给全逻辑库与逻辑表名（当前：" + logicDb + "." + logicTable + "）");
        }
        if (rows == null) {
            throw new IllegalArgumentException(
                    "分片规则查询返回 null（映射没接上或查询失败），不得当成「这张表没有规则」处理");
        }

        List<String> foreign = new ArrayList<>();
        List<String> enabledIds = new ArrayList<>();
        TdsqlShardRule hit = null;
        for (TdsqlShardRule row : rows) {
            if (row == null) {
                foreign.add("(空行)");
                continue;
            }
            // 返回的行必须真的属于请求的那张表：mapper 的条件写漏时，这里兜住"拿 A 表规则改 B 表"
            if (!sameName(row.getLogicDb(), logicDb) || !sameName(row.getLogicTable(), logicTable)) {
                foreign.add("id=" + row.getId() + " 是 " + row.getLogicDb() + "." + row.getLogicTable());
                continue;
            }
            if (row.isEnabledRule()) {
                enabledIds.add("id=" + row.getId());
                hit = row;
            }
        }
        if (!foreign.isEmpty()) {
            throw new IllegalArgumentException("按 " + logicDb + "." + logicTable + " 查规则，却拿回了别的表的行："
                    + join(foreign) + " —— 查询条件与返回行不符，先修查询再谈生成");
        }
        if (enabledIds.isEmpty()) {
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("逻辑表 " + logicDb + "." + logicTable
                        + " 没有登记任何分片规则 —— 请先在规则表录入一条再生成 DDL");
            }
            throw new IllegalArgumentException("逻辑表 " + logicDb + "." + logicTable
                    + " 没有启用的分片规则（查到 " + rows.size() + " 行，全部停用）—— 请先启用一条再生成 DDL");
        }
        if (enabledIds.size() > 1) {
            throw new IllegalArgumentException("逻辑表 " + logicDb + "." + logicTable + " 有 "
                    + enabledIds.size() + " 条启用规则（id=" + join(enabledIds)
                    + "）—— 一张逻辑表只能有一条启用规则，取哪条取决于数据库返回顺序，必须人工停用多余的");
        }
        return hit;
    }

    private static boolean sameName(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        // 库里的排序规则是 utf8mb4_general_ci（大小写不敏感），读侧比较必须同口径，
        // 否则 `User` 与 `user` 在库里是同一张表，在这里会被判成两张
        return a.trim().toUpperCase(Locale.ROOT).equals(b.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
