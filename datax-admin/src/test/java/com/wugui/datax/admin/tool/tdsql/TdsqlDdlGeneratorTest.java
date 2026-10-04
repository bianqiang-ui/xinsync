package com.wugui.datax.admin.tool.tdsql;

import com.wugui.datax.admin.entity.TdsqlShardRule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 规则驱动 DDL 生成的单测。
 *
 * 覆盖的重心不是"能不能改写出子句"（那部分由 {@link TdsqlDdlRewriterTest} 守），
 * 而是**规则不自洽时必须失败**：每一种"配置写歪了"的形态，如果生成器顺手挑一个能跑的类型，
 * 产物就能建表成功，问题要等到数据倾斜或主键冲突才暴露。
 */
public class TdsqlDdlGeneratorTest {

    private static final String ORDERS =
            "CREATE TABLE `orders` (\n"
            + "  `id` bigint(20) NOT NULL AUTO_INCREMENT,\n"
            + "  `uid` bigint(20) DEFAULT NULL,\n"
            + "  `order_no` varchar(64) NOT NULL,\n"
            + "  PRIMARY KEY (`id`),\n"
            + "  UNIQUE KEY `uk_order_no` (`order_no`)\n"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

    // ---- 正常路径：产物必须与改写器逐字节一致 ----

    @Test
    public void shardRuleProducesExactlyWhatTheRewriterProduces() {
        TdsqlDdlRewriter.Result result = TdsqlDdlGenerator.generate(shardRule(), ORDERS);

        assertTrue(result.isSuccess());
        assertEquals(TdsqlDdlRewriter.rewrite(ORDERS, TdsqlTableType.SHARD, "uid").getDdl(), result.getDdl());
        String last = result.getNotes().get(result.getNotes().size() - 1);
        assertTrue("最后一条说明必须点出依据的规则：" + last, last.contains("id=7"));
        assertTrue(last.contains("demo.orders"));
        assertTrue(last.contains("SHARD"));
    }

    @Test
    public void broadcastAndSingleRulesFollowTheirOwnTypes() {
        TdsqlShardRule broadcast = shardRule();
        broadcast.setTableType("BROADCAST");
        broadcast.setShardKey(null);
        broadcast.setShardNum(null);
        TdsqlDdlRewriter.Result b = TdsqlDdlGenerator.generate(broadcast, ORDERS);
        assertTrue(b.isSuccess());
        assertEquals(TdsqlDdlRewriter.rewrite(ORDERS, TdsqlTableType.BROADCAST, null).getDdl(), b.getDdl());

        TdsqlShardRule single = shardRule();
        single.setTableType("SINGLE");
        single.setShardKey(null);
        single.setShardNum(null);
        TdsqlDdlRewriter.Result s = TdsqlDdlGenerator.generate(single, ORDERS);
        assertTrue(s.isSuccess());
        assertEquals(TdsqlDdlRewriter.rewrite(ORDERS, TdsqlTableType.SINGLE, null).getDdl(), s.getDdl());
    }

    @Test
    public void ruleTypeAndTableNamesAreMatchedCaseInsensitively() {
        // 库里排序规则是 utf8mb4_general_ci：`Orders` 与 `orders` 是同一张表，读侧不能判成两张
        TdsqlShardRule rule = shardRule();
        rule.setTableType(" shard ");
        rule.setLogicTable("ORDERS");
        assertTrue(TdsqlDdlGenerator.generate(rule, ORDERS).isSuccess());
    }

    // ---- 规则不自洽：必须失败，且把原文还回去 ----

    @Test
    public void nullRuleHasNoDefaultType() {
        TdsqlDdlRewriter.Result result = TdsqlDdlGenerator.generate(null, ORDERS);
        assertFailsWith(result, "没有拿到分片规则");
    }

    @Test
    public void disabledRuleIsNotUsed() {
        TdsqlShardRule rule = shardRule();
        rule.setEnabled(0);
        assertFailsWith(TdsqlDdlGenerator.generate(rule, ORDERS), "停用");
    }

    @Test
    public void ruleWithoutNamesOrTypeIsRejected() {
        TdsqlShardRule noDb = shardRule();
        noDb.setLogicDb("  ");
        assertFailsWith(TdsqlDdlGenerator.generate(noDb, ORDERS), "没写全逻辑库/逻辑表名");

        TdsqlShardRule noType = shardRule();
        noType.setTableType(null);
        assertFailsWith(TdsqlDdlGenerator.generate(noType, ORDERS), "没写表类型");
    }

    @Test
    public void unknownTableTypeIsRejectedAndItsLiteralIsNotWrittenIntoTheDdl() {
        TdsqlShardRule rule = shardRule();
        rule.setTableType("sharding");
        TdsqlDdlRewriter.Result result = TdsqlDdlGenerator.generate(rule, ORDERS);
        assertFailsWith(result, "不认识");
        // 认不出类型时绝不能"照着最像的类型改"，也绝不能把脏值写进产物
        assertEquals(ORDERS, result.getDdl());
        assertFalse(result.getDdl().contains("sharding"));
    }

