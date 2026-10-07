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
 * 批次17：countTable 原语。一致性比对报告的每个数字都出自它，
 * 所以它沿用 getMaxIdVal 的同一条纪律：SQL 失败/没有结果行必须抛，
 * 静默返回 0 会把"查不到"伪装成"空表"，比对报告直接算错差异。
 */
public class BaseQueryToolCountTest {

    private BaseQueryTool tool;
    private Connection connection;
    private Statement statement;
    private ResultSet resultSet;

    @Before
    public void setUp() throws Exception {
        tool = Mockito.mock(BaseQueryTool.class, Mockito.CALLS_REAL_METHODS);
        connection = Mockito.mock(Connection.class);
        statement = Mockito.mock(Statement.class);
        resultSet = Mockito.mock(ResultSet.class);

        DatabaseInterface sqlBuilder = Mockito.mock(DatabaseInterface.class);
        Mockito.when(connection.createStatement()).thenReturn(statement);

        ReflectionTestUtils.setField(tool, "connection", connection);
        ReflectionTestUtils.setField(tool, "sqlBuilder", sqlBuilder);
    }

    @Test
    public void normalCountReturned() throws Exception {
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(resultSet);
        Mockito.when(resultSet.next()).thenReturn(true);
        Mockito.when(resultSet.getLong(1)).thenReturn(50123456L);
        Mockito.when(resultSet.wasNull()).thenReturn(false);

        assertEquals(50123456L, tool.countTable("t_dialog"));
    }

    @Test
    public void sqlFailureMustThrowNotSwallowedAsZero() throws Exception {
        Mockito.when(statement.executeQuery(Mockito.anyString()))
                .thenThrow(new SQLException("table doesn't exist"));

        try {
            tool.countTable("t_dialog");
            fail("SQL 失败必须抛出，不能返回 0");
        } catch (IllegalStateException e) {
            assertNotNull("必须保留原始 SQLException 作为 cause", e.getCause());
            assertTrue(e.getMessage().contains("t_dialog"));
        }
    }

    @Test
    public void missingResultRowMustThrow() throws Exception {
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(resultSet);
        Mockito.when(resultSet.next()).thenReturn(false);

        try {
            tool.countTable("t_dialog");
            fail("没有结果行必须抛出");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("没有返回结果行"));
        }
    }

    @Test
    public void unsafeIdentifierIsRefusedBeforeAnySql() {
        try {
            tool.countTable("t_dialog; drop table x");
            fail("标识符必须过 SqlSafeIdentifier 闸口");
        } catch (IllegalArgumentException e) {
            assertTrue("拒绝消息要带定位（表名）：" + e.getMessage(), e.getMessage().contains("表名"));
        }
    }
}
