package com.wugui.datax.admin.controller;

import com.wugui.datatx.core.biz.model.LogResult;
import com.wugui.datatx.core.biz.model.ReturnT;
import com.wugui.datax.admin.entity.JobInfo;
import com.wugui.datax.admin.entity.JobLog;
import com.wugui.datax.admin.mapper.JobInfoMapper;
import com.wugui.datax.admin.mapper.JobLogMapper;
import com.wugui.datax.admin.security.AccessControl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Date;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * 运行日志面上的归属判定。
 *
 * 上一轮把 executorAddress / processId 改成"只认库里那条执行记录"，挡住的是 SSRF 和任意 PID；
 * 复核点出剩下的那一半：**谁能按 logId 调**。日志正文里带着目标库连接串、SQL 和数据样本，
 * kill 会打断别人正在写的目标表、留下一半数据 —— 所以两个口子都必须"管理员或本人"。
 */
public class JobLogControllerOwnershipTest {

    private static final long LOG_ID = 501L;
    private static final int JOB_ID = 66;
    private static final int OWNER_USER_ID = 7;
    private static final int OTHER_USER_ID = 8;
    private static final String EXECUTOR_ADDRESS = "http://127.0.0.1:1/";

    private JobLogController controller;
    private JobLogMapper jobLogMapper;
    private JobInfoMapper jobInfoMapper;
    private HttpServletRequest request;

    @Before
    public void setUp() throws Exception {
        // spy 而不是 new：getCurrentUserId 要从 JWT 头里解 id，这里只关心"判的是哪个 id"，
        // 把取 id 这一步桩掉，归属判定本身仍然是真代码在跑。
        controller = Mockito.spy(new JobLogController());
        jobLogMapper = Mockito.mock(JobLogMapper.class);
        jobInfoMapper = Mockito.mock(JobInfoMapper.class);
        request = Mockito.mock(HttpServletRequest.class);

        ReflectionTestUtils.setField(controller, "jobLogMapper", jobLogMapper);
        ReflectionTestUtils.setField(controller, "jobInfoMapper", jobInfoMapper);

        when(jobLogMapper.load(LOG_ID)).thenReturn(storedLog());
        when(jobInfoMapper.loadById(JOB_ID)).thenReturn(jobInfo(OWNER_USER_ID));
        doReturn(OTHER_USER_ID).when(controller).getCurrentUserId(any(HttpServletRequest.class));

        SecurityContextHolder.clearContext();
    }

    @After
    public void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void normalUserCannotKillOthersJob() throws Exception {
        loginAs("dev01", AccessControl.ROLE_NORMAL);

        ReturnT<String> result = controller.killJob(request, bodyLog());

        assertTrue("别人的执行记录不得下发 kill：" + result.getMsg(), isOwnershipDeny(result.getMsg()));
    }

    @Test
    public void normalUserCannotReadOthersLogDetail() throws Exception {
        loginAs("dev01", AccessControl.ROLE_NORMAL);

        ReturnT<LogResult> result = controller.logDetailCat(request, EXECUTOR_ADDRESS, 0L, LOG_ID, 1);

        assertTrue("别人的运行日志正文（含目标库连接串与数据样本）不得被读取：" + result.getMsg(),
                isOwnershipDeny(result.getMsg()));
    }

    /**
     * 归属判定必须排在"取库里那行"之后、"发 RPC"之前：
     * 任务行查不到时要说清是任务没了，而不是带着空属主继续往下走。
     */
    @Test
    public void missingJobInfoFailsWithReadableMessageInsteadOfPassing() throws Exception {
        loginAs("dev01", AccessControl.ROLE_NORMAL);
        when(jobInfoMapper.loadById(anyInt())).thenReturn(null);

        ReturnT<String> result = controller.killJob(request, bodyLog());

        assertFalse("任务行缺失必须返回失败", result.getCode() == ReturnT.SUCCESS_CODE);
        assertFalse("不能把归属拒绝当成'任务不存在'：" + result.getMsg(), isOwnershipDeny(result.getMsg()));
    }

    /**
     * 正向用例只断言"没被归属判定拦在门口"：放行之后要发 RPC，而单元测试没有 Spring 上下文，
     * {@code JobScheduler.getExecutorBiz} 会先抛 NPE —— 这个 NPE 恰好证明代码走完了判定、进了下一步。
     */
    @Test
    public void ownerIsNotAllowedAtTheDoor() throws Exception {
        loginAs("dev07", AccessControl.ROLE_NORMAL);
        doReturn(OWNER_USER_ID).when(controller).getCurrentUserId(any(HttpServletRequest.class));

        ReturnT<LogResult> result = controller.logDetailCat(request, EXECUTOR_ADDRESS, 0L, LOG_ID, 1);

        assertFalse("本人访问自己的执行记录不能被归属判定拦下：" + result.getMsg(),
                isOwnershipDeny(result.getMsg()));
    }

    @Test
    public void adminMayKillOthersJob() throws Exception {
        loginAs("admin", AccessControl.ROLE_ADMIN);

        ReturnT<LogResult> result = controller.logDetailCat(request, EXECUTOR_ADDRESS, 0L, LOG_ID, 1);

        assertFalse("管理员代管不能被归属判定拦下：" + result.getMsg(), isOwnershipDeny(result.getMsg()));
    }

    private boolean isOwnershipDeny(String msg) {
        return msg != null && msg.contains("只能操作本人的资源");
    }

    private void loginAs(String username, String role) {
        User principal = new User(username, "n/a",
                Collections.singletonList(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    private JobLog storedLog() {
        JobLog log = new JobLog();
        log.setId(LOG_ID);
        log.setJobId(JOB_ID);
        log.setExecutorAddress(EXECUTOR_ADDRESS);
        log.setProcessId("4242");
        log.setTriggerTime(new Date());
        log.setTriggerCode(ReturnT.SUCCESS_CODE);
        return log;
    }

    /** 请求体只带 id：地址与 PID 由调用方给的话，前一轮的 SSRF 收口就白做了 */
    private JobLog bodyLog() {
        JobLog log = new JobLog();
        log.setId(LOG_ID);
        log.setExecutorAddress("http://attacker.invalid:9999/");
        log.setProcessId("1");
        return log;
    }

    private JobInfo jobInfo(int userId) {
        JobInfo info = new JobInfo();
        info.setId(JOB_ID);
        info.setUserId(userId);
        return info;
    }
}
