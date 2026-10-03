package com.wugui.datax.admin.security;

import com.wugui.datatx.core.biz.model.ReturnT;
import com.wugui.datatx.core.glue.GlueTypeEnum;
import com.wugui.datax.admin.controller.JobCodeController;
import com.wugui.datax.admin.controller.JobInfoController;
import com.wugui.datax.admin.controller.JobTemplateController;
import com.wugui.datax.admin.dto.DataXBatchJsonBuildDto;
import com.wugui.datax.admin.entity.JobInfo;
import com.wugui.datax.admin.entity.JobLogGlue;
import com.wugui.datax.admin.entity.JobTemplate;
import com.wugui.datax.admin.entity.JobUser;
import com.wugui.datax.admin.entity.JwtUser;
import com.wugui.datax.admin.mapper.JobInfoMapper;
import com.wugui.datax.admin.mapper.JobLogGlueMapper;
import com.wugui.datax.admin.mapper.JobTemplateMapper;
import com.wugui.datax.admin.service.DataxJsonService;
import com.wugui.datax.admin.service.DatasourceQueryService;
import com.wugui.datax.admin.service.JobService;
import com.wugui.datax.admin.service.JobTemplateService;
import com.wugui.datax.admin.service.impl.JobServiceImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GLUE 任务的管理员判定（批次10-I + 复核批次"守卫漏入口"）。
 *
 * 靶子是上一版的判定口径：{@code glue != null && glue.isScript() && !isAdmin()}。
 * 两处漏：
 *   1) GLUE_GROOVY 的 isScript 是 false，但 ExecutorBizImpl 会走
 *      GlueFactory.loadNewInstance → GroovyClassLoader.parseClass(glueSource)，
 *      在执行器 JVM 里把这段文本编译成类并跑起来 —— 按 isScript 判正好放过危害最大的一类；
 *   2) 只判了 /api/job/add、/api/job/update，而 glue 列的写入口有 5 个
 *      （还有 /jobcode/save、/api/job/batchAdd、/api/jobTemplate/add）。
 *
 * 反向红线同样有断言：BEAN（普通用户的数据同步主流程）必须照常可用，
 * 管理员对任何 glueType 都必须照常可写 —— 不能为了收口把能力关掉。
 */
public class GlueScriptAccessTest {

    private static final int OWNER_USER_ID = 7;
    private static final int TEMPLATE_ID = 3;
    private static final int JOB_ID = 42;
    private static final String GLUE_REMARK = "脚本改动说明";

    private JobInfoMapper jobInfoMapper;
    private JobLogGlueMapper jobLogGlueMapper;
    private JobCodeController jobCodeController;
    private HttpServletRequest request;

