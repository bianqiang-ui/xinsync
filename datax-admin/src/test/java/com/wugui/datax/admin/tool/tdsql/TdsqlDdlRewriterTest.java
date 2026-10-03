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

    /**
     * 注释陷阱三件套：块注释里写着 SHARDKEY 子句、行注释里写着没配对的 '('、
     * 行注释里写着 DEFAULT NULL。任意一处被当成代码，产物就是半句真话或直接定位失败。
     */
    private static final String COMMENT_TRAP_CLAUSE =
            "CREATE TABLE `comment_trap_c` (\n"
            + "  `id` bigint(20) NOT NULL AUTO_INCREMENT,\n"
            + "  -- 原设计里这一行写的是 PRIMARY KEY (id\n"
            + "  `uid` bigint(20) DEFAULT NULL,\n"
            + "  PRIMARY KEY (`id`)\n"
            + ") /* SHARDKEY = `ghost` 这一段只是注释，不是表选项 */ ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

    /** 迁移脚本常是整份 mysqldump：多条语句一起喂进来，工具不能只改最后一条 */
    private static final String TWO_TABLES =
            "CREATE TABLE `a` (\n"
            + "  `id` bigint(20) NOT NULL AUTO_INCREMENT,\n"
            + "  `uid` bigint(20) DEFAULT NULL,\n"
            + "  PRIMARY KEY (`id`)\n"
            + ") ENGINE=InnoDB;\n"
            + "CREATE TABLE `b` (\n"
            + "  `id` bigint(20) NOT NULL AUTO_INCREMENT,\n"
            + "  `uid` bigint(20) DEFAULT NULL,\n"
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

    /**
     * 表类型互转必须**换掉子句本身**。
     * 旧实现只在"没有子句"时追加，分片表转广播表会留着 SHARDKEY = `uid`：
     * 说明写着广播表，DDL 还是分片表 —— 建出来的表和你要的不是同一张。
     */
    @Test
    public void shardToBroadcastReplacesTheOldClause() {
        String shard = TdsqlDdlRewriter.rewrite(ORDERS, TdsqlTableType.SHARD, "uid").getDdl();

        TdsqlDdlRewriter.Result r = TdsqlDdlRewriter.rewrite(shard, TdsqlTableType.BROADCAST, null);

        assertTrue(r.getDdl() + " / " + r.getNotes(), r.isSuccess());
        String ddl = collapse(r.getDdl());
        assertTrue(ddl, ddl.contains("SHARDKEY = noshardkey_allset"));
        assertFalse("旧的分片键子句必须被替换掉：" + ddl, ddl.contains("SHARDKEY = `uid`"));
        assertEquals("整份 DDL 只该有一条真正的子句：" + ddl, 1, countOf(ddl, "SHARDKEY"));
        assertHasNote(r, "替换");
    }

    /** 反方向同理：广播表转分片表不能留 noshardkey_allset，否则分片键根本没生效 */
    @Test
    public void broadcastToShardReplacesTheBroadcastClause() {
        String broadcast = TdsqlDdlRewriter.rewrite(ORDERS, TdsqlTableType.BROADCAST, null).getDdl();

        TdsqlDdlRewriter.Result r = TdsqlDdlRewriter.rewrite(broadcast, TdsqlTableType.SHARD, "uid");

        assertTrue(r.getDdl() + " / " + r.getNotes(), r.isSuccess());
        String ddl = collapse(r.getDdl());
        assertTrue(ddl, ddl.contains("SHARDKEY = `uid`"));
        assertFalse(ddl, ddl.contains("noshardkey_allset"));
        assertEquals(ddl, 1, countOf(ddl, "SHARDKEY"));
        assertTrue(ddl, ddl.contains("PRIMARY KEY (`id`, `uid`)"));
    }

    /** 分片表改单表：子句必须删除。留着它，"单表"就还是个分片表 */
    @Test
    public void singleTableDropsTheExistingClause() {
        String shard = TdsqlDdlRewriter.rewrite(ORDERS, TdsqlTableType.SHARD, "uid").getDdl();

        TdsqlDdlRewriter.Result r = TdsqlDdlRewriter.rewrite(shard, TdsqlTableType.SINGLE, null);

        assertTrue(r.getDdl() + " / " + r.getNotes(), r.isSuccess());
        String ddl = collapse(r.getDdl());
        assertFalse("单表不该带 SHARDKEY 子句：" + ddl, ddl.contains("SHARDKEY"));
        assertTrue(ddl, ddl.endsWith(";"));
        assertHasNote(r, "去掉");
    }

    /**
     * 注释里的 SHARDKEY / 括号 / DEFAULT NULL 都不算代码。
     * 这一条同时钉三件事：块注释里的假子句不被当成"已分片"（说明必须是"追加"而不是"保持原样"）、
     * 行注释里未配对的 '(' 不打乱表体定位、注释字样不压掉补非空。
     */
    @Test
    public void commentsAreNeverReadAsTableOptions() {
        TdsqlDdlRewriter.Result r =
                TdsqlDdlRewriter.rewrite(COMMENT_TRAP_CLAUSE, TdsqlTableType.SHARD, "uid");

        assertTrue(r.getDdl() + " / " + r.getNotes(), r.isSuccess());
        String ddl = collapse(r.getDdl());
        assertTrue("注释里的假子句不能被认成已有子句，必须走追加：" + r.getNotes(),
                ddl.contains("SHARDKEY = `uid`") && hasNoteContaining(r, "追加"));
        assertFalse("行注释里未配对的括号会让表体定位失败：" + r.getNotes(),
                hasNoteContaining(r, "没找到 CREATE TABLE"));
        assertTrue(ddl, ddl.contains("`uid` bigint(20) NOT NULL"));
        assertFalse(ddl, ddl.contains("NOT NULL DEFAULT NULL"));
        // 注释本身要原样留在产物里，改写不是清理
        assertTrue(ddl, ddl.contains("这一段只是注释"));

        TdsqlDdlRewriter.Result again =
                TdsqlDdlRewriter.rewrite(r.getDdl(), TdsqlTableType.SHARD, "uid");
        assertTrue(again.getDdl(), again.isSuccess());
        assertEquals("二次改写必须逐字相同：" + collapse(again.getDdl()),
                collapse(r.getDdl()), collapse(again.getDdl()));
    }

    /** 未闭合的块注释：宁可乐观报错，也不能把后半份 DDL 当注释吞掉后产出半句真话 */
    @Test
    public void unterminatedCommentFailsLoudly() {
        String broken = "CREATE TABLE `t` (\n"
                + "  `id` bigint(20) NOT NULL,\n"
                + "  /* 忘了写结尾\n"
                + "  `uid` bigint(20) DEFAULT NULL,\n"
                + "  PRIMARY KEY (`id`)\n"
                + ") ENGINE=InnoDB;";

        TdsqlDdlRewriter.Result r = TdsqlDdlRewriter.rewrite(broken, TdsqlTableType.SHARD, "uid");

        assertFalse("注释未闭合必须报错，产物是：" + r.getDdl(), r.isSuccess());
        assertEquals(broken, r.getDdl());
    }

    /** 多语句脚本必须整份拒绝：只改一条却把整份还回去，操作者会以为全改完了 */
    @Test
    public void multiStatementScriptIsRejected() {
        TdsqlDdlRewriter.Result r =
                TdsqlDdlRewriter.rewrite(TWO_TABLES, TdsqlTableType.SHARD, "uid");

        assertFalse(r.getDdl(), r.isSuccess());
        assertTrue(r.getNotes().toString(), r.getNotes().get(0).contains("多语句"));
        assertEquals(TWO_TABLES, r.getDdl());
    }

    /** 不是 CREATE TABLE 的输入要指名道姓地拒，别"顺手改一点算一点" */
    @Test
    public void nonCreateTableStatementsAreRejected() {
        assertRejectedBecauseLeadingKeyword("ALTER TABLE `orders` ADD COLUMN `uid` bigint(20);", "ALTER");
        // 注释打头也一样要认得出真正的第一个关键字
        assertRejectedBecauseLeadingKeyword("-- 迁移脚本\nALTER TABLE `orders` ADD COLUMN `uid` bigint(20);", "ALTER");
        assertRejectedBecauseLeadingKeyword(
                "CREATE UNIQUE INDEX `uk_uid` ON `orders` (`uid`);", "UNIQUE");
    }

    /** CREATE TABLE ... LIKE / ... SELECT 的列结构不在这份 DDL 里，没有可改写的表体 */
    @Test
    public void createTableWithoutColumnListIsRejected() {
        TdsqlDdlRewriter.Result like = TdsqlDdlRewriter.rewrite(
                "CREATE TABLE `orders_new` LIKE `orders`;", TdsqlTableType.SHARD, "uid");
        assertFalse(like.getDdl(), like.isSuccess());
        assertTrue(like.getNotes().toString(), like.getNotes().get(0).contains("LIKE"));

        TdsqlDdlRewriter.Result select = TdsqlDdlRewriter.rewrite(
                "CREATE TABLE `t` (`uid` bigint(20)) AS SELECT `uid` FROM `orders`;",
                TdsqlTableType.SHARD, "uid");
        assertFalse(select.getDdl(), select.isSuccess());
        assertTrue(select.getNotes().toString(), select.getNotes().get(0).contains("SELECT"));
    }

    /** 已存在的子句写成小写、不带反引号时也要认出来并归一，不能又追加一条 */
    @Test
    public void lowercaseClauseIsNormalizedNotDuplicated() {
        String lowercase = "CREATE TABLE `t` (\n"
                + "  `id` bigint(20) NOT NULL AUTO_INCREMENT,\n"
                + "  `uid` bigint(20) NOT NULL,\n"
                + "  PRIMARY KEY (`id`, `uid`)\n"
                + ") ENGINE=InnoDB shardkey=uid;";

        TdsqlDdlRewriter.Result r = TdsqlDdlRewriter.rewrite(lowercase, TdsqlTableType.SHARD, "uid");

        assertTrue(r.getDdl() + " / " + r.getNotes(), r.isSuccess());
        String ddl = collapse(r.getDdl());
        assertEquals("归一后仍只能有一条子句：" + ddl, 1, countOf(ddl, "SHARDKEY"));
        assertTrue(ddl, ddl.contains("SHARDKEY = `uid`"));
    }

    /** 复合分片键（带括号的写法）本工具不猜：报错还原文，别把别人的分片键换掉 */
    @Test
    public void compositeClauseFailsInsteadOfBeingGuessed() {
        String composite = "CREATE TABLE `t` (\n"
                + "  `id` bigint(20) NOT NULL AUTO_INCREMENT,\n"
                + "  `uid` bigint(20) NOT NULL,\n"
                + "  PRIMARY KEY (`id`, `uid`)\n"
                + ") ENGINE=InnoDB SHARDKEY = (`uid`, `id`);";

        TdsqlDdlRewriter.Result r = TdsqlDdlRewriter.rewrite(composite, TdsqlTableType.SHARD, "uid");

        assertFalse("复合分片键必须明确报错，产物是：" + r.getDdl(), r.isSuccess());
        assertEquals(composite, r.getDdl());
        assertTrue(r.getNotes().toString(), r.getNotes().get(0).contains("认不出来"));
    }

    private static void assertRejectedBecauseLeadingKeyword(String ddl, String keyword) {
        TdsqlDdlRewriter.Result r = TdsqlDdlRewriter.rewrite(ddl, TdsqlTableType.SHARD, "uid");
        assertFalse(keyword + " 不该被改写：" + r.getDdl(), r.isSuccess());
        assertTrue(r.getNotes().toString(), r.getNotes().get(0).contains(keyword));
        assertEquals(ddl, r.getDdl());
    }

    private static boolean hasNoteContaining(TdsqlDdlRewriter.Result r, String keyword) {
        for (String note : r.getNotes()) {
            if (note.contains(keyword)) {
                return true;
            }
        }
        return false;
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
