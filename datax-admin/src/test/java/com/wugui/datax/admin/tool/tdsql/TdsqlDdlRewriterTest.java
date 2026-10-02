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

    /** mysqldump 5.7+ 对 InnoDB 唯一约束的真实产出形态 */
    private static final String ORDERS_WITH_CONSTRAINT =
            "CREATE TABLE `orders_c` (\n"
            + "  `id` bigint(20) NOT NULL AUTO_INCREMENT,\n"
            + "  `uid` bigint(20) DEFAULT NULL,\n"
            + "  `order_no` varchar(64) NOT NULL,\n"
            + "  PRIMARY KEY (`id`),\n"
            + "  CONSTRAINT `uk_order_no` UNIQUE (`order_no`)\n"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

    /** 建表时不加反引号的写法：列名恰好以 KEY / CHECK 这些关键字的字母开头 */
    private static final String KEYWORD_LOOKALIKE_COLUMNS =
            "CREATE TABLE settle_log (\n"
            + "  id bigint(20) NOT NULL AUTO_INCREMENT,\n"
            + "  key_id bigint(20) DEFAULT NULL,\n"
            + "  check_time datetime DEFAULT NULL,\n"
            + "  PRIMARY KEY (id)\n"
            + ") ENGINE=InnoDB;";

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
        // 真正的幂等判据：SHARDKEY 子句只能有一条。重复追加会让 DDL 直接语法不合法。
        assertTrue("第一次改写后应有且仅有一条 SHARDKEY：" + collapse(first.getDdl()),
                countOf(collapse(first.getDdl()), "SHARDKEY") == 1);
        assertTrue("二次改写后仍只能有一条 SHARDKEY：" + collapse(second.getDdl()),
                countOf(collapse(second.getDdl()), "SHARDKEY") == 1);
    }

    /** mysqldump 导出的是 CONSTRAINT ... UNIQUE 形态，不是 UNIQUE KEY 形态；漏认就等于产出非法 DDL */
    @Test
    public void constraintFormUniqueIndexMustAlsoGetShardKey() {
        TdsqlDdlRewriter.Result r =
                TdsqlDdlRewriter.rewrite(ORDERS_WITH_CONSTRAINT, TdsqlTableType.SHARD, "uid");

        assertTrue(r.getDdl() + " / " + r.getNotes(), r.isSuccess());
        String ddl = collapse(r.getDdl());
        assertTrue(ddl, ddl.contains("CONSTRAINT `uk_order_no` UNIQUE (`order_no`, `uid`)"));
        assertHasNote(r, "唯一索引");
    }

    /** 不带反引号时 key_id / check_time 会被误判成 KEY / CHECK 定义，分片键列就"找不到"了 */
    @Test
    public void columnNamesStartingWithKeywordLettersAreStillColumns() {
        TdsqlDdlRewriter.Result r =
                TdsqlDdlRewriter.rewrite(KEYWORD_LOOKALIKE_COLUMNS, TdsqlTableType.SHARD, "key_id");

        assertTrue("key_id 是列名，不能被当成 KEY 索引定义：" + r.getDdl() + " / " + r.getNotes(),
                r.isSuccess());
        String ddl = collapse(r.getDdl());
        assertTrue(ddl, ddl.contains("SHARDKEY = `key_id`"));
        assertTrue(ddl, ddl.contains("key_id bigint(20) NOT NULL"));
        // 另一条以 CHECK 开头的列同样不能被当成约束而丢掉
        assertTrue(ddl, ddl.contains("check_time datetime"));
    }

    private static void assertHasNote(TdsqlDdlRewriter.Result r, String keyword) {
        for (String note : r.getNotes()) {
            if (note.contains(keyword)) {
                return;
            }
        }
        throw new AssertionError("notes 里缺少含 " + keyword + " 的说明：" + r.getNotes());
    }

    private static int countOf(String s, String token) {
        int n = 0;
        int from = 0;
        while (true) {
            int idx = s.indexOf(token, from);
            if (idx < 0) {
                return n;
            }
            n++;
            from = idx + token.length();
        }
    }

    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ");
    }
}
