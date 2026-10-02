package com.wugui.datax.admin.tool.query;

import cn.hutool.core.util.StrUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.wugui.datatx.core.util.Constants;
import com.wugui.datax.admin.core.util.LocalCacheUtil;
import com.wugui.datax.admin.entity.JobDatasource;
import com.wugui.datax.admin.tool.database.ColumnInfo;
import com.wugui.datax.admin.tool.database.DasColumn;
import com.wugui.datax.admin.tool.database.TableInfo;
import com.wugui.datax.admin.tool.meta.DatabaseInterface;
import com.wugui.datax.admin.tool.meta.DatabaseMetaFactory;
import com.wugui.datax.admin.util.AESUtil;
import com.wugui.datax.admin.util.JdbcConstants;
import com.wugui.datax.admin.util.JdbcUtils;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 抽象查询工具
 *
 * @author zhouhongfa@gz-yibo.com
 * @ClassName BaseQueryTool
 * @Version 1.0
 * @since 2019/7/18 9:22
 */
public abstract class BaseQueryTool implements QueryToolInterface {

    protected static final Logger logger = LoggerFactory.getLogger(BaseQueryTool.class);

    /**
     * schema/表/列名会被各 DatabaseMeta 直接拼进元数据 SQL（无法参数化），这里统一限制字符集，阻断注入
     */
    private static final String IDENTIFIER_RULE = "^[A-Za-z0-9_$#.]{1,128}$";

    /**
     * Hive 各版本都支持、且不会拉起 MapReduce/Tez 作业的探活语句
     */
    private static final String HIVE_VALIDATION_QUERY = "SHOW DATABASES";

