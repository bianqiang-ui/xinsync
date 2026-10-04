package com.wugui.datax.admin.tool.tdsql;

import com.wugui.datax.admin.entity.TdsqlShardRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 从**分片规则表**产出 TDSQL 分布式 DDL：{@link TdsqlDdlRewriter} 的第一个调用方。
 *
 * 两条不可让步的设计：
 * 1) 这里只做"规则是否自洽"的判断，产出路径只有 TdsqlDdlRewriter.rewrite 一条。
 *    生成器自己不拼分布式子句——一旦有人在这里拼一份、改写在别处又一份，两张表的产物就会分叉，
 *    而对不上的是提交给数据库的那条语句。
 * 2) 规则不自洽时**报错并把原文还回去**，不静默挑一个能跑的类型。
 *    分片键丢了就当成单表建，表是能建起来的，直到第一次数据倾斜才发现，
 *    那时已经灌了几千万行，代价比在这里失败大两个数量级。
 */
public final class TdsqlDdlGenerator {

    private TdsqlDdlGenerator() {
    }

    /**
     * @param rule      一条启用的分片规则（来自 {@link TdsqlShardRules#singleEnabled}）
     * @param createDdl 待改造的单条 CREATE TABLE 原文
     * @return 成功时 ddl 为改造产物、notes 末尾带一条规则依据说明；
     *         失败时 success=false、ddl 为**原文**、notes 只有一条失败原因
     */
    public static TdsqlDdlRewriter.Result generate(TdsqlShardRule rule, String createDdl) {
        String problem = ruleProblem(rule);
        if (problem != null) {
            return new TdsqlDdlRewriter.Result(false, createDdl, Collections.singletonList(problem));
        }

        // 表名对不上就不往下走：改写器只看语句内容，不知道规则说的是哪张表。
        // 少了这一步，把 A 表的规则配给 B 表的 DDL 会顺顺利利产出一条"看着对"的分片表语句。
        String ddlTable = TdsqlDdlRewriter.tableNameOf(createDdl);
        if (ddlTable == null) {
            return new TdsqlDdlRewriter.Result(false, createDdl,
                    Collections.singletonList("无法从这条语句里确认表名（入口必须是一条完整的 CREATE TABLE），"
                            + "与规则 " + rule.getLogicDb() + "." + rule.getLogicTable() + " 无从核对"));
        }
        if (!sameName(ddlTable, rule.getLogicTable())) {
            return new TdsqlDdlRewriter.Result(false, createDdl,
                    Collections.singletonList("规则说的是逻辑表 " + rule.getLogicDb() + "." + rule.getLogicTable()
                            + "，传进来的 DDL 建的却是 " + ddlTable + " —— 表名对不上，拒绝生成"));
        }

        TdsqlTableType tableType = TdsqlTableType.valueOf(rule.getTableType().trim().toUpperCase(Locale.ROOT));
        TdsqlDdlRewriter.Result rewritten = TdsqlDdlRewriter.rewrite(
                createDdl, tableType, tableType == TdsqlTableType.SHARD ? rule.getShardKey() : null);
        if (!rewritten.isSuccess()) {
            return rewritten;
        }

        List<String> notes = new ArrayList<>(rewritten.getNotes());
        notes.add("依据规则 id=" + rule.getId() + "（" + rule.getLogicDb() + "." + rule.getLogicTable()
                + "，类型 " + tableType.name()
                + (tableType == TdsqlTableType.SHARD ? "，分片键 " + rule.getShardKey() : "") + "）");
        return new TdsqlDdlRewriter.Result(true, rewritten.getDdl(), notes);
    }

    /**
     * 规则自身的毛病，返回 null 表示规则可用。
     */
    private static String ruleProblem(TdsqlShardRule rule) {
        if (rule == null) {
            return "没有拿到分片规则：本工具只按规则表里的配置生成 DDL，不接受无规则的默认类型";
        }
        if (!rule.isEnabledRule()) {
            return "规则 id=" + rule.getId() + " 处于停用状态（enabled=" + rule.getEnabled() + "），不据它生成 DDL";
        }
        if (isBlank(rule.getLogicDb()) || isBlank(rule.getLogicTable())) {
            return "规则 id=" + rule.getId() + " 没写全逻辑库/逻辑表名（当前："
                    + rule.getLogicDb() + "." + rule.getLogicTable() + "）";
        }
        String type = rule.getTableType();
        if (isBlank(type)) {
            return "规则 id=" + rule.getId() + " 没写表类型，可选值：" + TdsqlTableType.SHARD.name() + " / "
                    + TdsqlTableType.BROADCAST.name() + " / " + TdsqlTableType.SINGLE.name();
        }
        TdsqlTableType tableType;
        try {
            tableType = TdsqlTableType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return "规则 id=" + rule.getId() + " 的表类型 \"" + type + "\" 不认识，可选值："
                    + TdsqlTableType.SHARD.name() + " / " + TdsqlTableType.BROADCAST.name() + " / "
                    + TdsqlTableType.SINGLE.name();
        }

        boolean hasKey = !isBlank(rule.getShardKey());
        if (tableType == TdsqlTableType.SHARD) {
            if (!hasKey) {
                return "规则 id=" + rule.getId() + " 是分片表却没写分片键列 —— 分片键不能猜，"
                        + "猜错要整表重分布";
            }
            if (!isBareIdentifier(rule.getShardKey().trim())) {
                return "规则 id=" + rule.getId() + " 的分片键 \"" + rule.getShardKey()
                        + "\" 不是一个单独的列名：分片键不接受反引号、库表前缀、逗号或多列组合，"
                        + "请填写裸列名（复合分片键本工具不猜，需人工确认）";
            }
            if (rule.getShardNum() == null || rule.getShardNum() < 1) {
                return "规则 id=" + rule.getId() + " 是分片表但没有有效的分片数（当前 " + rule.getShardNum()
                        + "）：分片数只作记录与校验用，缺了它无法核对实际部署";
            }
        } else if (hasKey) {
            return "规则 id=" + rule.getId() + " 的类型是 " + tableType.name()
                    + "（不分片），却又写了分片键 \"" + rule.getShardKey()
                    + "\" —— 两者矛盾，请删掉分片键或把类型改成分片表";
        }

        if (tableType == TdsqlTableType.SINGLE && rule.getShardNum() != null && rule.getShardNum() > 1) {
            return "规则 id=" + rule.getId() + " 是单表却写了分片数 " + rule.getShardNum() + " —— 两者矛盾";
        }
        if (!isBlank(rule.getAutoIncrementCol()) && isBlank(rule.getSequenceName())) {
            return "规则 id=" + rule.getId() + " 标了自增列 " + rule.getAutoIncrementCol()
                    + " 却没写序列名：分片表上的自增列必须由序列发号，否则各分片各发一套，主键必然冲突";
        }
        return null;
    }

    /**
     * 只认最常见的裸列名写法。反引号、点号、逗号、空格一律拒：
     * 这些写法每一种都对应一种"用户其实是想指定别的东西"的歧义，而歧义在生成器这一层没有出路。
     */
    private static boolean isBareIdentifier(String s) {
        if (s.isEmpty()) {
            return false;
        }
        char first = s.charAt(0);
        if (!((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z') || first == '_')) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '$';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameName(String a, String b) {
        return a.trim().toUpperCase(Locale.ROOT).equals(b.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
