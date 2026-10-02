package com.wugui.datax.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.api.R;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wugui.datax.admin.entity.JobDatasource;
import com.wugui.datax.admin.service.JobDatasourceService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据源口令不出响应体（批次10-G）。
 *
 * <h2>为什么"库里存的是密文"不算防线</h2>
 * {@code jdbc_password} 上的 {@code AESEncryptHandler} 只挂在写入侧：MP 生成的 select 需要
 * {@code @TableName(autoResultMap = true)} 才会用字段 typeHandler 解密，本实体没开这个开关
 * （所以 {@code BaseQueryTool}、{@code JSONUtils} 都自己调 {@code AESUtil.decrypt}）。
 * 于是三个只读接口把<b>库里的密文原样</b>发给前端；而 {@code datasource.aes.key} 有出厂默认值、
 * 且写在本仓库里 —— 拿到密文的人只需再读一次公开源码，等价于拿到明文口令。
 *
 * <h2>为什么回的是固定掩码而不是空值</h2>
 * 前端编辑表单把 {@code jdbcPassword} 定为必填（打包产物里 {@code jdbcPassword:[{required:!0,...}]}）。
 * 回空值意味着"管理员改任何一个别的字段都保存不了，请求根本发不出去"，那是把功能修坏；
 * 回固定 6 个星号同时满足三件事：必填校验过得去、真实值一个字节都不出去、"这一栏没被动过"可识别。
 * 第 1~4 条钉第二件事，第 5~6 条钉第三件事，第 7 条钉"真要换口令时不能被他自己的输入拦住"。
 *
 * <h2>为什么收口点在字段而不是入口</h2>
 * 普通用户建作业必须浏览数据源列表，把只读接口收归管理员会砍掉正常流程（属待拍板项 P6）。
 * 所以第 8 条钉"普通用户仍旧读得到列表，但读不到口令"。
 */
public class JobDatasourceSecretScrubTest {

    /** 与控制器里的 PASSWORD_MASK 对上：断言写死字面量，常量被改动时用例必须一起红 */
    private static final String MASK = "******";

    private JobDatasourceController controller;
    private JobDatasourceService service;

