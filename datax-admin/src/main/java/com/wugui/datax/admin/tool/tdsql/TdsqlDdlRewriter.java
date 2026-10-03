package com.wugui.datax.admin.tool.tdsql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把一张普通 MySQL 单库表的 CREATE TABLE 改写成 TDSQL 分布式表的 DDL。
 *
 * 依据的是 TDSQL MySQL 版两条硬约束（检索级事实，未经实例验证）：
 * 1) 分片键必须出现在主键和**所有**唯一索引里；
 * 2) 分片键列不可为 NULL。
 * 这两条正是"老表迁 TDSQL 必须改表结构"的根因，所以改写产物是 DDL 文件而不是建议清单。
 */
public final class TdsqlDdlRewriter {

    /** 广播表在 TDSQL 里的固定写法 */
    public static final String BROADCAST_SHARDKEY = "noshardkey_allset";

    private TdsqlDdlRewriter() {
    }

    public static class Result {
        private final boolean success;
        private final String ddl;
        private final List<String> notes;

        Result(boolean success, String ddl, List<String> notes) {
            this.success = success;
            this.ddl = ddl;
            this.notes = notes;
        }

        public boolean isSuccess() {
            return success;
        }

        /** 改写后的 DDL；失败时为原文，便于人工接手 */
        public String getDdl() {
            return ddl;
        }

        /** 每一处结构性改动都留一条说明，改造结果必须可审计 */
        public List<String> getNotes() {
            return notes;
        }
    }

    /**
     * @param createDdl 单表 CREATE TABLE 原文（一张表一条）
     * @param tableType 目标表类型
     * @param shardKey  分片键列名；SHARD 类型必填，其余类型忽略
     */
    public static Result rewrite(String createDdl, TdsqlTableType tableType, String shardKey) {
        List<String> notes = new ArrayList<>();
        if (createDdl == null || createDdl.trim().isEmpty()) {
            return new Result(false, createDdl, singleton("DDL 为空，无法改写"));
        }
        if (tableType == null) {
            return new Result(false, createDdl, singleton("未指定目标表类型"));
        }

        // 入口先验语句类型：这个工具只会改写"单条 CREATE TABLE"，
        // 喂 ALTER TABLE / CREATE INDEX / 多语句脚本时必须明确报错，不能"顺手改一点算一点"。
        String entryProblem = entryProblem(createDdl);
        if (entryProblem != null) {
            return new Result(false, createDdl, singleton(entryProblem));
        }

        int open = indexOfSignificant(createDdl, 0);
        int close = matchingParen(createDdl, open);
        if (open < 0 || close < 0) {
            return new Result(false, createDdl, singleton("没找到 CREATE TABLE 的列表括号，无法安全改写"));
        }

        List<String> items = splitTopLevel(createDdl.substring(open + 1, close));

        if (tableType == TdsqlTableType.SINGLE) {
            ClauseEdit edit = dropClause(createDdl);
            if (edit.problem != null) {
                return new Result(false, createDdl, singleton(edit.problem));
            }
            notes.add(edit.note);
            return new Result(true, edit.ddl, notes);
        }

        if (tableType == TdsqlTableType.BROADCAST) {
            ClauseEdit edit = setClause(createDdl, BROADCAST_SHARDKEY);
            if (edit.problem != null) {
                return new Result(false, createDdl, singleton(edit.problem));
            }
            notes.add("广播表：SHARDKEY = " + BROADCAST_SHARDKEY + "，每个分片各存一份全量，主键/唯一索引不需补列");
            notes.add(edit.note);
            return new Result(true, edit.ddl, notes);
        }

        // ---- 分片表 ----
        String key = trimIdentifier(shardKey);
        if (key == null || key.isEmpty()) {
            return new Result(false, createDdl, singleton("分片表必须指定分片键列"));
        }

        int keyItem = -1;
        for (int i = 0; i < items.size(); i++) {
            if (isColumnDef(items.get(i)) && sameName(columnName(items.get(i)), key)) {
                keyItem = i;
                break;
            }
        }
        if (keyItem < 0) {
            return new Result(false, createDdl,
                    singleton("分片键列 `" + key + "` 在表里不存在，请人工确认分片键后再改"));
        }

        // 分片键不可为 NULL
        String keyDef = items.get(keyItem);
        if (indexOfTokenOutsideQuotes(keyDef, "NOT NULL") < 0) {
            items.set(keyItem, addNotNull(keyDef));
            notes.add("分片键 `" + key + "` 原先可为空，已补 NOT NULL（TDSQL 要求分片键非空）");
            if (hasDefaultNull(keyDef)) {
                notes.add("分片键原先带 DEFAULT NULL，补非空时已一并去掉该默认值 —— "
                        + "此后「不填这一列」从写入 NULL 变成报错，且历史 NULL 值必须先在库里补成非空值，"
                        + "否则 ALTER 会因既有可能的 NULL 行而失败");
            }
        }

        // 主键与所有唯一索引都要含分片键
        for (int i = 0; i < items.size(); i++) {
            String item = items.get(i);
            if (isPrimaryKey(item)) {
                String rewritten = addColumnToIndex(item, key);
                if (rewritten != null) {
                    items.set(i, rewritten);
                    notes.add("主键原先不含分片键，已将 `" + key + "` 加入主键列 —— 这会改变主键语义，需业务确认");
                }
            } else if (isUniqueKey(item)) {
                String rewritten = addColumnToIndex(item, key);
                if (rewritten != null) {
                    notes.add("唯一索引原先不含分片键，已补 `" + key
                            + "` 列 —— 去重范围随之从全表缩小到单分片，必须人工复核");
                    items.set(i, rewritten);
                }
            }
        }

        String autoCol = firstAutoIncrementColumn(items);
        if (autoCol != null && hasPrimaryKey(items)) {
            notes.add("自增主键 `" + autoCol + "`：分片下 AUTO_INCREMENT 不保证全局有序，建议改用 "
                    + "CREATE TDSQL_SEQUENCE 供号（本工具只改 DDL，不改应用侧 INSERT，属下一批次范围）");
        }

        String body = join(items);
        String ddl = createDdl.substring(0, open + 1) + body + createDdl.substring(close);
        ClauseEdit edit = setClause(ddl, key);
        if (edit.problem != null) {
            return new Result(false, createDdl, singleton(edit.problem));
        }
        notes.add(edit.note);
        return new Result(true, edit.ddl, notes);
    }

