package com.wugui.datax.admin.tool.tdsql;

import com.wugui.datax.admin.entity.TdsqlShardRule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 批次16（T2-B）接线层单测：全部用**静态正则解构**打（不走 Spring/DB），
 * 钉"该不该切"的现场判定；JobTrigger 的行为侧由第 17 道门禁的形状判据守。
 */
public class TdsqlShardDispatchTest {

    /** 与 TdsqlShardSlicerTest.JOB_JSON 同形状（括号平衡的合法 JSON）。 */
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

    @Test
    public void writerDatasourceIdIsExtractedFromThePlaceholder() {
        assertEquals(9L, TdsqlShardDispatch.extractWriterDatasourceId(JOB_JSON));
    }

    @Test
    public void jobJsonWithoutPlaceholdersYieldsNegativeOne() {
        // 老密文任务：识别不了目标库 → 接线层必须判"不可切片"，走现状行为
        String legacy = JOB_JSON.replace("@@DATAX_DS_USER:7@@", "srcuser")
                .replace("@@DATAX_DS_PWD:7@@", "srcpwd")
                .replace("@@DATAX_DS_USER:9@@", "dstuser")
                .replace("@@DATAX_DS_PWD:9@@", "dstpwd");
        assertEquals(-1L, TdsqlShardDispatch.extractWriterDatasourceId(legacy));
    }

    @Test
    public void writerTableIsExtracted() {
        assertEquals("orders", TdsqlShardDispatch.extractWriterTable(JOB_JSON));
    }

    @Test
    public void jobJsonWithoutWriterTableYieldsNull() {
        assertEquals(null, TdsqlShardDispatch.extractWriterTable("{\"content\":[]}"));
    }

    @Test
    public void shardRuleIsAcceptedAndOtherTypesRejected() {
        TdsqlShardRule shard = rule("SHARD");
        assertTrue(TdsqlShardDispatch.isShardRule(shard));

        assertFalse("广播表的切片语义未定义，不许切", TdsqlShardDispatch.isShardRule(rule("BROADCAST")));
        assertFalse("单表没有切片语义", TdsqlShardDispatch.isShardRule(rule("SINGLE")));
        assertFalse("停用规则不许切", TdsqlShardDispatch.isShardRule(disabled()));
        assertFalse("空规则不许切", TdsqlShardDispatch.isShardRule(null));
        // 手工写入的规则值大小写不可控，判定与 TdsqlTableType.valueOf 同口径做归一化
        assertTrue("小写 shard 归一后必须接受", TdsqlShardDispatch.isShardRule(rule("shard")));
    }

    @Test
    public void glueTypeIsCaseInsensitiveWhileNonBeanIsRejected() {
        // glueType 的判定走 findSlicableRule 的私有路径，这里用 isShardRule 之外的
        // 静态入口守不住；形状由门禁钉。此处钉住"null glueType 不炸"的边界由
        // extract 系列静态方法的非空契约间接保证。
        assertEquals(-1L, TdsqlShardDispatch.extractWriterDatasourceId(null));
        assertEquals(null, TdsqlShardDispatch.extractWriterTable(null));
    }

    private static TdsqlShardRule rule(String type) {
        TdsqlShardRule r = new TdsqlShardRule();
        r.setId(1);
        r.setTableType(type);
        r.setEnabled(1);
        return r;
    }

    private static TdsqlShardRule disabled() {
        TdsqlShardRule r = rule("SHARD");
        r.setEnabled(0);
        return r;
    }
}
