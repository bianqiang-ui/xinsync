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

        int open = createDdl.indexOf('(');
        int close = matchingParen(createDdl, open);
        if (open < 0 || close < 0) {
            return new Result(false, createDdl, singleton("没找到 CREATE TABLE 的列表括号，无法安全改写"));
        }

        List<String> items = splitTopLevel(createDdl.substring(open + 1, close));

        if (tableType == TdsqlTableType.SINGLE) {
            String ddl = appendClause(createDdl, close, null);
            notes.add("单表：不加 SHARDKEY 子句，表结构保持原样");
            return new Result(true, ddl, notes);
        }

        if (tableType == TdsqlTableType.BROADCAST) {
            boolean alreadyKeyed = hasShardKeyClause(createDdl);
            String ddl = appendClause(createDdl, close, BROADCAST_SHARDKEY);
            notes.add(alreadyKeyed
                    ? "广播表：DDL 里已有 SHARDKEY 子句，未重复追加"
                    : "广播表：SHARDKEY = " + BROADCAST_SHARDKEY + "，每个分片各存一份全量，主键/唯一索引不需补列");
            return new Result(true, ddl, notes);
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
        boolean alreadyKeyed = hasShardKeyClause(ddl);
        ddl = appendClause(ddl, matchingParen(ddl, ddl.indexOf('(')), key);
        notes.add(alreadyKeyed
                ? "DDL 里已有 SHARDKEY 子句，未重复追加（重复子句会让建表语句不合法）"
                : "已追加 SHARDKEY = " + key + " 子句（子句在真实例上的确切位置待实测校准）");
        return new Result(true, ddl, notes);
    }

    // ---------------- 内部工具 ----------------

    private static List<String> singleton(String s) {
        List<String> l = new ArrayList<>();
        l.add(s);
        return l;
    }

    /** 从 openPos 处的 '(' 找配对的 ')'；跳过字符串字面量与反引号内的括号 */
    private static int matchingParen(String sql, int openPos) {
        if (openPos < 0) {
            return -1;
        }
        int depth = 0;
        for (int i = openPos; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '`' || c == '\'' || c == '"') {
                i = skipQuoted(sql, i, c);
                if (i < 0) {
                    return -1;
                }
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

    /** 按顶层逗号切分表体（varchar(20) 这类括号内的逗号不参与切分） */
    private static List<String> splitTopLevel(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '`' || c == '\'' || c == '"') {
                int end = skipQuoted(body, i, c);
                if (end < 0) {
                    cur.append(body.substring(i));
                    i = body.length();
                    break;
                }
                cur.append(body, i, end + 1);
                i = end;
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
        String t = item.trim();
        return !t.isEmpty() && !isIndexDef(t) && !isConstraintDef(t);
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
        return startsWithKeyword(toUpperCase(item.trim()), "PRIMARY KEY");
    }

    /**
     * 唯一索引的三种写法都要认出来：
     *   UNIQUE KEY `x` (...) / UNIQUE INDEX ... / UNIQUE (...)
     *   CONSTRAINT `x` UNIQUE (...)   ← mysqldump 5.7+ 对 InnoDB 唯一约束就产出这个形态
     * 漏认最后一种 = 分片键没进唯一索引 = 生成的 DDL 在 TDSQL 上直接建表失败。
     */
    private static boolean isUniqueKey(String item) {
        String u = toUpperCase(item.trim());
        return startsWithKeyword(u, "UNIQUE KEY") || startsWithKeyword(u, "UNIQUE INDEX")
                || startsWithKeyword(u, "UNIQUE")
                || (startsWithKeyword(u, "CONSTRAINT") && CONSTRAINT_HAS_UNIQUE.matcher(u).find());
    }

    private static final java.util.regex.Pattern CONSTRAINT_HAS_UNIQUE =
            java.util.regex.Pattern.compile("\\bUNIQUE\\b");

    /** `uid` bigint(20) NOT NULL -> uid */
    private static String columnName(String item) {
        String t = item.trim();
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
        int open = item.indexOf('(');
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

    /** 在引号区之外找关键字 token；COMMENT 'NOT NULL 约束' 这类字样不得当成列属性 */
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
            if (leftOk && rightOk && !inQuotedRegion(raw, idx)) {
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
            if (isColumnDef(item) && toUpperCase(item).contains("AUTO_INCREMENT")) {
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

    /** 在表选项末尾（分号前）追加 SHARDKEY 子句；广播表的 noshardkey_allset 是关键字，不能加反引号 */
    private static String appendClause(String ddl, int closeParen, String key) {
        if (key == null) {
            return ddl;
        }
        if (hasShardKeyClause(ddl)) {
            // 幂等：二次改写若再追加一条 SHARDKEY，产物就是语法不合法的 DDL
            return ddl;
        }
        String clause = BROADCAST_SHARDKEY.equals(key)
                ? " SHARDKEY = " + key
                : " SHARDKEY = `" + key + "`";
        int semi = ddl.lastIndexOf(';');
        if (semi > closeParen) {
            return ddl.substring(0, semi) + clause + ddl.substring(semi);
        }
        return ddl + clause;
    }

    /**
     * 是否已经带 SHARDKEY 子句。只在非引号区里找独立 token，
     * 免得某个列/约束恰好叫 `shardkey_time` 就被误判成"已分片"。
     */
    private static boolean hasShardKeyClause(String ddl) {
        if (ddl == null) {
            return false;
        }
        String u = toUpperCase(ddl);
        int from = 0;
        while (true) {
            int idx = u.indexOf("SHARDKEY", from);
            if (idx < 0) {
                return false;
            }
            if (inQuotedRegion(ddl, idx)) {
                from = idx + 1;
                continue;
            }
            boolean leftOk = idx == 0 || !isWordChar(ddl.charAt(idx - 1));
            int right = idx + "SHARDKEY".length();
            boolean rightOk = right >= ddl.length() || !isWordChar(ddl.charAt(right));
            if (leftOk && rightOk) {
                return true;
            }
            from = right;
        }
    }

    /** 该下标是否落在反引号/引号包裹的标识符里 */
    private static boolean inQuotedRegion(String ddl, int pos) {
        int i = 0;
        while (i < pos) {
            char c = ddl.charAt(i);
            if (c == '`' || c == '\'' || c == '"') {
                int end = skipQuoted(ddl, i, c);
                if (end < 0) {
                    return false;
                }
                if (pos > i && pos <= end) {
                    return true;
                }
                i = end + 1;
                continue;
            }
            i++;
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
