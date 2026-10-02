package com.wugui.datax.admin.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link SqlSafeIdentifier} 的用例：拼进 SQL 结构位置的列名只许是"裸列名"。
 *
 * 反证口径：把 SqlSafeIdentifier 的正则放宽成允许任意字符，下面每一条"必须拒绝"的用例都会变红。
 */
public class SqlSafeIdentifierTest {

    @Test
    public void acceptsPlainColumnNames() {
        assertEquals("datasource_name", SqlSafeIdentifier.check("datasource_name", "排序字段"));
        // 前后空白是允许的（前端偶尔带上），落库前会被 trim 掉
        assertEquals("job_desc", SqlSafeIdentifier.check("  job_desc  ", "排序字段"));
        assertEquals("id2", SqlSafeIdentifier.check("id2", "排序字段"));
        assertEquals("_private", SqlSafeIdentifier.check("_private", "排序字段"));
    }

    /**
     * 真实前端用法：{@code ascs=datasource_name}、以及注释里写明的"多个用逗号分隔"。
     * 拆分后每段各自校验，绝不能把整串原样交给 ORDER BY。
     */
    @Test
    public void splitsAndChecksEachColumnSeparately() {
        List<String> columns = SqlSafeIdentifier.splitAndCheck("datasourceName, job_desc", "排序字段");
        assertEquals(Arrays.asList("datasource_name", "job_desc"), columns);

        assertEquals(Arrays.asList("id"), SqlSafeIdentifier.splitAndCheck("id", "排序字段"));
    }

    @Test
    public void rejectsEveryMetacharacterThatCouldLeaveTheColumnName() {
        String[] payloads = {
                "id; DROP TABLE job_info",
                "id -- comment",
                "id # comment",
                "id/*x*/",
                "(select 1)",
                "id,(",
                "1id",
                "id name",
                "id`",
                "id\"",
                "id'",
                "datasource\\\"name",
                "if(1=1,id)",
                "",
                "   ",
        };
        for (String payload : payloads) {
            try {
                SqlSafeIdentifier.splitAndCheck(payload, "排序字段");
                fail("应当拒绝，却放过了：" + payload);
            } catch (IllegalArgumentException expected) {
                // 文案里只允许出现调用方自己传进来的值，不许带 SQL 片段或表名
                assertTrue("文案应以『非法的排序字段』开头，实际是：" + expected.getMessage(),
                        expected.getMessage().startsWith("非法的排序字段"));
            }
        }
    }

    @Test
    public void rejectsIdentifiersLongerThanMySqlAllows() {
        StringBuilder tooLong = new StringBuilder("a");
        for (int i = 0; i < 64; i++) {
            tooLong.append("b");
        }
        assertEquals(65, tooLong.length());
        try {
            SqlSafeIdentifier.check(tooLong.toString(), "排序字段");
            fail("超过 64 字符的标识符必须拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("非法的排序字段"));
        }
    }

    @Test
    public void checkRejectsTheWholeBatchWhenOneColumnIsBad() {
        try {
            // 逗号分隔的多列里混一列非法，整批拒绝——不能"合法的那几列照排，非法的悄悄丢掉"
            SqlSafeIdentifier.splitAndCheck("id,1=1", "排序字段");
            fail("一批里有一个非法就必须整体拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue("文案应点明是排序字段，实际：" + expected.getMessage(),
                    expected.getMessage().contains("排序字段"));
        }
    }
}
