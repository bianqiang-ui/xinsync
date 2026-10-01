package com.wugui.datax.admin.tool.tdsql;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 分布式 DDL 改写的规则单测。
 * 夹具用的是社区里最常见的老表形态：自增 id 做主键 + 业务 uid 做分片键 + 一个不含分片键的唯一索引。
 */
public class TdsqlDdlRewriterTest {

    private static final String ORDERS =
            "CREATE TABLE `orders` (\n"
            + "  `id` bigint(20) NOT NULL AUTO_INCREMENT,\n"
            + "  `uid` bigint(20) DEFAULT NULL,\n"
            + "  `order_no` varchar(64) NOT NULL,\n"
            + "  `amount` decimal(10,2) NOT NULL DEFAULT '0.00',\n"
            + "  `remark` varchar(200) DEFAULT NULL,\n"
            + "  PRIMARY KEY (`id`),\n"
            + "  UNIQUE KEY `uk_order_no` (`order_no`),\n"
            + "  KEY `idx_uid` (`uid`)\n"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

    @Test
    public void shardTableMustPutShardKeyIntoPkAndEveryUk() {
        TdsqlDdlRewriter.Result r =
                TdsqlDdlRewriter.rewrite(ORDERS, TdsqlTableType.SHARD, "uid");

        assertTrue(r.getDdl() + " / " + r.getNotes(), r.isSuccess());
        String ddl = collapse(r.getDdl());
        // 主键与唯一索引都被补上分片键
        assertTrue(ddl, ddl.contains("PRIMARY KEY (`id`, `uid`)"));
        assertTrue(ddl, ddl.contains("UNIQUE KEY `uk_order_no` (`order_no`, `uid`)"));
        // 分片键补了非空
        assertTrue(ddl, ddl.contains("`uid` bigint(20) NOT NULL"));
        // 追加子句
        assertTrue(ddl, ddl.contains("SHARDKEY = `uid`"));
        assertTrue("改写结果应以分号结尾：" + ddl, ddl.endsWith(";"));
        // 普通索引不该被动过
        assertTrue(ddl, ddl.contains("KEY `idx_uid` (`uid`)"));
        // 结构性改动都有说明
        assertHasNote(r, "主键");
        assertHasNote(r, "唯一索引");
        assertHasNote(r, "NOT NULL");
        assertHasNote(r, "TDSQL_SEQUENCE");
    }

    @Test
    public void ordinaryCommaInsideColumnTypeMustNotSplitItems() {
        TdsqlDdlRewriter.Result r =
                TdsqlDdlRewriter.rewrite(ORDERS, TdsqlTableType.SHARD, "uid");
        String ddl = collapse(r.getDdl());
        // decimal(10,2) 里的逗号若被当成顶层分隔符，这一列就会被拆坏
        assertTrue(ddl, ddl.contains("`amount` decimal(10,2) NOT NULL DEFAULT '0.00'"));
    }

    @Test
    public void broadcastTableUsesKeywordAndKeepsIndexesUntouched() {
        TdsqlDdlRewriter.Result r =
                TdsqlDdlRewriter.rewrite(ORDERS, TdsqlTableType.BROADCAST, null);

        assertTrue(r.getDdl(), r.isSuccess());
        String ddl = collapse(r.getDdl());
        assertTrue(ddl, ddl.contains("SHARDKEY = noshardkey_allset"));
        assertFalse("广播表不应改写主键", ddl.contains("PRIMARY KEY (`id`, `"));
        assertTrue(ddl, ddl.contains("PRIMARY KEY (`id`)"));
    }

    @Test
    public void singleTableStaysAsIs() {
        TdsqlDdlRewriter.Result r =
                TdsqlDdlRewriter.rewrite(ORDERS, TdsqlTableType.SINGLE, null);
        assertTrue(r.getDdl(), r.isSuccess());
        assertFalse(r.getDdl(), collapse(r.getDdl()).contains("SHARDKEY"));
    }

    @Test
    public void missingShardKeyColumnFailsLoudlyInsteadOfSilentlyPassing() {
        TdsqlDdlRewriter.Result r =
                TdsqlDdlRewriter.rewrite(ORDERS, TdsqlTableType.SHARD, "not_exist");
        assertFalse(r.getDdl(), r.isSuccess());
        assertTrue(r.getNotes().toString(), r.getNotes().get(0).contains("不存在"));
        // 失败时把原文还给调用方，不能产出半改不建的 DDL
        assertTrue(r.getDdl(), r.getDdl().startsWith("CREATE TABLE `orders`"));
    }

    @Test
    public void blankShardKeyOnShardTableFails() {
        TdsqlDdlRewriter.Result r =
                TdsqlDdlRewriter.rewrite(ORDERS, TdsqlTableType.SHARD, "  ");
        assertFalse(r.getDdl(), r.isSuccess());
    }

    @Test
    public void alreadyShardKeyAwareDdlIsIdempotent() {
        TdsqlDdlRewriter.Result first =
                TdsqlDdlRewriter.rewrite(ORDERS, TdsqlTableType.SHARD, "uid");
        TdsqlDdlRewriter.Result second =
                TdsqlDdlRewriter.rewrite(first.getDdl(), TdsqlTableType.SHARD, "uid");

        assertTrue(second.getDdl(), second.isSuccess());
        // 第二次不该再生成补列/补非空的说明（幂等）
        for (String note : second.getNotes()) {
            assertFalse(note, note.contains("已将"));
            assertFalse(note, note.contains("已补 NOT NULL"));
        }
    }

    private static void assertHasNote(TdsqlDdlRewriter.Result r, String keyword) {
        for (String note : r.getNotes()) {
            if (note.contains(keyword)) {
                return;
            }
        }
        throw new AssertionError("notes 里缺少含 " + keyword + " 的说明：" + r.getNotes());
    }

    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ");
    }
}
