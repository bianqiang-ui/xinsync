package com.wugui.datax.admin.tool.query;

import com.wugui.datax.admin.tool.meta.DatabaseInterface;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 社区 #672：增量同步的主键上界取不到时，原来会静默返回 0。
 * 0 会被当作 endId 下发给执行器，于是"任务成功、零字节"，用户完全看不出来。
 * 现在要求：SQL 报错、或者根本没返回结果行，都必须抛出让触发侧记为失败；
 * 只有"空表导致 MAX(id) 为 NULL"这种合法情形才继续返回 0。
 */
public class BaseQueryToolMaxIdTest {

    private BaseQueryTool tool;
    private Connection connection;
    private Statement statement;
    private ResultSet resultSet;

    @Before
    public void setUp() throws Exception {
        // 真实构造器会去连库，这里绕过它、只把 getMaxIdVal 用到的两个字段塞进去
        tool = Mockito.mock(BaseQueryTool.class, Mockito.CALLS_REAL_METHODS);
        connection = Mockito.mock(Connection.class);
        statement = Mockito.mock(Statement.class);
        resultSet = Mockito.mock(ResultSet.class);

        DatabaseInterface sqlBuilder = Mockito.mock(DatabaseInterface.class);
        Mockito.when(sqlBuilder.getMaxId("t_inc", "id")).thenReturn("select max(id) from t_inc");
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(resultSet);

        ReflectionTestUtils.setField(tool, "connection", connection);
        ReflectionTestUtils.setField(tool, "sqlBuilder", sqlBuilder);
    }

    @Test
    public void sqlFailureMustBeAttributableNotSwallowedAsZero() throws Exception {
        Mockito.when(statement.executeQuery(Mockito.anyString()))
                .thenThrow(new SQLException("access denied for user"));

        try {
            tool.getMaxIdVal("t_inc", "id");
            fail("SQL 失败必须抛出，不能返回 0");
        } catch (IllegalStateException e) {
            assertNotNull("必须保留原始 SQLException 作为 cause", e.getCause());
            assertTrue(e.getMessage().contains("t_inc"));
            assertTrue(e.getMessage().contains("access denied for user"));
        }
    }

    @Test
    public void missingResultRowMustThrow() throws Exception {
        Mockito.when(resultSet.next()).thenReturn(false);

        try {
            tool.getMaxIdVal("t_inc", "id");
            fail("没有结果行说明元数据查询本身坏了，必须抛出");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("select max(id) from t_inc"));
        }
    }

    @Test
    public void normalMaxIdReturned() throws Exception {
        Mockito.when(resultSet.next()).thenReturn(true);
        Mockito.when(resultSet.getLong(1)).thenReturn(9001L);
        Mockito.when(resultSet.wasNull()).thenReturn(false);

        assertEquals(9001L, tool.getMaxIdVal("t_inc", "id"));
    }

    @Test
    public void emptyTableNullShouldStillBeZero() throws Exception {
        Mockito.when(resultSet.next()).thenReturn(true);
        Mockito.when(resultSet.getLong(1)).thenReturn(0L);
        Mockito.when(resultSet.wasNull()).thenReturn(true);

        assertEquals(0L, tool.getMaxIdVal("t_inc", "id"));
    }
}
