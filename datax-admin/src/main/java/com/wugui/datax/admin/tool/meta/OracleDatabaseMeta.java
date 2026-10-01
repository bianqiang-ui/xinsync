package com.wugui.datax.admin.tool.meta;
/**
 * Oracle数据库 meta信息查询
 *
 * @author zhouhongfa@gz-yibo.com
 * @ClassName MySQLDatabaseMeta
 * @Version 1.0
 * @since 2019/7/17 15:48
 */
public class OracleDatabaseMeta extends BaseDatabaseMeta implements DatabaseInterface {

    private volatile static OracleDatabaseMeta single;

    /**
     * 下面几条元数据 SQL 只能字符串拼接，先做字符集校验
     */
    private static final String IDENTIFIER_RULE = "^[A-Za-z0-9_$#.]{1,128}$";

    private static String safeIdentifier(String value, String desc) {
        if (value == null || !value.trim().matches(IDENTIFIER_RULE)) {
            throw new IllegalArgumentException("非法的" + desc + "：" + value);
        }
        return value.trim();
    }

    public static OracleDatabaseMeta getInstance() {
        if (single == null) {
            synchronized (OracleDatabaseMeta.class) {
                if (single == null) {
                    single = new OracleDatabaseMeta();
                }
            }
        }
        return single;
    }


    @Override
    public String getSQLQueryComment(String schemaName, String tableName, String columnName) {
        // 低权限账号也读不到 user_* 之外的其它 schema，这里按 owner 过滤，改用 all_* 视图
        return String.format("select B.comments\n" +
                "  from all_tab_columns A, all_col_comments B\n" +
                " where a.OWNER = b.OWNER\n" +
                "   and a.COLUMN_NAME = b.column_name\n" +
                "   and A.Table_Name = B.Table_Name\n" +
                "   and A.Owner = upper('%s')\n" +
                "   and A.Table_Name = upper('%s')\n" +
                "   AND A.column_name  = upper('%s')", safeIdentifier(schemaName, "schema"), safeIdentifier(tableName, "表名"), safeIdentifier(columnName, "字段名"));
    }

    @Override
    public String getSQLQueryPrimaryKey() {
        return "select cu.column_name from all_cons_columns cu, all_constraints au where cu.constraint_name = au.constraint_name and cu.owner = au.owner and au.owner = upper(?) and au.constraint_type = 'P' and au.table_name = ?";
    }

    @Override
    public String getSQLQueryTablesNameComments() {
        return "select table_name,comments from all_tab_comments";
    }

    @Override
    public String getSQLQueryTableNameComment() {
        return "select table_name,comments from all_tab_comments where owner=upper(?) and table_name = ?";
    }

    @Override
    public String getSQLQueryTables(String... tableSchema) {
        // dba_tables 需要 DBA 权限，普通只读账号会报 ORA-00942；all_tables + all_views 覆盖“只有视图权限”的场景
        return "select table_name from ("
                + "select table_name from all_tables where owner='" + tableSchema[0].toUpperCase() + "'"
                + " union select view_name as table_name from all_views where owner='" + tableSchema[0].toUpperCase() + "'"
                + ") order by table_name";
    }

    @Override
    public String getSQLQueryTableSchema(String... args) {
        // sys.dba_users 同样需要 DBA 权限，all_tab_comments 里的 owner 就是可见 schema
        return "select distinct owner from all_tab_comments order by owner";
    }


    @Override
    public String getSQLQueryTables() {
        return "select table_name from user_tab_comments";
    }

    @Override
    public String getSQLQueryColumns(String... args) {
        return "select table_name,comments from user_tab_comments where table_name = ?";
    }
}