    // ---------------- 内部工具 ----------------

    private static List<String> singleton(String s) {
        List<String> l = new ArrayList<>();
        l.add(s);
        return l;
    }

    /** 从 from 起表体列表括号 '(' 的下标：注释、字符串里的括号不算 */
    private static int indexOfSignificant(String sql, int from) {
        for (int i = from; i < sql.length(); i++) {
            int j = skipIgnorable(sql, i);
            if (j < 0) {
                return -1;
            }
            if (j != i) {
                i = j;
            } else if (sql.charAt(i) == '(') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从 from 起第一个有内容（非空白、非注释）的下标；没有则 -1。
     * 这里**只**跳空白与注释：反引号里的表名、列名就是内容，跳过去等于"这一列不存在"。
     */
    private static int firstContent(String sql, int from) {
        for (int i = from; i < sql.length(); i++) {
            int j = skipComment(sql, i);
            if (j < 0 || j == i) {
                if (!Character.isWhitespace(sql.charAt(i))) {
                    return i;
                }
            } else {
                i = j;
            }
        }
        return -1;
    }

    /**
     * i 处若是一段注释（块注释、{@code -- } 行注释、{@code #} 行注释）的起点，
     * 返回这段内容的最后一个下标；不是起点则原样返回 i；未闭合的块注释一路吃到串尾。
     */
    private static int skipComment(String sql, int i) {
        char c = sql.charAt(i);
        if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
            int end = sql.indexOf("*/", i + 2);
            return end < 0 ? sql.length() - 1 : end + 1;
        }
        if (c == '#' || (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-'
                && (i + 2 >= sql.length() || Character.isWhitespace(sql.charAt(i + 2))))) {
            int nl = sql.indexOf('\n', i);
            return nl < 0 ? sql.length() - 1 : nl - 1;
        }
        return i;
    }

    /**
     * i 处若是一段注释或字符串/反引号字面量的起点，返回这段内容的最后一个下标；
     * 不是起点则原样返回 i；引号未闭合返回 -1。
     *
     * 括号配对、顶层逗号切分、关键字定位都要先过这一步：注释里写的 {@code DEFAULT NULL}、
     * {@code SHARDKEY = xxx} 都会被当成真的表选项，产物就成了半句真话。
     */
    private static int skipIgnorable(String sql, int i) {
        int comment = skipComment(sql, i);
        if (comment != i) {
            return comment;
        }
        char c = sql.charAt(i);
        if (c == '`' || c == '\'' || c == '"') {
            return skipQuoted(sql, i, c);
        }
        return i;
    }

    /** 从 openPos 处的 '(' 找配对的 ')'；跳过注释与字符串字面量里的括号 */
    private static int matchingParen(String sql, int openPos) {
        if (openPos < 0) {
            return -1;
        }
        int depth = 0;
        for (int i = openPos; i < sql.length(); i++) {
            char c = sql.charAt(i);
            int j = skipIgnorable(sql, i);
            if (j < 0) {
                return -1;
            }
            if (j != i) {
                i = j;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int skipQuoted(String sql, int start, char quote) {
        for (int i = start + 1; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\\' && quote != '`') {
                i++;
                continue;
            }
            if (c == quote) {
                // 双写引号（'' / ""）是转义，不算结束
                if (quote != '`' && i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i++;
                    continue;
                }
                return i;
            }
        }
        return -1;
    }

    /** 按顶层逗号切分表体：括号内的逗号（varchar(20) / decimal(10,2)）与注释里的逗号都不切 */
    private static List<String> splitTopLevel(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            int j = skipIgnorable(body, i);
            if (j < 0) {
                cur.append(body.substring(i));
                break;
            }
            if (j != i) {
                cur.append(body, i, j + 1);
                i = j;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /**
     * 第一个位于注释/引号之外的 ';' 下标；没有则 -1。
     *
     * 分号之后还有内容就说明这是多语句脚本。上一版只改最后一条语句就把整份脚本还回去，
     * 操作者以为整份都改完了 —— 宁可拒绝，也不给半成品。
     */
    private static int firstStatementSeparator(String sql) {
        for (int i = 0; i < sql.length(); i++) {
            int j = skipIgnorable(sql, i);
            if (j < 0) {
                return -1;
            }
            if (j != i) {
                i = j;
            } else if (sql.charAt(i) == ';') {
                return i;
            }
        }
        return -1;
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            sb.append(items.get(i).trim());
            if (i < items.size() - 1) {
                sb.append(",\n  ");
            }
        }
        return "\n  " + sb;
    }

    private static boolean isColumnDef(String item) {
        String t = stripLeadingComments(item);
        return !t.isEmpty() && !isIndexDef(t) && !isConstraintDef(t);
    }

    /**
     * 去掉片段开头的空白与注释：块注释打头的 "PRIMARY KEY (id)" 这一项仍然是主键定义，
     * 但按字面 trim 后首个词是注释内容，就会被误判成列定义。
     */
    private static String stripLeadingComments(String item) {
        String t = item.trim();
        int i = firstContent(t, 0);
        return i <= 0 ? t : t.substring(i);
    }

    private static boolean isIndexDef(String t) {
        String u = toUpperCase(t);
        return startsWithKeyword(u, "PRIMARY KEY") || startsWithKeyword(u, "UNIQUE KEY")
                || startsWithKeyword(u, "UNIQUE INDEX") || startsWithKeyword(u, "KEY")
                || startsWithKeyword(u, "INDEX") || startsWithKeyword(u, "FULLTEXT");
    }

    private static boolean isConstraintDef(String t) {
        String u = toUpperCase(t);
        return startsWithKeyword(u, "CONSTRAINT") || startsWithKeyword(u, "FOREIGN KEY")
                || startsWithKeyword(u, "CHECK");
    }

    /**
     * 关键字必须是独立 token：不带反引号的列名 key_id / check_time 以 KEY、CHECK 的字母开头，
     * 直接用 startsWith 会把列定义误判成索引/约束定义，分片键就会"在表里不存在"。
     */
    private static boolean startsWithKeyword(String upper, String keyword) {
        if (!upper.startsWith(keyword)) {
            return false;
        }
        int next = keyword.length();
        return next >= upper.length() || !isWordChar(upper.charAt(next));
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isPrimaryKey(String item) {
        return startsWithKeyword(toUpperCase(stripLeadingComments(item)), "PRIMARY KEY");
    }

    /**
     * 唯一索引的三种写法都要认出来：
     *   UNIQUE KEY `x` (...) / UNIQUE INDEX ... / UNIQUE (...)
     *   CONSTRAINT `x` UNIQUE (...)   ← mysqldump 5.7+ 对 InnoDB 唯一约束就产出这个形态
     * 漏认最后一种 = 分片键没进唯一索引 = 生成的 DDL 在 TDSQL 上直接建表失败。
     */
    private static boolean isUniqueKey(String item) {
        String u = toUpperCase(stripLeadingComments(item));
        return startsWithKeyword(u, "UNIQUE KEY") || startsWithKeyword(u, "UNIQUE INDEX")
                || startsWithKeyword(u, "UNIQUE")
                || (startsWithKeyword(u, "CONSTRAINT") && CONSTRAINT_HAS_UNIQUE.matcher(u).find());
    }

    private static final java.util.regex.Pattern CONSTRAINT_HAS_UNIQUE =
            java.util.regex.Pattern.compile("\\bUNIQUE\\b");

    /** `uid` bigint(20) NOT NULL -> uid */
    private static String columnName(String item) {
        String t = stripLeadingComments(item);
        if (t.startsWith("`")) {
            int end = t.indexOf('`', 1);
            return end > 0 ? t.substring(1, end) : null;
        }
        int sp = indexOfAny(t, " \t\n\r(");
        return sp > 0 ? t.substring(0, sp) : t;
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 往主键/唯一索引的列清单里加分片键。
     *
     * @return 已含分片键时返回 null（表示无需改动），否则返回改写后的片段
     */
    private static String addColumnToIndex(String item, String key) {
        int open = indexOfSignificant(item, 0);
        int close = matchingParen(item, open);
        if (open < 0 || close < 0) {
            return null;
        }
        List<String> cols = splitTopLevel(item.substring(open + 1, close));
        for (String col : cols) {
            if (sameName(trimIdentifier(columnName(col)), key)) {
                return null;
            }
        }
        StringBuilder sb = new StringBuilder(item.substring(0, open + 1));
        for (int i = 0; i < cols.size(); i++) {
            sb.append(cols.get(i).trim());
            sb.append(", ");
        }
        sb.append('`').append(key).append('`').append(item.substring(close));
        return sb.toString();
    }

    /**
     * 分片键列补 NOT NULL：放在 AUTO_INCREMENT / DEFAULT / COMMENT 这些后续属性之前，
     * 并去掉与之冲突的 {@code DEFAULT NULL}。
     *
     * 必须一起去掉默认值：MySQL 不接受 {@code NOT NULL DEFAULT NULL}，实测 8.0.46 在默认严格模式
     * 与 {@code sql_mode=''} 下都报 ERROR 1067 Invalid default value —— 而 mysqldump 对可空列的标准
     * 产出恰好就是 {@code DEFAULT NULL}，所以只插 NOT NULL 会让改写产物直接建不出表。
     */
    private static String addNotNull(String keyDef) {
        String t = stripDefaultNull(keyDef.trim());
        int insertAt = t.length();
        int auto = indexOfTokenOutsideQuotes(t, "AUTO_INCREMENT");
        int def = indexOfTokenOutsideQuotes(t, "DEFAULT");
        int comment = indexOfTokenOutsideQuotes(t, "COMMENT");
        if (auto >= 0 && auto < insertAt) {
            insertAt = auto;
        }
        if (def >= 0 && def < insertAt) {
            insertAt = def;
        }
        if (comment >= 0 && comment < insertAt) {
            insertAt = comment;
        }
        return t.substring(0, insertAt).trim() + " NOT NULL " + t.substring(insertAt).trim();
    }

    /** 列定义里是否写着 {@code DEFAULT NULL}（引号内的字样不算） */
    private static boolean hasDefaultNull(String keyDef) {
        return indexOfDefaultNull(keyDef.trim()) >= 0;
    }

    /** 返回 {@code DEFAULT NULL} 这一对的起始下标，没有则 -1 */
    private static int indexOfDefaultNull(String t) {
        int def = indexOfTokenOutsideQuotes(t, "DEFAULT");
        if (def < 0) {
            return -1;
        }
        int i = def + "DEFAULT".length();
        while (i < t.length() && Character.isWhitespace(t.charAt(i))) {
            i++;
        }
        if (i + 4 > t.length() || !"NULL".equals(toUpperCase(t).substring(i, i + 4))) {
            return -1;
        }
        if (i + 4 < t.length() && isNameChar(t.charAt(i + 4))) {
            return -1;
        }
        return def;
    }

    private static String stripDefaultNull(String t) {
        int def = indexOfDefaultNull(t);
        if (def < 0) {
            return t;
        }
        int i = def + "DEFAULT".length();
        while (i < t.length() && Character.isWhitespace(t.charAt(i))) {
            i++;
        }
        return (t.substring(0, def) + t.substring(i + 4)).trim();
    }

    /** 在注释与引号区之外找关键字 token；COMMENT 'NOT NULL 约束' 这类字样不得当成列属性 */
    private static int indexOfTokenOutsideQuotes(String raw, String token) {
        String upper = toUpperCase(raw);
        int from = 0;
        while (true) {
            int idx = upper.indexOf(token, from);
            if (idx < 0) {
                return -1;
            }
            boolean leftOk = idx == 0 || !isNameChar(upper.charAt(idx - 1));
            int right = idx + token.length();
            boolean rightOk = right >= upper.length() || !isNameChar(upper.charAt(right));
            if (leftOk && rightOk && !inIgnorableRegion(raw, idx)) {
                return idx;
            }
            from = idx + 1;
        }
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '`';
    }

    /** 表里第一个带 AUTO_INCREMENT 的列名；没有则返回 null */
    private static String firstAutoIncrementColumn(List<String> items) {
        for (String item : items) {
            if (isColumnDef(item) && indexOfTokenOutsideQuotes(item, "AUTO_INCREMENT") >= 0) {
                return columnName(item);
            }
        }
        return null;
    }

    private static boolean hasPrimaryKey(List<String> items) {
        for (String item : items) {
            if (isPrimaryKey(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一次 SHARDKEY 子句改写的结果：改写后的 DDL 与说明，或者明确的失败原因。
     */
    private static final class ClauseEdit {
        private final String ddl;
        private final String note;
        private final String problem;

        private ClauseEdit(String ddl, String note, String problem) {
            this.ddl = ddl;
            this.note = note;
            this.problem = problem;
        }

        private static ClauseEdit ok(String ddl, String note) {
            return new ClauseEdit(ddl, note, null);
        }

        private static ClauseEdit bad(String problem) {
            return new ClauseEdit(null, null, problem);
        }
    }

    /**
     * 把子句设成目标形态：没有就追加，已有但值不同就**整段替换**。
     *
     * "只追加不替换"是上一版的缺陷：分片表转广播表时旧子句 {@code SHARDKEY = `uid`} 会原样留着，
     * 说明写着广播表、DDL 却仍然是分片表。值已相同则逐字保留，这是幂等的前提。
     */
    private static ClauseEdit setClause(String ddl, String key) {
        String clause = BROADCAST_SHARDKEY.equals(key)
                ? "SHARDKEY = " + key
                : "SHARDKEY = `" + key + "`";
        int idx = indexOfShardKeyKeyword(ddl);
        if (idx < 0) {
            return ClauseEdit.ok(insertClause(ddl, clause), "已在表选项末尾追加 " + clause);
        }
        int end = clauseValueEnd(ddl, idx);
        if (end < 0) {
            return ClauseEdit.bad(unknownClause(ddl, idx));
        }
        String existing = collapseSpace(ddl.substring(idx, end));
        if (existing.equals(collapseSpace(clause))) {
            return ClauseEdit.ok(ddl, "已带 " + clause + " 子句，保持原样");
        }
        return ClauseEdit.ok(ddl.substring(0, idx) + clause + ddl.substring(end),
                "原先已有 " + existing + " 子句，已整段替换为 " + clause
                        + " —— 表类型换了子句必须跟着换，留着旧子句等于换了个说法还是老表");
    }

    /** 单表：TDSQL 里不分布，已有的 SHARDKEY 子句必须去掉，否则产物还是分片表 */
    private static ClauseEdit dropClause(String ddl) {
        int idx = indexOfShardKeyKeyword(ddl);
        if (idx < 0) {
            return ClauseEdit.ok(ddl, "单表：不写 SHARDKEY 子句，DDL 原样保留");
        }
        int end = clauseValueEnd(ddl, idx);
        if (end < 0) {
            return ClauseEdit.bad(unknownClause(ddl, idx));
        }
        int from = idx;
        while (from > 0 && Character.isWhitespace(ddl.charAt(from - 1))) {
            from--;
        }
        return ClauseEdit.ok(ddl.substring(0, from) + ddl.substring(end),
                "单表：已去掉原先的 " + collapseSpace(ddl.substring(idx, end)) + " 子句");
    }

    /** 在语句结尾（分号之前）插入子句；没有分号就接在末尾。广播表的关键字不带反引号 */
    private static String insertClause(String ddl, String clause) {
        int sep = firstStatementSeparator(ddl);
        if (sep < 0) {
            return ddl + " " + clause;
        }
        int at = sep;
        while (at > 0 && Character.isWhitespace(ddl.charAt(at - 1))) {
            at--;
        }
        return ddl.substring(0, at) + " " + clause + ddl.substring(sep);
    }

    /** 认不出的子句形态一律报错：猜一个值替换上去就是把别人的分片键改掉 */
    private static String unknownClause(String ddl, int idx) {
        int stop = Math.min(ddl.length(), idx + 60);
        return "已存在的 SHARDKEY 子句形态认不出来（" + collapseSpace(ddl.substring(idx, stop))
                + "）：复合分片键或多列写法请人工改写，工具不做猜测";
    }

    /**
     * SHARDKEY 关键字在注释/引号之外的下标；没有则 -1。
     * 列名叫 `shardkey_time` 不算子句，边界字符与引号区都要判。
     */
    private static int indexOfShardKeyKeyword(String ddl) {
        return indexOfKeywordOutsideIgnorable(ddl, "SHARDKEY");
    }

    /**
     * 从 SHARDKEY 关键字起解析整个子句，返回结束下标（不含）。
     * 认得出 {@code SHARDKEY = `uid`} / {@code shardkey='uid'} / {@code SHARDKEY=noshardkey_allset}；
     * 带括号的复合分片键返回 -1，让调用方明确报错。
     */
    private static int clauseValueEnd(String ddl, int keywordIdx) {
        int i = firstContent(ddl, keywordIdx + "SHARDKEY".length());
        if (i < 0) {
            return -1;
        }
        if (ddl.charAt(i) == '=') {
            i = firstContent(ddl, i + 1);
            if (i < 0) {
                return -1;
            }
        }
        char c = ddl.charAt(i);
        if (c == '(') {
            return -1;
        }
        if (c == '`' || c == '\'' || c == '"') {
            int end = skipQuoted(ddl, i, c);
            return end < 0 ? -1 : end + 1;
        }
        int j = i;
        while (j < ddl.length() && isWordChar(ddl.charAt(j))) {
            j++;
        }
        return j == i ? -1 : j;
    }

    private static String collapseSpace(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    /**
     * 入口语句类型校验：这个工具只改写"单条 CREATE TABLE"。
     * 返回 null 表示可以改写，否则返回给操作者的失败原因。
     */
    private static String entryProblem(String ddl) {
        int head = firstContent(ddl, 0);
        if (head < 0) {
            return "这份输入里没有有效内容，无法改写";
        }
        if (!startsWithKeywordAt(ddl, head, "CREATE")) {
            return "只支持改写 CREATE TABLE，这份输入以 " + wordAt(ddl, head) + " 开头，表结构请人工改写";
        }
        int afterCreate = firstContent(ddl, head + "CREATE".length());
        if (startsWithKeywordAt(ddl, afterCreate, "TEMPORARY")) {
            afterCreate = firstContent(ddl, afterCreate + "TEMPORARY".length());
        }
        if (!startsWithKeywordAt(ddl, afterCreate, "TABLE")) {
            return "只支持改写 CREATE TABLE，这份输入的第二个关键字是 " + wordAt(ddl, afterCreate);
        }

        // 表名（可能带库名、IF NOT EXISTS）这一段里出现 LIKE 的是整表复制，列结构不在这份 DDL 里
        int i = firstContent(ddl, afterCreate + "TABLE".length());
        while (i >= 0) {
            char c = ddl.charAt(i);
            if (c == '(') {
                break;
            }
            if (c == ';') {
                return "这条 CREATE TABLE 没有列清单，没有可改写的表体";
            }
            if (startsWithKeywordAt(ddl, i, "LIKE")) {
                return "CREATE TABLE ... LIKE 是整表复制，列结构不在这份 DDL 里，工具改写不了";
            }
            int next = firstContent(ddl, wordEnd(ddl, i));
            if (next <= i) {
                break;
            }
            i = next;
        }

        int sep = firstStatementSeparator(ddl);
        if (sep >= 0 && firstContent(ddl, sep + 1) >= 0) {
            return "这是一份多语句脚本（第一条分号之后还有内容）：本工具一次只改写一条 CREATE TABLE，请拆开后再喂";
        }
        if (indexOfKeywordOutsideIgnorable(ddl, "SELECT") >= 0) {
            return "这是 CREATE TABLE ... SELECT（表体来自查询结果），列结构不在这份 DDL 里，工具改写不了";
        }
        return null;
    }

    /** 关键字必须从 idx 起独立成 token（大小写不敏感），`shardkey_time` 这种名字不算命中 */
    private static int indexOfKeywordOutsideIgnorable(String sql, String keyword) {
        String upper = toUpperCase(sql);
        int from = 0;
        while (true) {
            int idx = upper.indexOf(keyword, from);
            if (idx < 0) {
                return -1;
            }
            boolean leftOk = idx == 0 || !isWordChar(sql.charAt(idx - 1));
            int right = idx + keyword.length();
            boolean rightOk = right >= sql.length() || !isWordChar(sql.charAt(right));
            if (leftOk && rightOk && !inIgnorableRegion(sql, idx)) {
                return idx;
            }
            from = idx + 1;
        }
    }

    private static boolean startsWithKeywordAt(String sql, int idx, String keyword) {
        if (idx < 0 || idx + keyword.length() > sql.length()) {
            return false;
        }
        if (!toUpperCase(sql.substring(idx, idx + keyword.length())).equals(keyword)) {
            return false;
        }
        int right = idx + keyword.length();
        return right >= sql.length() || !isWordChar(sql.charAt(right));
    }

    /** 从 i 起这一个 token 的结束下标（不含）；反引号名整体算一个 token，保证调用方能前进 */
    private static int wordEnd(String sql, int i) {
        char c = sql.charAt(i);
        if (c == '`' || c == '\'' || c == '"') {
            int end = skipQuoted(sql, i, c);
            return end < 0 ? sql.length() : end + 1;
        }
        int j = i;
        while (j < sql.length() && (isWordChar(sql.charAt(j)) || sql.charAt(j) == '.')) {
            j++;
        }
        return j == i ? i + 1 : j;
    }

    /** 取 i 起的第一个词，只用于拼错误信息 */
    private static String wordAt(String sql, int i) {
        if (i < 0) {
            return "(结尾)";
        }
        String w = collapseSpace(sql.substring(i, Math.min(wordEnd(sql, i), sql.length())));
        return w.isEmpty() ? String.valueOf(sql.charAt(i)) : w;
    }

    /**
     * 该下标是否落在"不算代码"的区域里：反引号/引号包裹的标识符，或注释。
     * 注释里的 {@code NOT NULL}、{@code DEFAULT NULL} 都会被属性判定误认，所以注释区必须一起挡掉。
     */
    private static boolean inIgnorableRegion(String ddl, int pos) {
        int i = 0;
        while (i < pos) {
            int j = skipIgnorable(ddl, i);
            if (j < 0) {
                return false;
            }
            if (j == i) {
                i++;
                continue;
            }
            if (pos <= j) {
                return true;
            }
            i = j + 1;
        }
        return false;
    }

    private static String trimIdentifier(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("`") && t.endsWith("`")) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static boolean sameName(String a, String b) {
        return a != null && b != null && toUpperCase(a).equals(toUpperCase(b));
    }

    private static String toUpperCase(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }
}
