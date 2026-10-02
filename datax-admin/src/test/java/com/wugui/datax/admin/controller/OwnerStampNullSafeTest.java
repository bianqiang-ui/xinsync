package com.wugui.datax.admin.controller;

import com.baomidou.mybatisplus.extension.api.R;
import com.wugui.datatx.core.biz.model.ReturnT;
import com.wugui.datax.admin.entity.JobInfo;
import com.wugui.datax.admin.entity.JobProject;
import com.wugui.datax.admin.entity.JobTemplate;
import com.wugui.datax.admin.security.AccessControl;
import com.wugui.datax.admin.service.JobProjectService;
import com.wugui.datax.admin.service.JobService;
import com.wugui.datax.admin.service.JobTemplateService;
import com.wugui.datax.admin.util.JwtTokenUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次10-H：新建类接口的"归属盖章"必须在登录判据缺失时明确拒绝，而不是崩成 500。
 *
 * 批次10-D 把 BaseController#getCurrentUserId 从"缺头就抛 NoSuchElementException"改成返回 null，
 * 本意是让 AccessControl 按最小权限处理；但 add/insert 这三处是把它直接塞进
 * {@code setUserId(...)}，而 JobInfo/JobTemplate/JobProject 的 userId 是基本类型 int ——
 * null 一拆箱就是 NullPointerException。于是"最小权限"只对读路径生效，写路径换了一种方式炸：
 * 前端拿到 500，日志里是一条与业务无关的 JWT 栈，用户不知道自己没登录。
 *
 * 三条正向用例同样重要：盖章这件事不能因为加了判定就不做了，否则普通用户建的任务自己改不了
 * （denyUnlessAdminOrOwner 拿库里的 user_id 比，写成 0 就谁都不是它的属主）。
 */
public class OwnerStampNullSafeTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef-xyz-0123456789abcdef-xyz-012345";
    private static final int USER_ID = 7;

    @Before
    public void setUp() {
        JwtTokenUtils.configureSecret(SECRET);
    }

    /** 未登录的请求：没有 Authorization 头 */
    private static MockHttpServletRequest anonymous() {
        return new MockHttpServletRequest();
    }

    private static MockHttpServletRequest loggedIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(JwtTokenUtils.TOKEN_HEADER, JwtTokenUtils.TOKEN_PREFIX
                + JwtTokenUtils.createToken(USER_ID, "dev01", AccessControl.ROLE_NORMAL, false));
        return request;
    }

    // ---------------- /api/job/add ----------------

    @Test
    public void jobAddRejectsWithoutLoginInsteadOfUnboxingNull() {
        JobService jobService = Mockito.mock(JobService.class);
        JobInfoController controller = new JobInfoController();
        ReflectionTestUtils.setField(controller, "jobService", jobService);

        ReturnT<String> result = controller.add(anonymous(), new JobInfo());

        assertEquals("未登录必须是一条看得懂的拒绝，不是 500", ReturnT.FAIL_CODE, result.getCode());
        assertEquals(AccessControl.NO_LOGIN_MSG, result.getMsg());
        verify(jobService, never()).add(any(JobInfo.class));
    }

    @Test
    public void jobAddStillStampsCallerId() {
        JobService jobService = Mockito.mock(JobService.class);
        JobInfoController controller = new JobInfoController();
        ReflectionTestUtils.setField(controller, "jobService", jobService);
        when(jobService.add(any(JobInfo.class))).thenReturn(ReturnT.SUCCESS);

        JobInfo jobInfo = new JobInfo();
        assertTrue(controller.add(loggedIn(), jobInfo).getCode() == ReturnT.SUCCESS_CODE);
        assertEquals(USER_ID, jobInfo.getUserId());
        verify(jobService).add(jobInfo);
    }

    // ---------------- /api/jobTemplate/add ----------------

    @Test
    public void templateAddRejectsWithoutLoginInsteadOfUnboxingNull() {
        JobTemplateService jobTemplateService = Mockito.mock(JobTemplateService.class);
        JobTemplateController controller = new JobTemplateController();
        ReflectionTestUtils.setField(controller, "jobTemplateService", jobTemplateService);

        ReturnT<String> result = controller.add(anonymous(), new JobTemplate());

        assertEquals("未登录必须是一条看得懂的拒绝，不是 500", ReturnT.FAIL_CODE, result.getCode());
        assertEquals(AccessControl.NO_LOGIN_MSG, result.getMsg());
        verify(jobTemplateService, never()).add(any(JobTemplate.class));
    }

    /**
     * 模板的 user_id 会被 batchAdd 的 copyProperties 带进一整批新任务，
     * 模板无主 = 批量建出来的任务全都无主，谁都改不动。
     */
    @Test
    public void templateAddStillStampsCallerId() {
        JobTemplateService jobTemplateService = Mockito.mock(JobTemplateService.class);
        JobTemplateController controller = new JobTemplateController();
        ReflectionTestUtils.setField(controller, "jobTemplateService", jobTemplateService);
        when(jobTemplateService.add(any(JobTemplate.class))).thenReturn(ReturnT.SUCCESS);

        JobTemplate jobTemplate = new JobTemplate();
        assertTrue(controller.add(loggedIn(), jobTemplate).getCode() == ReturnT.SUCCESS_CODE);
        assertEquals(USER_ID, jobTemplate.getUserId());
    }

    // ---------------- POST /api/jobProject ----------------

    @Test
    public void projectInsertRejectsWithoutLoginInsteadOfUnboxingNull() {
        JobProjectService jobProjectService = Mockito.mock(JobProjectService.class);
        JobProjectController controller = new JobProjectController();
        ReflectionTestUtils.setField(controller, "jobProjectService", jobProjectService);

        R<Boolean> result = controller.insert(anonymous(), new JobProject());

        // R 判成败用 ok()（与 JobDatasourceControllerUpdateTest 同一口径），不去猜它的 code 常量
        assertFalse("未登录必须是一条看得懂的拒绝，不是 500", result.ok());
        assertEquals(AccessControl.NO_LOGIN_MSG, result.getMsg());
        verify(jobProjectService, never()).save(any(JobProject.class));
    }

    @Test
    public void projectInsertStillStampsCallerId() {
        JobProjectService jobProjectService = Mockito.mock(JobProjectService.class);
        JobProjectController controller = new JobProjectController();
        ReflectionTestUtils.setField(controller, "jobProjectService", jobProjectService);
        when(jobProjectService.save(any(JobProject.class))).thenReturn(Boolean.TRUE);

        JobProject project = new JobProject();
        assertTrue(controller.insert(loggedIn(), project).ok());
        assertEquals(USER_ID, project.getUserId());
    }
}
