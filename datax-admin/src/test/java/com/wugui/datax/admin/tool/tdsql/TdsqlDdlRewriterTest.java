package com.wugui.datax.admin.tool.tdsql;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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

    /** 分片键可空且带 DEFAULT NULL（mysqldump 对可空列的标准产出），注释里还故意写了 NOT NULL */
    private static final String COMMENT_TRAP =
            "CREATE TABLE `comment_trap` (\n"
            + "  `id` bigint(20) NOT NULL AUTO_INCREMENT,\n"
            + "  `shard_k` bigint(20) DEFAULT NULL COMMENT '这里写着 NOT NULL 也只是注释',\n"
            + "  PRIMARY KEY (`id`)\n"
            + ") ENGINE=InnoDB;";

    /** 分片键可空但有真实默认值：补非空时只能去掉 DEFAULT NULL，不能把 DEFAULT '7' 一起删掉 */
    private static final String KEEP_DEFAULT =
            "CREATE TABLE `keep_default` (\n"
            + "  `id` bigint(20) NOT NULL AUTO_INCREMENT,\n"
            + "  `shard_k` int(11) DEFAULT '7',\n"
            + "  PRIMARY KEY (`id`)\n"
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
        // 只断"不含 SHARDKEY"是弱断言：返回空串、或把表体改坏了也照样绿。
        // 单表的契约是原样返回，所以逐字比对（空白折叠后）。
        assertEquals(collapse(ORDERS), collapse(r.getDdl()));
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
        // 真正的幂等判据：改写产物逐字相同。只数 SHARDKEY 条数或只看 notes，
        // 挡不住"二次改写把 uid 再塞一遍主键列"这种破坏（PRIMARY KEY (`id`, `uid`, `uid`)）。
        assertEquals("二次改写的 DDL 必须与第一次完全一致",
                collapse(first.getDdl()), collapse(second.getDdl()));
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

    /**
     * 补 NOT NULL 时必须连同 {@code DEFAULT NULL} 一起摘掉。
     *
     * MySQL 不接受 {@code NOT NULL DEFAULT NULL}：实测 8.0.46 在默认严格模式和 {@code sql_mode=''}
     * 下都报 ERROR 1067 Invalid default value。旧实现只插 NOT NULL，产物直接建不出表；
     * 而老断言写的是 {@code contains("`uid` bigint(20) NOT NULL")}，残缺产物照样绿，所以这里逐字钉列定义。
     */
    @Test
    public void nullableShardKeyMustDropDefaultNullSoTheDdlStillBuilds() {
        TdsqlDdlRewriter.Result r =
                TdsqlDdlRewriter.rewrite(ORDERS, TdsqlTableType.SHARD, "uid");

        assertTrue(r.getDdl(), r.isSuccess());
        String ddl = collapse(r.getDdl());
        assertTrue("分片键列定义必须是 `uid` bigint(20) NOT NULL：" + ddl,
                ddl.contains("`uid` bigint(20) NOT NULL,"));
        assertFalse("产物里不得出现自相矛盾的 NOT NULL DEFAULT NULL：" + ddl,
                ddl.contains("NOT NULL DEFAULT NULL"));
        // 去掉默认值是语义变更，必须写进 notes 让操作者知道
        assertHasNote(r, "DEFAULT NULL");
    }

    /** 注释里写着 NOT NULL 不等于列已经非空：漏补会让分片键带着 NULL 进主键，MySQL 报 ERROR 1171 */
    @Test
    public void commentMentioningNotNullMustNotSuppressTheFix() {
        TdsqlDdlRewriter.Result r =
                TdsqlDdlRewriter.rewrite(COMMENT_TRAP, TdsqlTableType.SHARD, "shard_k");

        assertTrue(r.getDdl(), r.isSuccess());
        String ddl = collapse(r.getDdl());
        assertTrue("COMMENT 里的字样不该被当成列属性：" + ddl,
                ddl.contains("`shard_k` bigint(20) NOT NULL"));
        assertFalse(ddl, ddl.contains("NOT NULL DEFAULT NULL"));
    }

    /** 真实默认值要保住：只有 DEFAULT NULL 才随补非空一起删除 */
    @Test
    public void explicitDefaultMustSurviveTheNotNullFix() {
        TdsqlDdlRewriter.Result r =
                TdsqlDdlRewriter.rewrite(KEEP_DEFAULT, TdsqlTableType.SHARD, "shard_k");

        assertTrue(r.getDdl(), r.isSuccess());
        String ddl = collapse(r.getDdl());
        assertTrue(ddl, ddl.contains("`shard_k` int(11) NOT NULL DEFAULT '7'"));
        for (String note : r.getNotes()) {
            assertFalse("这一列本来就没有 DEFAULT NULL，不该报去掉它：" + note,
                    note.contains("DEFAULT NULL"));
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
