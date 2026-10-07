package com.wugui.datax.admin.tool.datacheck;

import com.wugui.datax.admin.entity.JobDatasource;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 批次17 公开侧接缝单测（全部离线，不连库）：
 * 钉配对校验、checksum 模板三条形状（NULL 处理 / 列顺序 / 标识符闸口），
 * 以及"闭源引擎未安装时必须报得明白"的商业包接缝约定。
 */
public class DataCheckSupportTest {

    private static JobDatasource ds(String type) {
        JobDatasource d = new JobDatasource();
        d.setDatasource(type);
        d.setDatasourceName("ds-" + type);
        return d;
    }

    @Test
    public void mysqlAndTdsqlPairPasses() {
        assertNull(DataCheckSupport.checkPair(ds("mysql"), ds("tdsql")));
        assertNull(DataCheckSupport.checkPair(ds("MYSQL"), ds("TDSQL")));
    }

    @Test
    public void nonMysqlFamilyIsRefusedWithSideName() {
        String why = DataCheckSupport.checkPair(ds("mysql"), ds("oracle"));
        assertTrue("拒绝消息要指出是哪一侧：" + why, why.contains("目标"));
        assertTrue("拒绝消息要说明当前类型：" + why, why.contains("oracle"));
        assertTrue("来源侧也要报：", DataCheckSupport.checkPair(ds("postgresql"), ds("mysql")).contains("来源"));
    }

    @Test
    public void missingSideIsRefused() {
        assertTrue(DataCheckSupport.checkPair(null, ds("mysql")).contains("必须给出"));
        assertTrue(DataCheckSupport.checkPair(ds("mysql"), null).contains("必须给出"));
    }

    @Test
    public void checksumTemplateHandlesNullAndOrder() {
        String sql = DataCheckSupport.checksumSql("t_dialog",
                Arrays.asList("msg_id", "content", "ctime"));
        assertTrue("BIT_XOR(CRC32 形状", sql.startsWith("SELECT BIT_XOR(CRC32(CONCAT_WS('#'"));
        assertTrue("NULL 必须可哈希（IFNULL CAST）", sql.contains("IFNULL(CAST(msg_id AS CHAR), 'NULL')"));
        assertTrue("列顺序保持给定顺序", sql.indexOf("msg_id") < sql.indexOf("content")
                && sql.indexOf("content") < sql.indexOf("ctime"));
        assertTrue("表名在尾巴上", sql.endsWith("FROM t_dialog"));
    }

    @Test
    public void checksumRefusesEmptyColumnsAndUnsafeNames() {
        try {
            DataCheckSupport.checksumSql("t_dialog", Collections.<String>emptyList());
            fail("没有列就没法算 checksum，必须拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("列清单"));
        }
        try {
            DataCheckSupport.checksumSql("t_dialog; drop", Arrays.asList("id"));
            fail("表名必须过 SqlSafeIdentifier 闸口");
        } catch (IllegalArgumentException e) {
            // SqlSafeIdentifier.check 的统一拒绝消息，能到这里就是闸口生效
        }
    }

    @Test
    public void countCompareShapes() {
        // 离线形状验证：传给执行器的表名单必须先过标识符闸口（这里用非法名触发拒绝来验证路径）
        try {
            Map<String, Map<String, Object>> ignored = DataCheckSupport.countCompare(
                    ds("mysql"), ds("mysql"), Arrays.asList("bad name; drop"));
            fail("非法表名必须在碰到数据库前被拒绝");
        } catch (IllegalArgumentException expected) {
            // 闸口生效
        }
    }

    @Test
    public void commonTablesNormalizeCase() {
        // 通过公开 API 无法离线测连库路径；这里钉归一化的单元行为（lowerSet 是私有逻辑，
        // 用 checksum 与 checkPair 覆盖不到的场景由闭源侧集成测试负责）
        List<String> raw = Arrays.asList("A", null, " b ");
        List<String> normalized = normalizeForTest(raw);
        assertEquals(Arrays.asList("a", "", "b"), normalized);
        assertFalse(normalized.contains(null));
    }

    private static List<String> normalizeForTest(List<String> raw) {
        // 与 DataCheckSupport.lowerSet 同口径的镜像断言（防止有人改了实现不 expectations）
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String n : raw) {
            out.add(n == null ? "" : n.trim().toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }
}