    static String checkIdentifier(String name, String desc) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException(desc + "不能为空");
        }
        String value = name.trim();
        if (!value.matches(IDENTIFIER_RULE)) {
            throw new IllegalArgumentException("非法的" + desc + "：" + value);
        }
        return value;
    }

    /**
     * 部分调用方允许不传（表示全库查询），只在传了值的时候做校验
     */
    static String checkIdentifierIfPresent(String name, String desc) {
        return StringUtils.isBlank(name) ? name : checkIdentifier(name, desc);
    }
    /**
     * 用于获取查询语句
     */
    private DatabaseInterface sqlBuilder;

    private DataSource datasource;

    private Connection connection;
    /**
     * 当前数据库名
     */
    private String currentSchema;
    private String currentDatabase;

    /**
     * 构造方法
     *
     * @param jobDatasource
     */
    BaseQueryTool(JobDatasource jobDatasource) throws SQLException {
        if (LocalCacheUtil.get(jobDatasource.getDatasourceName()) == null) {
            getDataSource(jobDatasource);
        } else {
            this.connection = (Connection) LocalCacheUtil.get(jobDatasource.getDatasourceName());
            if (!isConnectionAlive(this.connection)) {
                LocalCacheUtil.remove(jobDatasource.getDatasourceName());
                getDataSource(jobDatasource);
            }
        }
        sqlBuilder = DatabaseMetaFactory.getByDbType(jobDatasource.getDatasource());
        currentSchema = getSchema(jobDatasource.getJdbcUsername());
        currentDatabase = jobDatasource.getDatasource();
        LocalCacheUtil.set(jobDatasource.getDatasourceName(), this.connection, 4 * 60 * 60 * 1000);
    }

    /**
     * 部分驱动（hive-jdbc 连旧版 HiveServer2）的 isValid() 会抛异常而不是返回 false，
     * 这里把"校验失败"和"驱动不支持该校验"统一当作连接不可用，避免整个构造流程被带崩。
     */
    private static boolean isConnectionAlive(Connection conn) {
        try {
            return conn.isValid(500);
        } catch (SQLException e) {
            logger.warn("[isValid Exception] 连接校验失败，按不可用处理：{}", e.getMessage());
            return false;
        }
    }

    private void getDataSource(JobDatasource jobDatasource) throws SQLException {
        String userName = AESUtil.decrypt(jobDatasource.getJdbcUsername());

        //这里默认使用 hikari 数据源
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setUsername(userName);
        dataSource.setPassword(AESUtil.decrypt(jobDatasource.getJdbcPassword()));
        dataSource.setJdbcUrl(jobDatasource.getJdbcUrl());
        dataSource.setDriverClassName(jobDatasource.getJdbcDriverClass());
        dataSource.setMaximumPoolSize(1);
        dataSource.setMinimumIdle(0);
        dataSource.setConnectionTimeout(30000);
        // HikariCP 默认用 Connection.isValid() 做连接校验；hive-jdbc 的 isValid() 内部会发
        // Thrift GetInfo RPC，旧版 HiveServer2（1.1.x / CDH5）未实现该接口，于是每次取连接都抛
        // "Method not supported"（Issue #296）。显式给一条各 Hive 版本都支持、且不触发计算引擎的语句。
        if (JdbcConstants.HIVE.equalsIgnoreCase(jobDatasource.getDatasource())) {
            dataSource.setConnectionTestQuery(HIVE_VALIDATION_QUERY);
        }
        this.datasource = dataSource;
        this.connection = this.datasource.getConnection();
    }

    //根据connection获取schema
    private String getSchema(String jdbcUsername) {
        String res = null;
        try {
            res = connection.getCatalog();
        } catch (SQLException e) {
            try {
                res = connection.getSchema();
            } catch (SQLException e1) {
                logger.error("[SQLException getSchema Exception] --> "
                        + "the exception message is:" + e1.getMessage());
            }
            logger.error("[getSchema Exception] --> "
                    + "the exception message is:" + e.getMessage());
        }
        // 如果res是null，则将用户名当作 schema
        if (StrUtil.isBlank(res) && StringUtils.isNotBlank(jdbcUsername)) {
            res = jdbcUsername.toUpperCase();
        }
        return res;
    }

    @Override
    public TableInfo buildTableInfo(String tableName) {
        checkIdentifierIfPresent(tableName, "表名");
        //获取表信息
        List<Map<String, Object>> tableInfos = this.getTableInfo(tableName);
        if (tableInfos.isEmpty()) {
            throw new NullPointerException("查询出错! ");
        }

        TableInfo tableInfo = new TableInfo();
        //表名，注释
        List tValues = new ArrayList(tableInfos.get(0).values());

        tableInfo.setName(StrUtil.toString(tValues.get(0)));
        tableInfo.setComment(StrUtil.toString(tValues.get(1)));


        //获取所有字段
        List<ColumnInfo> fullColumn = getColumns(tableName);
        tableInfo.setColumns(fullColumn);

        //获取主键列
        List<String> primaryKeys = getPrimaryKeys(tableName);
        logger.info("主键列为：{}", primaryKeys);

        //设置ifPrimaryKey标志
        fullColumn.forEach(e -> {
            if (primaryKeys.contains(e.getName())) {
                e.setIfPrimaryKey(true);
            } else {
                e.setIfPrimaryKey(false);
            }
        });
        return tableInfo;
    }

    //无论怎么查，返回结果都应该只有表名和表注释，遍历map拿value值即可
    @Override
    public List<Map<String, Object>> getTableInfo(String tableName) {
        checkIdentifierIfPresent(tableName, "表名");
        String sqlQueryTableNameComment = sqlBuilder.getSQLQueryTableNameComment();
        logger.info(sqlQueryTableNameComment);
        List<Map<String, Object>> res = null;
        try {
            res = JdbcUtils.executeQuery(connection, sqlQueryTableNameComment, ImmutableList.of(currentSchema, tableName));
        } catch (SQLException e) {
            logger.error("[getTableInfo Exception] --> "
                    + "the exception message is:" + e.getMessage());
        }
        return res;
    }

    @Override
    public List<Map<String, Object>> getTables() {
        String sqlQueryTables = sqlBuilder.getSQLQueryTables();
        logger.info(sqlQueryTables);
        List<Map<String, Object>> res = null;
        try {
            res = JdbcUtils.executeQuery(connection, sqlQueryTables, ImmutableList.of(currentSchema));
        } catch (SQLException e) {
            logger.error("[getTables Exception] --> "
                    + "the exception message is:" + e.getMessage());
        }
        return res;
    }

    @Override
    public List<ColumnInfo> getColumns(String tableName) {

        List<ColumnInfo> fullColumn = Lists.newArrayList();
        //获取指定表的所有字段
        checkIdentifierIfPresent(tableName, "表名");
        try {
            //获取查询指定表所有字段的sql语句
            String querySql = sqlBuilder.getSQLQueryFields(tableName);
            logger.info("querySql: {}", querySql);

            //获取所有字段
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(querySql);
            ResultSetMetaData metaData = resultSet.getMetaData();

            List<DasColumn> dasColumns = buildDasColumn(tableName, metaData);
            statement.close();

            //构建 fullColumn
            fullColumn = buildFullColumn(dasColumns);

        } catch (SQLException e) {
            logger.error("[getColumns Exception] --> "
                    + "the exception message is:" + e.getMessage());
        }
        return fullColumn;
    }

    private List<ColumnInfo> buildFullColumn(List<DasColumn> dasColumns) {
        List<ColumnInfo> res = Lists.newArrayList();
        dasColumns.forEach(e -> {
            ColumnInfo columnInfo = new ColumnInfo();
            columnInfo.setName(e.getColumnName());
            columnInfo.setComment(e.getColumnComment());
            columnInfo.setType(e.getColumnTypeName());
            columnInfo.setIfPrimaryKey(e.isIsprimaryKey());
            columnInfo.setIsnull(e.getIsNull());
            res.add(columnInfo);
        });
        return res;
    }

    //构建DasColumn对象
    private List<DasColumn> buildDasColumn(String tableName, ResultSetMetaData metaData) {
        List<DasColumn> res = Lists.newArrayList();
        try {
            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                DasColumn dasColumn = new DasColumn();
                dasColumn.setColumnClassName(metaData.getColumnClassName(i));
                dasColumn.setColumnTypeName(metaData.getColumnTypeName(i));
                dasColumn.setColumnName(metaData.getColumnName(i));
                dasColumn.setIsNull(metaData.isNullable(i));

                res.add(dasColumn);
            }

            Statement statement = connection.createStatement();

            if (currentDatabase.equals(JdbcConstants.MYSQL) || currentDatabase.equals(JdbcConstants.ORACLE)) {
                DatabaseMetaData databaseMetaData = connection.getMetaData();

                ResultSet resultSet = databaseMetaData.getPrimaryKeys(null, null, tableName);

                while (resultSet.next()) {
                    String name = resultSet.getString("COLUMN_NAME");
                    res.forEach(e -> {
                        if (e.getColumnName().equals(name)) {
                            e.setIsprimaryKey(true);

                        } else {
                            e.setIsprimaryKey(false);
                        }
                    });
                }

                res.forEach(e -> {
                    String sqlQueryComment = sqlBuilder.getSQLQueryComment(currentSchema, tableName, e.getColumnName());
                    //查询字段注释
                    try {
                        ResultSet resultSetComment = statement.executeQuery(sqlQueryComment);
                        while (resultSetComment.next()) {
                            e.setColumnComment(resultSetComment.getString(1));
                        }
                        JdbcUtils.close(resultSetComment);
                    } catch (SQLException e1) {
                        logger.error("[buildDasColumn executeQuery Exception] --> "
                                + "the exception message is:" + e1.getMessage());
                    }
                });
            }

            JdbcUtils.close(statement);
        } catch (SQLException e) {
            logger.error("[buildDasColumn Exception] --> "
                    + "the exception message is:" + e.getMessage());
        }
        return res;
    }

    //获取指定表的主键，可能是多个，所以用list
    private List<String> getPrimaryKeys(String tableName) {
        List<String> res = Lists.newArrayList();
        String sqlQueryPrimaryKey = sqlBuilder.getSQLQueryPrimaryKey();
        try {
            List<Map<String, Object>> pkColumns = JdbcUtils.executeQuery(connection, sqlQueryPrimaryKey, ImmutableList.of(currentSchema, tableName));
            //返回主键名称即可
            pkColumns.forEach(e -> res.add((String) new ArrayList<>(e.values()).get(0)));
        } catch (SQLException e) {
            logger.error("[getPrimaryKeys Exception] --> "
                    + "the exception message is:" + e.getMessage());
        }
        return res;
    }

    @Override
    public List<String> getColumnNames(String tableName, String datasource) {

        List<String> res = Lists.newArrayList();
        Statement stmt = null;
        ResultSet rs = null;
        checkIdentifierIfPresent(tableName, "表名");
        try {
            //获取查询指定表所有字段的sql语句
            String querySql = sqlBuilder.getSQLQueryFields(tableName);
            logger.info("querySql: {}", querySql);

            //获取所有字段
            stmt = connection.createStatement();
            rs = stmt.executeQuery(querySql);
            ResultSetMetaData metaData = rs.getMetaData();

            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = metaData.getColumnName(i);
                if (JdbcConstants.HIVE.equals(datasource)) {
                    if (columnName.contains(Constants.SPLIT_POINT)) {
                        res.add(i - 1 + Constants.SPLIT_SCOLON + columnName.substring(columnName.indexOf(Constants.SPLIT_POINT) + 1) + Constants.SPLIT_SCOLON + metaData.getColumnTypeName(i));
                    } else {
                        res.add(i - 1 + Constants.SPLIT_SCOLON + columnName + Constants.SPLIT_SCOLON + metaData.getColumnTypeName(i));
                    }
                } else {
                    res.add(columnName);
                }

            }
        } catch (SQLException e) {
            logger.error("[getColumnNames Exception] --> "
                    + "the exception message is:" + e.getMessage());
        } finally {
            JdbcUtils.close(rs);
            JdbcUtils.close(stmt);
        }
        return res;
    }

    @Override
    public List<String> getTableNames(String tableSchema) {
        List<String> tables = new ArrayList<String>();
        checkIdentifierIfPresent(tableSchema, "schema");
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = connection.createStatement();
            //获取sql
            String sql = getSQLQueryTables(tableSchema);
            rs = stmt.executeQuery(sql);
            while (rs.next()) {
                String tableName = rs.getString(1);
                tables.add(tableName);
            }
            tables.sort(Comparator.naturalOrder());
        } catch (SQLException e) {
            logger.error("[getTableNames Exception] --> "
                    + "the exception message is:" + e.getMessage());
        } finally {
            JdbcUtils.close(rs);
            JdbcUtils.close(stmt);
        }
        return tables;
    }

    @Override
    public List<String> getTableNames() {
        List<String> tables = new ArrayList<String>();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = connection.createStatement();
            //获取sql
            String sql = getSQLQueryTables();
            rs = stmt.executeQuery(sql);
            while (rs.next()) {
                String tableName = rs.getString(1);
                tables.add(tableName);
            }
        } catch (SQLException e) {
            logger.error("[getTableNames Exception] --> "
                    + "the exception message is:" + e.getMessage());
        } finally {
            JdbcUtils.close(rs);
            JdbcUtils.close(stmt);
        }
        return tables;
    }

    public Boolean dataSourceTest() {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            if (metaData.getDatabaseProductName().length() > 0) {
                return true;
            }
        } catch (SQLException e) {
            // hive-jdbc 对旧版 HiveServer2 取不到 DatabaseMetaData（GetInfo 未实现），降级用语句探活
            logger.warn("[dataSourceTest] getDatabaseProductName 失败，降级为执行探活语句：{}", e.getMessage());
        }
        return probeWithSql();
    }

    /**
     * 执行该库类型自带的"列表"语句来确认连接可用（Hive 为 show tables），
     * 只在 DatabaseMetaData 不可用时作为兜底。
     */
    private boolean probeWithSql() {
        String probeSql = sqlBuilder == null ? null : sqlBuilder.getSQLQueryTables();
        if (StringUtils.isBlank(probeSql)) {
            return false;
        }
        Statement stmt = null;
        try {
            stmt = connection.createStatement();
            stmt.execute(probeSql);
            return true;
        } catch (SQLException e) {
            logger.error("[probeWithSql Exception] --> the exception message is:" + e.getMessage());
            return false;
        } finally {
            JdbcUtils.close(stmt);
        }
    }


    protected String getSQLQueryTables(String tableSchema) {
        return sqlBuilder.getSQLQueryTables(tableSchema);
    }

    /**
     * 不需要其他参数的可不重写
     *
     * @return
     */
    protected String getSQLQueryTables() {
        return sqlBuilder.getSQLQueryTables();
    }

    @Override
    public List<String> getColumnsByQuerySql(String querySql) throws SQLException {

        List<String> res = Lists.newArrayList();
        Statement stmt = null;
        ResultSet rs = null;
        String sql = buildLimitSql(querySql);
        try {
            //获取所有字段
            stmt = connection.createStatement();
            rs = stmt.executeQuery(sql);
            ResultSetMetaData metaData = rs.getMetaData();

            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                res.add(metaData.getColumnName(i));
            }
        } catch (SQLException e) {
            logger.error("[getColumnsByQuerySql Exception] --> " + e.getMessage(), e);
            // 不把数据库原始报错回传前端，避免泄露库表结构信息
            throw new SQLException("查询字段失败，请检查 SQL 语法是否正确");
        } finally {
            JdbcUtils.close(rs);
            JdbcUtils.close(stmt);
        }
        return res;
    }

    /**
     * 只允许单条 select 语句，并追加恒假条件用于取列名。
     */
    private String buildLimitSql(String querySql) {
        if (StringUtils.isBlank(querySql)) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String sql = querySql.trim();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        String lower = sql.toLowerCase();
        if (sql.indexOf(';') >= 0) {
            throw new IllegalArgumentException("只允许执行单条 SQL 语句");
        }
        if (!lower.startsWith("select")) {
            throw new IllegalArgumentException("只允许执行 select 查询语句");
        }
        if (lower.contains("--") || lower.contains("#") || lower.contains("/*") || lower.contains("*/")) {
            throw new IllegalArgumentException("SQL 中不允许包含注释");
        }
        if (lower.matches("(?s).*(insert|update|delete|drop|truncate|alter|create|grant|revoke|exec|execute)\\s.*")) {
            throw new IllegalArgumentException("SQL 中不允许出现写操作关键字");
        }
        //拼装sql语句，在后面加上 where 1=0 即可
        //判断是否已有 where，如果是，则加 and 1=0
        return (lower.contains("where") ? sql.concat(" and 1=0") : sql.concat(" where 1=0"));
    }

    @Override
    public long getMaxIdVal(String tableName, String primaryKey) {
        checkIdentifier(tableName, "表名");
        checkIdentifier(primaryKey, "主键名");
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = connection.createStatement();
            //获取sql
            String sql = getSQLMaxID(tableName, primaryKey);
            rs = stmt.executeQuery(sql);
            // 没有结果行说明这条元数据 SQL 本身出了问题（权限/连接/方言不兼容）。
            // 以前 SQLException 会被吞掉、直接 return 0，而 0 会作为增量上界 endId 下发给执行器，
            // 表现就是"任务显示成功但一条数据都没同步"（社区 #672）——取不到上界必须是失败，不能是 0。
            if (!rs.next()) {
                throw new IllegalStateException("获取主键上界没有返回结果行，sql = [" + sql + "]");
            }
            long maxVal = rs.getLong(1);
            if (rs.wasNull()) {
                // 空表时 MAX(id) 本身就是 NULL，这是合法结果，语义上确实没有更大的主键
                logger.warn("[getMaxIdVal] 表 {}.{} 的上界为 NULL（通常是空表），按 0 处理", tableName, primaryKey);
                return 0;
            }
            return maxVal;
        } catch (SQLException e) {
            logger.error("[getMaxIdVal Exception] --> "
                    + "the exception message is:" + e.getMessage());
            throw new IllegalStateException("获取主键上界失败，table = " + tableName + ", primaryKey = " + primaryKey
                    + "：" + e.getMessage(), e);
        } finally {
            JdbcUtils.close(rs);
            JdbcUtils.close(stmt);
        }
    }

    private String getSQLMaxID(String tableName, String primaryKey) {
        return sqlBuilder.getMaxId(tableName, primaryKey);
    }

    public void executeCreateTableSql(String querySql) {
        if (StringUtils.isBlank(querySql)) {
            return;
        }
        Statement stmt = null;
        try {
            stmt = connection.createStatement();
            stmt.executeUpdate(querySql);
        } catch (SQLException e) {
            logger.error("[executeCreateTableSql Exception] --> "
                    + "the exception message is:" + e.getMessage());
        } finally {
            JdbcUtils.close(stmt);
        }
    }

    public List<String> getTableSchema() {
        List<String> schemas = new ArrayList<>();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = connection.createStatement();
            //获取sql
            String sql = getSQLQueryTableSchema();
            rs = stmt.executeQuery(sql);
            while (rs.next()) {
                String tableName = rs.getString(1);
                schemas.add(tableName);
            }
        } catch (SQLException e) {
            logger.error("[getTableNames Exception] --> "
                    + "the exception message is:" + e.getMessage());
        } finally {
            JdbcUtils.close(rs);
            JdbcUtils.close(stmt);
        }
        return schemas;
    }

    protected String getSQLQueryTableSchema() {
        return sqlBuilder.getSQLQueryTableSchema();
    }
}