    @Test
    public void shardRuleWithoutShardKeyFailsInsteadOfBecomingASingleTable() {
        TdsqlShardRule rule = shardRule();
        rule.setShardKey(null);
        assertFailsWith(TdsqlDdlGenerator.generate(rule, ORDERS), "没写分片键");
    }

    @Test
    public void shardRuleWithoutShardNumFails() {
        TdsqlShardRule rule = shardRule();
        rule.setShardNum(0);
        assertFailsWith(TdsqlDdlGenerator.generate(rule, ORDERS), "分片数");
    }

    @Test
    public void compoundOrQuotedShardKeyIsRejectedNotGuessed() {
        for (String bad : new String[]{"`uid`", "orders.uid", "uid,oid", "uid)", "(uid", "uid uid2"}) {
            TdsqlShardRule rule = shardRule();
            rule.setShardKey(bad);
            TdsqlDdlRewriter.Result result = TdsqlDdlGenerator.generate(rule, ORDERS);
            assertFailsWith(result, "不是一个单独的列名");
            assertEquals("分片键 \"" + bad + "\" 必须退回原文", ORDERS, result.getDdl());
        }
    }

    @Test
    public void nonShardTypesRejectAnExistingShardKey() {
        TdsqlShardRule broadcast = shardRule();
        broadcast.setTableType("BROADCAST");
        assertFailsWith(TdsqlDdlGenerator.generate(broadcast, ORDERS), "矛盾");

        TdsqlShardRule single = shardRule();
        single.setTableType("SINGLE");
        single.setShardNum(16);
        assertFailsWith(TdsqlDdlGenerator.generate(single, ORDERS), "矛盾");
    }

    @Test
    public void autoIncrementColumnWithoutSequenceFails() {
        TdsqlShardRule rule = shardRule();
        rule.setAutoIncrementCol("id");
        assertFailsWith(TdsqlDdlGenerator.generate(rule, ORDERS), "序列名");

        rule.setSequenceName("sq_orders_id");
        assertTrue(TdsqlDdlGenerator.generate(rule, ORDERS).isSuccess());
    }

    // ---- 规则与 DDL 的配对 ----

    @Test
    public void ruleAndDdlForDifferentTablesNeverMix() {
        String users = ORDERS.replace("`orders`", "`users`");
        TdsqlDdlRewriter.Result result = TdsqlDdlGenerator.generate(shardRule(), users);
        assertFailsWith(result, "表名对不上");
        assertEquals(users, result.getDdl());
    }

    @Test
    public void statementWhoseTableCannotBeNamedIsRejected() {
        // 多语句 / 非 CREATE TABLE：改写器的入口校验会拒，但生成器要先给出一条能说清"没法核对表名"的话
        TdsqlDdlRewriter.Result alter = TdsqlDdlGenerator.generate(
                shardRule(), "ALTER TABLE `orders` ADD COLUMN `note` varchar(10) NULL;");
        assertFailsWith(alter, "无法从这条语句里确认表名");

        TdsqlDdlRewriter.Result empty = TdsqlDdlGenerator.generate(shardRule(), "   ");
        assertFailsWith(empty, "无法从这条语句里确认表名");
    }

    @Test
    public void rewriterProblemsPassThroughWithTheOriginalDdl() {
        TdsqlShardRule rule = shardRule();
        rule.setShardKey("ghost");
        TdsqlDdlRewriter.Result result = TdsqlDdlGenerator.generate(rule, ORDERS);
        assertFalse(result.isSuccess());
        assertEquals(ORDERS, result.getDdl());
        assertNotNull(result.getNotes().get(0));
        assertTrue("改写器的失败原因要原样传出去：" + result.getNotes(),
                result.getNotes().get(0).contains("ghost"));
        // 失败时不加"依据规则"的审计说明，否则会看着像已经生成过了
        assertEquals(1, result.getNotes().size());
    }

    private static TdsqlShardRule shardRule() {
        TdsqlShardRule rule = new TdsqlShardRule();
        rule.setId(7);
        rule.setDatasourceId(3L);
        rule.setLogicDb("demo");
        rule.setLogicTable("orders");
        rule.setTableType("SHARD");
        rule.setShardKey("uid");
        rule.setShardNum(8);
        rule.setEnabled(1);
        return rule;
    }

    private static void assertFailsWith(TdsqlDdlRewriter.Result result, String keyword) {
        assertFalse("本该失败但成功了：" + result.getDdl(), result.isSuccess());
        assertTrue("失败原因里必须点出「" + keyword + "」，实际：" + result.getNotes(),
                result.getNotes().get(0).contains(keyword));
    }
}