    @Before
    public void setUp() {
        jobInfoMapper = Mockito.mock(JobInfoMapper.class);
        jobLogGlueMapper = Mockito.mock(JobLogGlueMapper.class);
        request = Mockito.mock(HttpServletRequest.class);

        jobCodeController = Mockito.spy(new JobCodeController());
        ReflectionTestUtils.setField(jobCodeController, "jobInfoMapper", jobInfoMapper);
        ReflectionTestUtils.setField(jobCodeController, "jobLogGlueMapper", jobLogGlueMapper);
        doReturn(OWNER_USER_ID).when(jobCodeController).getCurrentUserId(any(HttpServletRequest.class));
        when(jobInfoMapper.update(any(JobInfo.class))).thenReturn(1);
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String username, String role) {
        JwtUser principal = new JwtUser(user(username, role));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    private static JobUser user(String username, String role) {
        JobUser u = new JobUser();
        u.setUsername(username);
        u.setPassword("hash");
        u.setRole(role);
        return u;
    }

    // ---------------------------------------------------------------- 判定本身

    /** 普通用户可以继续建/改 BEAN 型（数据同步）任务 —— 反向红线，不能把主流程关掉 */
    @Test
    public void beanTaskStaysOpenToNormalUsers() {
        loginAs("dev01", AccessControl.ROLE_NORMAL);

        assertNull(GlueScriptAccess.denyMessage(GlueTypeEnum.BEAN.name()));
        assertNull(GlueScriptAccess.denyMessage(GlueTypeEnum.BEAN.getDesc()));
    }

    /**
     * 本批的靶子：GLUE_GROOVY 的 isScript 是 false，所以"按 isScript 收口"等于没收。
     * 断言里把 isScript() 本身也钉住，是为了让"改回按 isScript 判"这种退化一眼可见。
     */
    @Test
    public void glueGroovyIsAdminOnlyEvenThoughItIsNotMarkedScript() {
        loginAs("dev01", AccessControl.ROLE_NORMAL);

        assertFalse("前提：GLUE_GROOVY 的 isScript 确实是 false（所以不能按它判）",
                GlueTypeEnum.GLUE_GROOVY.isScript());
        assertNotNull("GLUE_GROOVY 会在执行器 JVM 里编译执行，必须管理员专属",
                GlueScriptAccess.denyMessage(GlueTypeEnum.GLUE_GROOVY.name()));
        assertNotNull(GlueScriptAccess.denyMessage(GlueTypeEnum.GLUE_GROOVY.getDesc()));
    }

    /** 除 BEAN 之外的每一个枚举值都要管理员专属，desc 形态也要认 */
    @Test
    public void everyNonBeanGlueTypeIsAdminOnly() {
        loginAs("dev01", AccessControl.ROLE_NORMAL);

        for (GlueTypeEnum glue : GlueTypeEnum.values()) {
            if (glue == GlueTypeEnum.BEAN) {
                continue;
            }
            assertNotNull(glue.name() + " 必须收归管理员",
                    GlueScriptAccess.denyMessage(glue.name()));
            assertNotNull(glue.getDesc() + "（desc 形态）同样要收归管理员",
                    GlueScriptAccess.denyMessage(glue.getDesc()));
            assertNotNull(glue.name() + " 带前后空格仍是同一个类型",
                    GlueScriptAccess.denyMessage("  " + glue.name() + " "));
        }
    }

    /**
     * 枚举里没有的串：上一版 `glue != null` 会直接放行，而 /api/job/update 这条路径
     * 并没有 add 那样的一致性校验，任意 glueType 都能落库。口径改成白名单之后它不是 BEAN，
     * 所以对普通用户拒绝。（执行器侧对未知类型会回 "glueType[..] is not valid"，
     * 因此这条判的是"库里的配置不被普通用户写成不可执行/将来可被解释的形状"。）
     */
    @Test
    public void unknownGlueTypeIsAdminOnlyToo() {
        loginAs("dev01", AccessControl.ROLE_NORMAL);

        assertNotNull(GlueScriptAccess.denyMessage("GLUE_TOMCAT"));
        assertNull("识别不了也不该被误判成某个枚举", GlueScriptAccess.parse("GLUE_TOMCAT"));
    }

    /** 空白值不由本判定负责：保持既有行为，由 service 的校验拒绝，避免把 500 换成一句看不懂的话 */
    @Test
    public void blankGlueTypeIsLeftToExistingValidation() {
        loginAs("dev01", AccessControl.ROLE_NORMAL);

        assertNull(GlueScriptAccess.denyMessage(null));
        assertNull(GlueScriptAccess.denyMessage(""));
        assertNull(GlueScriptAccess.denyMessage("   "));
    }

    /** 反向红线：管理员对任何形态（含不可识别的串）都不能被这条判定挡住 */
    @Test
    public void adminIsNeverDeniedByThisGuard() {
        loginAs("admin", AccessControl.ROLE_ADMIN);

        for (GlueTypeEnum glue : GlueTypeEnum.values()) {
            assertNull(glue.name() + " 管理员必须照常可写", GlueScriptAccess.denyMessage(glue.name()));
        }
        assertNull(GlueScriptAccess.denyMessage("GLUE_TOMCAT"));
    }

    /** 未登录（SecurityContext 里没有身份）不能因为"取不到角色"而放行 */
    @Test
    public void anonymousIsNotTreatedAsAdmin() {
        SecurityContextHolder.clearContext();

        assertNotNull(GlueScriptAccess.denyMessage(GlueTypeEnum.GLUE_SHELL.name()));
    }

    // ---------------------------------------------------------------- 五个写入口

    /** /api/job/add：普通用户建 GLUE_SHELL 任务必须当场拒，且不落到 service */
    @Test
    public void jobAddRejectsScriptTypeBeforeTouchingService() {
        loginAs("dev01", AccessControl.ROLE_NORMAL);
        JobService jobService = Mockito.mock(JobService.class);
        JobInfoController controller = new JobInfoController();
        ReflectionTestUtils.setField(controller, "jobService", jobService);
        ReflectionTestUtils.setField(controller, "jobInfoMapper", jobInfoMapper);

        JobInfo scriptJob = new JobInfo();
        scriptJob.setGlueType(GlueTypeEnum.GLUE_SHELL.name());
        scriptJob.setGlueSource("#!/bin/bash\nid");

        ReturnT<String> result = controller.add(request, scriptJob);

        assertNotNull(result);
        assertEquals(ReturnT.FAIL_CODE, result.getCode());
        assertTrue("拒绝文案要能被前端直接展示：" + result.getMsg(),
                result.getMsg().contains("管理员"));
        verify(jobService, never()).add(any(JobInfo.class));
    }

    /** /api/job/update：把 BEAN 任务改成 GLUE_GROOVY 是绕过 add 的同一条路 */
    @Test
    public void jobUpdateRejectsGroovyDowngradeBeforeTouchingService() {
        loginAs("dev01", AccessControl.ROLE_NORMAL);
        JobService jobService = Mockito.mock(JobService.class);
        JobInfoController controller = new JobInfoController();
        ReflectionTestUtils.setField(controller, "jobService", jobService);
        ReflectionTestUtils.setField(controller, "jobInfoMapper", jobInfoMapper);

        JobInfo job = new JobInfo();
        job.setId(JOB_ID);
        job.setGlueType(GlueTypeEnum.GLUE_GROOVY.name());
        job.setGlueSource("class J extends com.wugui.executor.glue.IMJobHandler { ... }");

        ReturnT<String> result = controller.update(request, job);

        assertEquals(ReturnT.FAIL_CODE, result.getCode());
        // 必须是被 GLUE 判定拒的，不是"顺手被后面的归属/存在性检查拒掉"——
        // 只断言 FAIL_CODE 的话，把守卫摘掉这条照样绿（假绿通道）。
        assertTrue("拒绝原因必须是管理员权限，而不是任务不存在：" + result.getMsg(),
                result.getMsg().contains("管理员"));
        verify(jobInfoMapper, never()).loadById(anyInt());
        verify(jobService, never()).update(any(JobInfo.class));
    }

    /** 正向：普通用户改自己的 BEAN 任务照常通过（判定不能顺手把主流程关了） */
    @Test
    public void jobUpdateStillAcceptsBeanFromOwner() {
        loginAs("dev01", AccessControl.ROLE_NORMAL);
        JobService jobService = Mockito.mock(JobService.class);
        // spy：BEAN 会走过守卫，接下来要从"当前登录用户 id"这一步拿到属主，
        // 而单元测试里的 mock request 没有 Authorization 头，只能把这一步桩掉
        JobInfoController controller = Mockito.spy(new JobInfoController());
        ReflectionTestUtils.setField(controller, "jobService", jobService);
        ReflectionTestUtils.setField(controller, "jobInfoMapper", jobInfoMapper);
        when(jobInfoMapper.loadById(JOB_ID)).thenReturn(storedJob(GlueTypeEnum.BEAN.name(), OWNER_USER_ID));
        doReturn(OWNER_USER_ID).when(controller).getCurrentUserId(any(HttpServletRequest.class));
        when(jobService.update(any(JobInfo.class))).thenReturn(ReturnT.SUCCESS);

        JobInfo job = new JobInfo();
        job.setId(JOB_ID);
        job.setGlueType(GlueTypeEnum.BEAN.name());

        ReturnT<String> result = controller.update(request, job);

        assertEquals("BEAN 任务的更新不能被这条判定拦住", ReturnT.SUCCESS_CODE, result.getCode());
        verify(jobService).update(any(JobInfo.class));
    }

    /**
     * /jobcode/save 的请求体里**没有 glueType**（前端只传 id/glueSource/glueRemark），
     * 所以类型只能取库里那一行。这条测的就是"取库里的行"这件事：
     * 库里是 GLUE_SHELL 且调用者是属主本人，也要拒。
     */
    @Test
    public void jobCodeSaveDeniesOwnerOnStoredScriptJob() {
        loginAs("dev01", AccessControl.ROLE_NORMAL);
        when(jobInfoMapper.loadById(JOB_ID)).thenReturn(storedJob(GlueTypeEnum.GLUE_SHELL.name(), OWNER_USER_ID));

        ReturnT<String> result = jobCodeController.save(request, null, JOB_ID, "rm -rf /", GLUE_REMARK);

        assertEquals(ReturnT.FAIL_CODE, result.getCode());
        assertTrue("要说明是管理员权限问题：" + result.getMsg(), result.getMsg().contains("管理员"));
        verify(jobInfoMapper, never()).update(any(JobInfo.class));
        verify(jobLogGlueMapper, never()).save(any(JobLogGlue.class));
    }

    /** 同一条路径的 Groovy 形态：库里是 GLUE_GROOVY（isScript=false）也必须拒 */
    @Test
    public void jobCodeSaveDeniesStoredGroovyJob() {
        loginAs("dev01", AccessControl.ROLE_NORMAL);
        when(jobInfoMapper.loadById(JOB_ID)).thenReturn(storedJob(GlueTypeEnum.GLUE_GROOVY.name(), OWNER_USER_ID));

        ReturnT<String> result = jobCodeController.save(request, null, JOB_ID, "groovy code", GLUE_REMARK);

        assertEquals(ReturnT.FAIL_CODE, result.getCode());
        verify(jobInfoMapper, never()).update(any(JobInfo.class));
    }

    /** 反向红线：BEAN 任务的代码页保存照旧是"管理员或属主"，普通属主不能被新规挡住 */
    @Test
    public void jobCodeSaveStillAcceptsOwnerOnBeanJob() {
        loginAs("dev01", AccessControl.ROLE_NORMAL);
        when(jobInfoMapper.loadById(JOB_ID)).thenReturn(storedJob(GlueTypeEnum.BEAN.name(), OWNER_USER_ID));

        ReturnT<String> result = jobCodeController.save(request, null, JOB_ID,
                "{\"job\":{\"content\":[]}}", GLUE_REMARK);

        assertEquals("BEAN 任务的 /jobcode/save 必须照常可用", ReturnT.SUCCESS_CODE, result.getCode());
        verify(jobInfoMapper).update(any(JobInfo.class));
    }

    /** 管理员对脚本型任务的 /jobcode/save 照常可写（只加判定、不收回能力） */
    @Test
    public void jobCodeSaveAllowsAdminOnScriptJob() {
        loginAs("admin", AccessControl.ROLE_ADMIN);
        when(jobInfoMapper.loadById(JOB_ID)).thenReturn(storedJob(GlueTypeEnum.GLUE_SHELL.name(), OWNER_USER_ID));

        ReturnT<String> result = jobCodeController.save(request, null, JOB_ID, "echo hi", GLUE_REMARK);

        assertEquals(ReturnT.SUCCESS_CODE, result.getCode());
        verify(jobInfoMapper).update(any(JobInfo.class));
    }

    /** /api/job/batchAdd：模板的 glueType 会被 copyProperties 原样拷进每个新建任务 */
    @Test
    public void batchAddDeniesScriptTemplateForNormalUser() throws Exception {
        loginAs("dev01", AccessControl.ROLE_NORMAL);
        JobServiceImpl service = batchAddService(GlueTypeEnum.GLUE_SHELL.name());

        ReturnT<String> result = service.batchAdd(batchDto(), OWNER_USER_ID);

        assertEquals(ReturnT.FAIL_CODE, result.getCode());
        assertTrue("拒绝文案要说明需要管理员：" + result.getMsg(), result.getMsg().contains("管理员"));
    }

    /** 同一条路径：BEAN 模板照常批量（批次9 的归属落点不能被这次改动带坏） */
    @Test
    public void batchAddStillAcceptsBeanTemplate() throws Exception {
        loginAs("dev01", AccessControl.ROLE_NORMAL);
        JobServiceImpl service = batchAddService(GlueTypeEnum.BEAN.name());

        ReturnT<String> result = service.batchAdd(batchDto(), OWNER_USER_ID);

        assertEquals("BEAN 模板的批量建任务必须照常可用：" + result.getMsg(),
                ReturnT.SUCCESS_CODE, result.getCode());
    }

    /** /api/jobTemplate/add：模板页只提供 BEAN，所以这条判定不影响正常用法，但必须挡住 API 直调 */
    @Test
    public void templateAddDeniesScriptTemplateForNormalUser() {
        loginAs("dev01", AccessControl.ROLE_NORMAL);
        JobTemplateService jobTemplateService = Mockito.mock(JobTemplateService.class);
        JobTemplateController controller = new JobTemplateController();
        ReflectionTestUtils.setField(controller, "jobTemplateService", jobTemplateService);
        ReflectionTestUtils.setField(controller, "jobTemplateMapper", Mockito.mock(JobTemplateMapper.class));

        JobTemplate template = new JobTemplate();
        template.setGlueType(GlueTypeEnum.GLUE_NODEJS.name());
        template.setGlueSource("require('child_process').exec('id')");

        ReturnT<String> result = controller.add(request, template);

        assertEquals(ReturnT.FAIL_CODE, result.getCode());
        assertTrue("必须是 GLUE 权限判定拒的，不是[未识别到登录用户]那种拒法：" + result.getMsg(),
                result.getMsg().contains("管理员"));
        verify(jobTemplateService, never()).add(any(JobTemplate.class));
    }

    /** 反向红线：管理员可以建脚本型模板（出厂演示与既有使用方式不能被砍掉） */
    @Test
    public void templateAddStillAcceptsAdmin() {
        loginAs("admin", AccessControl.ROLE_ADMIN);
        JobTemplateService jobTemplateService = Mockito.mock(JobTemplateService.class);
        JobTemplateController controller = Mockito.spy(new JobTemplateController());
        ReflectionTestUtils.setField(controller, "jobTemplateService", jobTemplateService);
        ReflectionTestUtils.setField(controller, "jobTemplateMapper", Mockito.mock(JobTemplateMapper.class));
        doReturn(OWNER_USER_ID).when(controller).getCurrentUserId(any(HttpServletRequest.class));
        when(jobTemplateService.add(any(JobTemplate.class))).thenReturn(ReturnT.SUCCESS);

        JobTemplate template = new JobTemplate();
        template.setGlueType(GlueTypeEnum.GLUE_SHELL.name());

        ReturnT<String> result = controller.add(request, template);

        assertEquals(ReturnT.SUCCESS_CODE, result.getCode());
        verify(jobTemplateService).add(any(JobTemplate.class));
    }

    // ---------------------------------------------------------------- 辅助

    private static JobInfo storedJob(String glueType, int ownerUserId) {
        JobInfo job = new JobInfo();
        job.setId(JOB_ID);
        job.setUserId(ownerUserId);
        job.setGlueType(glueType);
        job.setGlueSource("echo keep");
        return job;
    }

    private JobServiceImpl batchAddService(String templateGlueType) throws Exception {
        JobServiceImpl service = new JobServiceImpl();
        JobTemplateMapper jobTemplateMapper = Mockito.mock(JobTemplateMapper.class);
        DatasourceQueryService datasourceQueryService = Mockito.mock(DatasourceQueryService.class);
        DataxJsonService dataxJsonService = Mockito.mock(DataxJsonService.class);
        ReflectionTestUtils.setField(service, "jobInfoMapper", jobInfoMapper);
        ReflectionTestUtils.setField(service, "jobTemplateMapper", jobTemplateMapper);
        ReflectionTestUtils.setField(service, "datasourceQueryService", datasourceQueryService);
        ReflectionTestUtils.setField(service, "dataxJsonService", dataxJsonService);

        JobTemplate template = new JobTemplate();
        template.setId(TEMPLATE_ID);
        template.setUserId(99);
        template.setJobDesc("模板任务");
        template.setGlueType(templateGlueType);
        when(jobTemplateMapper.loadById(TEMPLATE_ID)).thenReturn(template);
        when(jobInfoMapper.save(any(JobInfo.class))).thenReturn(1);
        when(datasourceQueryService.getColumns(anyLong(), anyString()))
                .thenReturn(Collections.singletonList("id"));
        when(dataxJsonService.buildJobJson(any())).thenReturn("{\"job\":{}}");
        return service;
    }

    private static DataXBatchJsonBuildDto batchDto() {
        DataXBatchJsonBuildDto dto = new DataXBatchJsonBuildDto();
        dto.setReaderDatasourceId(1L);
        dto.setWriterDatasourceId(2L);
        dto.setTemplateId(TEMPLATE_ID);
        dto.setReaderTables(Collections.singletonList("t_order"));
        dto.setWriterTables(Collections.singletonList("t_order"));
        return dto;
    }

    /** jobInfoMapper 在 batchAdd 正向用例里必须真被调用到，避免"其实没建"也被断言成通过 */
    @Test
    public void beanTemplateBatchAddReallySavesRows() throws Exception {
        loginAs("dev01", AccessControl.ROLE_NORMAL);
        JobServiceImpl service = batchAddService(GlueTypeEnum.BEAN.name());

        assertEquals(ReturnT.SUCCESS_CODE, service.batchAdd(batchDto(), OWNER_USER_ID).getCode());
        verify(jobInfoMapper, Mockito.times(1)).save(any(JobInfo.class));
        verify(jobInfoMapper, never()).loadById(anyInt());
    }
}