    @Before
    public void setUp() {
        controller = new JobDatasourceController();
        service = Mockito.mock(JobDatasourceService.class);
        ReflectionTestUtils.setField(controller, "jobJdbcDatasourceService", service);
        when(service.updateById(any(JobDatasource.class))).thenReturn(true);
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 与 JWTAuthorizationFilter 同形的登录态：principal 是用户名字符串，权限走 normalizeRole */
    private static void loginAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "x",
                        Collections.singletonList(new SimpleGrantedAuthority(role))));
    }

    private static JobDatasource stored(Long id, String user, String password) {
        JobDatasource ds = new JobDatasource();
        ds.setId(id);
        ds.setDatasourceName("源" + id);
        ds.setJdbcUsername(user);
        ds.setJdbcPassword(password);
        ds.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/datax_web");
        return ds;
    }

    @Test
    public void pagedReadCarriesNoRealPassword() {
        loginAs("admin", "ROLE_ADMIN");
        Page<JobDatasource> page = new Page<JobDatasource>(1, 10);
        page.setRecords(new ArrayList<JobDatasource>(
                Arrays.asList(stored(1L, "root", "p@1"), stored(2L, "app", "p@2"))));
        when(service.page(any(IPage.class), any())).thenReturn(page);

        R<IPage<JobDatasource>> result = controller.selectAll();

        assertTrue("分页读取本身要照常成功：" + result.getMsg(), result.ok());
        List<JobDatasource> records = result.getData().getRecords();
        assertEquals("记录不能少，剥的是字段不是行", 2, records.size());
        assertFalse("真实口令不得出现在响应里",
                "p@1".equals(records.get(0).getJdbcPassword()) || "p@2".equals(records.get(1).getJdbcPassword()));
        assertEquals(MASK, records.get(0).getJdbcPassword());
        assertEquals(MASK, records.get(1).getJdbcPassword());
        assertEquals("账号不是口令，编辑界面还要用", "root", records.get(0).getJdbcUsername());
        assertEquals("连接串不是口令，照常返回", "jdbc:mysql://127.0.0.1:3306/datax_web", records.get(0).getJdbcUrl());
    }

    @Test
    public void listReadCarriesNoRealPassword() {
        loginAs("admin", "ROLE_ADMIN");
        when(service.selectAllDatasource()).thenReturn(
                Arrays.asList(stored(1L, "root", "secret-1"), stored(2L, "app", "secret-2")));

        R<List<JobDatasource>> result = controller.selectAllDatasource();

        assertTrue(result.ok());
        assertEquals(2, result.getData().size());
        assertEquals(MASK, result.getData().get(0).getJdbcPassword());
        assertEquals(MASK, result.getData().get(1).getJdbcPassword());
    }

    @Test
    public void singleReadCarriesNoRealPassword() {
        loginAs("admin", "ROLE_ADMIN");
        when(service.getById(3L)).thenReturn(stored(3L, "root", "should-not-leave-server"));

        R<JobDatasource> result = controller.selectOne(3L);

        assertTrue(result.ok());
        assertEquals(MASK, result.getData().getJdbcPassword());
        assertRealPasswordAbsent(result.getData().getJdbcPassword(), "should-not-leave-server");
        assertEquals(Long.valueOf(3L), result.getData().getId());
    }

    private static void assertRealPasswordAbsent(String returned, String real) {
        assertFalse("响应里出现的必须是掩码，不能是库里那条（或其任何变形）", real.equals(returned));
    }

    /** 读接口对不存在的 id 不能把剥离动作顶成 NPE */
    @Test
    public void singleReadOfMissingIdDoesNotThrow() {
        loginAs("admin", "ROLE_ADMIN");
        when(service.getById(404L)).thenReturn(null);

        R<JobDatasource> result = controller.selectOne(404L);

        assertTrue(result.ok());
        assertNull(result.getData());
    }

    /**
     * 反向红线第 1 条：前端把读到的掩码原样回提时，必须解释成"这次不改口令"。
     * 否则 MP 与 {@code JobDatasourceMapper.xml} 的 {@code <if test="jdbcPassword!=null">}
     * 会把 6 个星号当成新口令写进库，下一次作业连库直接认证失败。
     */
    @Test
    public void resubmittedMaskKeepsTheStoredPassword() {
        loginAs("admin", "ROLE_ADMIN");
        when(service.getById(7L)).thenReturn(stored(7L, "root", "keep-me"));

        JobDatasource submitted = new JobDatasource();
        submitted.setId(7L);
        submitted.setDatasourceName("改了名字");
        submitted.setJdbcPassword(MASK);

        R<Boolean> result = controller.update(submitted);

        assertTrue("只改名称的编辑要成功：" + result.getMsg(), result.ok());
        ArgumentCaptor<JobDatasource> captor = ArgumentCaptor.forClass(JobDatasource.class);
        verify(service).updateById(captor.capture());
        assertNull("回提掩码不能被当成新口令写库", captor.getValue().getJdbcPassword());
        assertEquals("改了名字", captor.getValue().getDatasourceName());
    }

    /** 反向红线第 2 条：不带口令（脚本调用、部分字段更新）同样是不修改，不能被清空 */
    @Test
    public void blankSubmittedPasswordKeepsTheStoredOne() {
        loginAs("admin", "ROLE_ADMIN");
        when(service.getById(8L)).thenReturn(stored(8L, "root", "keep-me-too"));

        JobDatasource submitted = new JobDatasource();
        submitted.setId(8L);
        submitted.setDatasourceName("只改名字");
        submitted.setJdbcPassword("");

        R<Boolean> result = controller.update(submitted);

        assertTrue(result.ok());
        ArgumentCaptor<JobDatasource> captor = ArgumentCaptor.forClass(JobDatasource.class);
        verify(service).updateById(captor.capture());
        assertNull("空口令必须走『不修改』这一支", captor.getValue().getJdbcPassword());
    }

    /** 历史行为：旧前端回提读到的原值（改动前是密文），仍旧认成"未修改" */
    @Test
    public void echoedStoredPasswordIsTreatedAsUnchanged() {
        loginAs("admin", "ROLE_ADMIN");
        when(service.getById(9L)).thenReturn(stored(9L, "root", "cipher-text"));

        R<Boolean> result = controller.update(stored(9L, "root", "cipher-text"));

        assertTrue(result.ok());
        ArgumentCaptor<JobDatasource> captor = ArgumentCaptor.forClass(JobDatasource.class);
        verify(service).updateById(captor.capture());
        assertNull(captor.getValue().getJdbcPassword());
    }

    /** 反向红线第 3 条：管理员真的在换口令时，守卫不能拦住他 */
    @Test
    public void genuinelyNewPasswordStillGoesThrough() {
        loginAs("admin", "ROLE_ADMIN");
        when(service.getById(10L)).thenReturn(stored(10L, "root", "old"));

        R<Boolean> result = controller.update(stored(10L, "root", "brand-new"));

        assertTrue(result.ok());
        ArgumentCaptor<JobDatasource> captor = ArgumentCaptor.forClass(JobDatasource.class);
        verify(service).updateById(captor.capture());
        assertEquals("显式提交的新口令要照常落库", "brand-new", captor.getValue().getJdbcPassword());
    }

    /**
     * 普通用户仍要能浏览数据源（建作业时要选），所以这条不能变成拒绝；
     * 但"能读"不等于"读得到口令" —— 这正是本批整条设计的前提。
     */
    @Test
    public void normalUserStillSeesDatasourcesButWithoutPassword() {
        loginAs("devuser", "0");
        when(service.selectAllDatasource()).thenReturn(Collections.singletonList(stored(5L, "root", "s3cret")));

        R<List<JobDatasource>> result = controller.selectAllDatasource();

        assertTrue("普通用户浏览数据源的能力不能被砍掉：" + result.getMsg(), result.ok());
        assertEquals(1, result.getData().size());
        assertEquals("普通用户同样只拿到掩码", MASK, result.getData().get(0).getJdbcPassword());
        verify(service, never()).updateById(any(JobDatasource.class));
    }
}
