package com.wugui.datax.admin.tool.tdsql;

import com.wugui.datax.admin.entity.TdsqlShardRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * 规则读侧选择口径的单测：一张逻辑表在任意时刻只许有一条启用的规则。
 *
 * 这里每条断言都在守一种"不报错也能继续跑"的情形：返回 null、返回空列表、返回多条、
 * 返回了别的表的行。放任其中任何一条，上层拿到的都是**下一次才知道错**的输入。
 */
public class TdsqlShardRulesTest {

    @Test
    public void theSingleEnabledRowWinsRegardlessOfDisabledNeighbours() {
        List<TdsqlShardRule> rows = Arrays.asList(rule(1, "demo", "orders", 0), rule(2, "demo", "orders", 1));

        assertEquals(2, TdsqlShardRules.singleEnabled("demo", "orders", rows).getId());
    }

    @Test
    public void namesAreComparedTheWayTheDatabaseComparesThem() {
        // utf8mb4_general_ci：大小写与首尾空格不参与比较，读侧必须同口径，否则同一张表被判成两张
        List<TdsqlShardRule> rows = Collections.singletonList(rule(3, " Demo ", "Orders", 1));

        assertEquals(3, TdsqlShardRules.singleEnabled("demo", "orders", rows).getId());
    }

    @Test
    public void missingNamesAreRefusedBeforeAnyLookup() {
        expectMessage("逻辑库", () ->
                TdsqlShardRules.singleEnabled("  ", "orders", Collections.<TdsqlShardRule>emptyList()));
        expectMessage("逻辑库", () ->
                TdsqlShardRules.singleEnabled("demo", null, Collections.<TdsqlShardRule>emptyList()));
    }

    @Test
    public void nullResultIsNotReadAsNoRule() {
        // 查询失败和"这张表没配规则"是两件事：混为一谈会让生成器静默产出单表 DDL
        expectMessage("返回 null", () ->
                TdsqlShardRules.singleEnabled("demo", "orders", null));
    }

    @Test
    public void noRowAtAllSaysNoRuleRegistered() {
        expectMessage("没有登记任何分片规则", () ->
                TdsqlShardRules.singleEnabled("demo", "orders", new ArrayList<TdsqlShardRule>()));
    }

    @Test
    public void allDisabledSaysEnableOneFirst() {
        List<TdsqlShardRule> rows = Arrays.asList(rule(4, "demo", "orders", 0), rule(5, "demo", "orders", 0));
        expectMessage("全部停用", () -> TdsqlShardRules.singleEnabled("demo", "orders", rows));
    }

    @Test
    public void twoEnabledRulesFailInsteadOfTakingTheFirstOne() {
        // 唯一索引被人手工删掉时的形态。取第一条 = 取到的是数据库返回顺序，
        // 同一个作业两次跑出不同 DDL 是最难查的一类错误。
        List<TdsqlShardRule> rows = Arrays.asList(rule(6, "demo", "orders", 1), rule(9, "demo", "orders", 1));
        IllegalArgumentException e = expectMessage("条启用规则", () ->
                TdsqlShardRules.singleEnabled("demo", "orders", rows));
        assertTrueMessage(e, "id=6");
        assertTrueMessage(e, "id=9");
    }

    @Test
    public void rowsFromAnotherTableAreNeverAccepted() {
        // mapper 少写一个条件时的形态：宁可报错，也不能拿 A 表的规则去改 B 表
        List<TdsqlShardRule> rows = Arrays.asList(rule(7, "demo", "users", 1), rule(8, "demo2", "orders", 1));
        IllegalArgumentException e = expectMessage("别的表的行", () ->
                TdsqlShardRules.singleEnabled("demo", "orders", rows));
        assertTrueMessage(e, "id=7");
        assertTrueMessage(e, "id=8");
    }

    @Test
    public void nullRowIsReportedAsForeignToo() {
        List<TdsqlShardRule> rows = new ArrayList<>();
        rows.add(null);
        expectMessage("别的表的行", () -> TdsqlShardRules.singleEnabled("demo", "orders", rows));
    }

    @Test
    public void enabledOnlyCountsOneAsEnabled() {
        // enabled 为 null（历史行没写默认值）不能当成启用
        TdsqlShardRule nullEnabled = rule(10, "demo", "orders", 1);
        nullEnabled.setEnabled(null);
        assertFalse("enabled 为 null 的历史行不能当成启用", nullEnabled.isEnabledRule());
        expectMessage("全部停用", () ->
                TdsqlShardRules.singleEnabled("demo", "orders", Collections.singletonList(nullEnabled)));
    }

    private static TdsqlShardRule rule(int id, String db, String table, int enabled) {
        TdsqlShardRule r = new TdsqlShardRule();
        r.setId(id);
        r.setLogicDb(db);
        r.setLogicTable(table);
        r.setEnabled(enabled);
        return r;
    }

    private static IllegalArgumentException expectMessage(String keyword, Runnable call) {
        try {
            call.run();
        } catch (IllegalArgumentException e) {
            assertTrueMessage(e, keyword);
            return e;
        }
        fail("本该抛 IllegalArgumentException（关键词「" + keyword + "」），但它成功了");
        return null;
    }

    private static void assertTrueMessage(IllegalArgumentException e, String keyword) {
        org.junit.Assert.assertTrue("异常信息里必须点出「" + keyword + "」，实际：" + e.getMessage(),
                e.getMessage().contains(keyword));
    }
}
