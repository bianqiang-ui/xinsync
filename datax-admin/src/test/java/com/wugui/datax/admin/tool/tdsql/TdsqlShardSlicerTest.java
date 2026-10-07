package com.wugui.datax.admin.tool.tdsql;

import com.wugui.datax.admin.entity.TdsqlShardRule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 批次16（T2-B）切片器单测：每片一份 jobJson 的纯函数判据。
 *
 * 每条都在守施工单的一条红线或一种形状：querySql 拒绝（红线2）、表名对不上拒绝、
 * pkColumns 缺失拒绝（不猜主键）、where 拼接两条路径、分片号边界、内容不被越权改动。
 */
public class TdsqlShardSlicerTest {

    /** 手工核对过括号层数的合法 jobJson：content[0] 里 reader/writer 各一个 connection。 */
    private static final String JOB_JSON = "{"
            + "\"content\":[{"
            +   "\"reader\":{\"name\":\"mysqlreader\",\"parameter\":{"
            +     "\"username\":\"@@DATAX_DS_USER:7@@\",\"password\":\"@@DATAX_DS_PWD:7@@\","
            +     "\"column\":[\"id\",\"uid\",\"amount\"],\"splitPk\":\"id\","
            +     "\"connection\":[{"
            +       "\"table\":[\"orders\"],"
            +       "\"jdbcUrl\":[\"jdbc:mysql://src:3306/srcdb\"]"
            +     "}]"
            +   "}},"
            +   "\"writer\":{\"name\":\"mysqlwriter\",\"parameter\":{"
            +     "\"username\":\"@@DATAX_DS_USER:9@@\",\"password\":\"@@DATAX_DS_PWD:9@@\","
            +     "\"column\":[\"id\",\"uid\",\"amount\"],"
            +     "\"connection\":[{"
            +       "\"table\":[\"orders\"],"
            +       "\"jdbcUrl\":[\"jdbc:tdsql://dst:3306/dstdb\"]"
            +     "}]"
            +   "}}"
            + "}]"
            + "}";

    private static TdsqlShardRule shardRule() {
        TdsqlShardRule rule = new TdsqlShardRule();
        rule.setId(11);
        rule.setDatasourceId(9L);
        rule.setLogicDb("dstdb");
        rule.setLogicTable("orders");
        rule.setTableType("SHARD");
        rule.setShardKey("uid");
        rule.setPkColumns("id");
        rule.setEnabled(1);
        return rule;
    }

    @Test
    public void eachSliceGetsItsOwnModulusFilter() {
        String s0 = TdsqlShardSlicer.slice(JOB_JSON, shardRule(), 0, 4);
        String s3 = TdsqlShardSlicer.slice(JOB_JSON, shardRule(), 3, 4);
        assertTrue("第 0 片必须带 (id % 4) = 0", s0.contains("(id % 4) = 0"));
        assertTrue("第 3 片必须带 (id % 4) = 3", s3.contains("(id % 4) = 3"));
        assertFalse("两片条件必须不同", s0.equals(s3));
    }

    @Test
    public void existingWhereIsKeptAndConditionIsAppendedWithAnd() {
        String withWhere = JOB_JSON.replace("\"splitPk\":\"id\",",
                "\"splitPk\":\"id\",\"where\":\"dt = '2026-10-07'\",");
        String out = TdsqlShardSlicer.slice(withWhere, shardRule(), 2, 4);
        assertTrue("原有 where 不能被覆盖", out.contains("dt = '2026-10-07'"));
        assertTrue("切片条件必须用 AND 追加", out.contains("dt = '2026-10-07' AND (id % 4) = 2"));
    }    @Test
    public void missingWhereCreatesTheKey() {
        String out = TdsqlShardSlicer.slice(JOB_JSON, shardRule(), 1, 2);
        assertTrue("无 where 时必须新增 where 键", out.contains("\"where\":\"(id % 2) = 1\""));
    }

    @Test
    public void writerSideAndRestOfJsonStayUntouched() {
        String out = TdsqlShardSlicer.slice(JOB_JSON, shardRule(), 0, 2);
        assertTrue("writer 侧 jdbcUrl 不许被改动", out.contains("jdbc:tdsql://dst:3306/dstdb"));
        assertTrue("writer 的占位符原样保留（账密还原在触发链路后段）",
                out.contains("@@DATAX_DS_PWD:9@@"));
        assertFalse("reader 侧不能被整份换成切片条件（占位符仍在）",
                !out.contains("@@DATAX_DS_USER:7@@"));
    }

    @Test
    public void querySqlModeIsRefusedLoudly() {
        String direct = "{\"content\":[{\"reader\":{\"name\":\"mysqlreader\",\"parameter\":{"
                + "\"connection\":[{\"querySql\":[\"select * from orders\"],"
                + "\"jdbcUrl\":[\"jdbc:mysql://src:3306/srcdb\"]}]}}},"
                + "\"writer\":{\"name\":\"mysqlwriter\",\"parameter\":{"
                + "\"connection\":[{\"table\":[\"orders\"],\"jdbcUrl\":[\"jdbc:x\"]}]}}}]}";
        expectMessage("querySql", () -> TdsqlShardSlicer.slice(direct, shardRule(), 0, 2));
    }

    @Test
    public void multiTableReaderIsRefused() {
        String multi = JOB_JSON.replace("\"table\":[\"orders\"]", "\"table\":[\"orders\",\"orders_bak\"]");
        expectMessage("多表", () -> TdsqlShardSlicer.slice(multi, shardRule(), 0, 2));
    }

    @Test
    public void tableMismatchWithRuleIsRefused() {
        TdsqlShardRule otherTable = shardRule();
        otherTable.setLogicTable("refund_orders");
        expectMessage("表名对不上", () -> TdsqlShardSlicer.slice(JOB_JSON, otherTable, 0, 2));
    }

    @Test
    public void missingPkColumnsIsRefusedInsteadOfGuessing() {
        TdsqlShardRule noPk = shardRule();
        noPk.setPkColumns(" ");
        expectMessage("pkColumns", () -> TdsqlShardSlicer.slice(JOB_JSON, noPk, 0, 2));
    }

    @Test
    public void invalidIndexOrTotalIsRefused() {
        expectMessage("分片号", () -> TdsqlShardSlicer.slice(JOB_JSON, shardRule(), 4, 4));
        expectMessage("分片总数", () -> TdsqlShardSlicer.slice(JOB_JSON, shardRule(), 0, 0));
    }

    @Test
    public void singleShardStillSlicesToItself() {
        // total=1：切片条件 (id % 1) = 0 恒真，等价全量——语义一致即可，不必特殊分支
        String out = TdsqlShardSlicer.slice(JOB_JSON, shardRule(), 0, 1);
        assertTrue(out.contains("(id % 1) = 0"));
    }

    @Test
    public void pkColumnsFirstColumnIsUsed() {
        TdsqlShardRule composite = shardRule();
        composite.setPkColumns("id, uid");
        String out = TdsqlShardSlicer.slice(JOB_JSON, composite, 1, 3);
        assertTrue("复合主键取第一列做切片列", out.contains("(id % 3) = 1"));
    }

    private static IllegalArgumentException expectMessage(String expected, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains(expected)) {
                fail("异常消息应包含「" + expected + "」：" + e.getMessage());
            }
            return e;
        }
        fail("应当抛出包含「" + expected + "」的 IllegalArgumentException");
        return null; // 不可达
    }
}
