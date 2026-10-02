package com.wugui.datax.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wugui.datax.admin.entity.JobDatasource;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 分页接口的排序/查询字段必须过列名白名单（批次 10-A）。
 *
 * <p>为什么盯这条路径：{@code ascs / descs} 与列查询条件的 key 会被 mybatis-plus **原样拼**进
 * {@code ORDER BY} / {@code WHERE}，不走预编译占位符；而它是登录用户（甚至部分环境的匿名只读页）
 * 直接可控的字符串。判定必须在拼装之前，不能指望"数据库报错挡一下"。
 *
 * <p>反证：把 {@code SqlSafeIdentifier.check / splitAndCheck} 改成直接返回入参，本类三条负例全部变红；
 * 把正例的白名单写死成只允许 {@code id}，则 {@code datasource_name} 那条变红。
 */
public class BaseFormOrderByWhitelistTest {

    /**
     * BaseForm 的构造函数会去拿 ServletRequestAttributes，单测环境没有 → 它自己 catch 掉，
     * 参数表为空。我们只关心 pageQueryWrapperCustom 对**显式传入 map** 的判定，故直接喂 map。
     */
    private QueryWrapper<JobDatasource> assemble(Map<String, Object> params) {
        BaseForm form = new BaseForm();
        return (QueryWrapper<JobDatasource>) form.pageQueryWrapperCustom(params, new QueryWrapper<JobDatasource>());
    }

    private Map<String, Object> params(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    @Test
    public void legitimateSortColumnStillReachesOrderBy() {
        // 前端真实用法：按下划线列名排序。白名单不能把正常功能一起杀掉。
        QueryWrapper<JobDatasource> wrapper = assemble(params("ascs", "datasource_name"));
        String sql = wrapper.getSqlSegment();
        assertTrue("合法排序字段应当进入 ORDER BY，实际片段：" + sql,
                sql.toUpperCase().contains("DATASOURCE_NAME"));
    }

    @Test
    public void legitimateCamelCaseFilterKeyStillReachesWhere() {
        QueryWrapper<JobDatasource> wrapper = assemble(params("datasourceName", "prod"));
        String sql = wrapper.getSqlSegment();
        assertTrue("驼峰 key 仍应被规范化成列名，实际片段：" + sql,
                sql.toUpperCase().contains("DATASOURCE_NAME"));
        assertFalse("值必须走占位符，不能把明文拼进 SQL 片段：" + sql, sql.contains("prod"));
    }

    @Test
    public void injectionPayloadInOrderByIsRefusedAndNothingIsAppended() {
        String[] payloads = {
                "id; DROP TABLE job_info",
                "1=(SELECT 1)",
                "id,(updatexml(1,concat(0x7e,user()),1))",
        };
        for (String payload : payloads) {
            try {
                assemble(params("ascs", payload));
                fail("排序字段注入载荷必须被拒绝：" + payload);
            } catch (IllegalArgumentException expected) {
                assertTrue("文案应说明是排序字段问题，实际：" + expected.getMessage(),
                        expected.getMessage().contains("排序字段"));
            }
        }
    }

    @Test
    public void injectionPayloadInFilterKeyIsRefused() {
        try {
            assemble(params("datasource_name=1 OR 1=1 --", "x"));
            fail("列查询条件的 key 同样不可信");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("查询字段"));
        }
    }

    /**
     * 抛出异常时不得留下"半个 wrapper"：调用方拿到的是同一个实例，且注入内容没有进去。
     */
    @Test
    public void refusedWrapperCarriesNeitherThePayloadNorAPartialClause() {
        QueryWrapper<JobDatasource> wrapper = new QueryWrapper<>();
        BaseForm form = new BaseForm();
        Map<String, Object> map = params("ascs", "datasource_name");
        map.put("descs", "1=(select 1)");
        try {
            form.pageQueryWrapperCustom(map, wrapper);
            fail("descs 非法，必须抛");
        } catch (IllegalArgumentException expected) {
            // 预期内
        }
        assertFalse("非法载荷不许出现在 SQL 片段里：" + wrapper.getSqlSegment(),
                wrapper.getSqlSegment().toUpperCase().contains("SELECT"));
        assertSame(wrapper, form.pageQueryWrapperCustom(new LinkedHashMap<String, Object>(), wrapper));
    }
}
