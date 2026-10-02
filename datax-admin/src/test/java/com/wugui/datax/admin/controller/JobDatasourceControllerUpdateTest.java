package com.wugui.datax.admin.controller;

import com.baomidou.mybatisplus.extension.api.R;
import com.wugui.datax.admin.entity.JobDatasource;
import com.wugui.datax.admin.service.JobDatasourceService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据源更新接口的两条空指针路径：请求体只带部分字段、id 在库里不存在。
 * 修复前两条都会 500 —— 前者是 entity.getJdbcUsername() 为 null 还拿去 equals，
 * 后者是 getById 返回 null 后直接取字段。
 */
public class JobDatasourceControllerUpdateTest {

    private JobDatasourceController controller;
    private JobDatasourceService service;

    @Before
    public void setUp() {
        controller = new JobDatasourceController();
        service = Mockito.mock(JobDatasourceService.class);
        ReflectionTestUtils.setField(controller, "jobJdbcDatasourceService", service);
        when(service.updateById(any(JobDatasource.class))).thenReturn(true);
    }

    @Test
    public void partialPayloadWithoutUsernameShouldNotThrow() {
        when(service.getById(1L)).thenReturn(datasource(1L, "root", "old-pass"));

        JobDatasource entity = new JobDatasource();
        entity.setId(1L);
        entity.setDatasourceName("本地mysql");
        entity.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/datax_web");
        // 账号和密码都不提交

        R<Boolean> result = controller.update(entity);

        assertTrue("只提交部分字段也应该更新成功：" + result.getMsg(), result.ok());
        ArgumentCaptor<JobDatasource> captor = ArgumentCaptor.forClass(JobDatasource.class);
        verify(service).updateById(captor.capture());
        assertNull("没提交的账号不能被写回实体，否则会把库里的值覆盖成 null", captor.getValue().getJdbcUsername());
    }

    @Test
    public void missingIdShouldReturnFailureInsteadOfNpe() {
        when(service.getById(999L)).thenReturn(null);

        R<Boolean> result = controller.update(datasource(999L, "root", "pass"));

        assertFalse("库里没有这条数据源时要返回失败，不能抛 NPE", result.ok());
        verify(service, never()).updateById(any(JobDatasource.class));
    }

    @Test
    public void unchangedUsernameIsStrippedBeforeUpdate() {
        when(service.getById(2L)).thenReturn(datasource(2L, "root", "old-pass"));

        R<Boolean> result = controller.update(datasource(2L, "root", "new-pass"));

        assertTrue("更新应成功：" + result.getMsg(), result.ok());
        ArgumentCaptor<JobDatasource> captor = ArgumentCaptor.forClass(JobDatasource.class);
        verify(service).updateById(captor.capture());
        assertNull("与库里一致的账号不必重新落库", captor.getValue().getJdbcUsername());
        assertEquals("改动的密码要照常写回", "new-pass", captor.getValue().getJdbcPassword());
    }

    private JobDatasource datasource(Long id, String username, String password) {
        JobDatasource ds = new JobDatasource();
        ds.setId(id);
        ds.setDatasourceName("测试库");
        ds.setJdbcUsername(username);
        ds.setJdbcPassword(password);
        ds.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/datax_web");
        return ds;
    }
}
