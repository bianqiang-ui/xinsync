package com.wugui.datax.admin.service.impl;

import com.wugui.datatx.core.biz.model.ReturnT;
import com.wugui.datax.admin.dto.DataXBatchJsonBuildDto;
import com.wugui.datax.admin.entity.JobInfo;
import com.wugui.datax.admin.entity.JobTemplate;
import com.wugui.datax.admin.mapper.JobInfoMapper;
import com.wugui.datax.admin.mapper.JobTemplateMapper;
import com.wugui.datax.admin.service.DatasourceQueryService;
import com.wugui.datax.admin.service.DataxJsonService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量建任务时的归属落点。
 *
 * 这条是被自己的修复逼出来的：写路径开始按"管理员或本人"判归属之后，
 * batchAdd 仍从模板 copyProperties 带出 user_id（甚至不带，因为模板的属主往往不是调用者），
 * 结果就是普通用户批量建出来的一整批任务**自己改不了**。
 */
public class JobServiceBatchAddOwnerTest {

    private static final int CALLER_USER_ID = 7;
    private static final int TEMPLATE_OWNER_ID = 99;
    private static final int TEMPLATE_ID = 3;

    private JobServiceImpl service;
    private JobInfoMapper jobInfoMapper;
    private JobTemplateMapper jobTemplateMapper;

    @Before
    public void setUp() throws Exception {
        service = new JobServiceImpl();
        jobInfoMapper = Mockito.mock(JobInfoMapper.class);
        jobTemplateMapper = Mockito.mock(JobTemplateMapper.class);
        DatasourceQueryService datasourceQueryService = Mockito.mock(DatasourceQueryService.class);
        DataxJsonService dataxJsonService = Mockito.mock(DataxJsonService.class);

        ReflectionTestUtils.setField(service, "jobInfoMapper", jobInfoMapper);
        ReflectionTestUtils.setField(service, "jobTemplateMapper", jobTemplateMapper);
        ReflectionTestUtils.setField(service, "datasourceQueryService", datasourceQueryService);
        ReflectionTestUtils.setField(service, "dataxJsonService", dataxJsonService);

        when(datasourceQueryService.getColumns(anyLong(), anyString()))
                .thenReturn(Collections.singletonList("id"));
        when(dataxJsonService.buildJobJson(any())).thenReturn("{\"job\":{}}");
        when(jobInfoMapper.save(any(JobInfo.class))).thenReturn(1);
    }

    @Test
    public void everyCreatedJobBelongsToCallerNotTemplate() throws Exception {
        when(jobTemplateMapper.loadById(TEMPLATE_ID)).thenReturn(template(TEMPLATE_OWNER_ID));

        ReturnT<String> result = service.batchAdd(dto("t_order_1", "t_order_2"), CALLER_USER_ID);

        assertTrue("批量建任务应当成功：" + result.getMsg(), result.getCode() == ReturnT.SUCCESS_CODE);
        ArgumentCaptor<JobInfo> captor = ArgumentCaptor.forClass(JobInfo.class);
        verify(jobInfoMapper, times(2)).save(captor.capture());
        for (JobInfo created : captor.getAllValues()) {
            assertEquals("每张任务的归属都必须是调用者本人，不能被模板的属主带走",
                    CALLER_USER_ID, created.getUserId());
        }
    }

    /**
     * 模板不存在时以前会在第一条 copyProperties 抛 NPE：接口 500、任务一条也没建，
     * 前端只显示"系统异常"。现在要在建任何东西之前给出可定位的失败。
     */
    @Test
    public void missingTemplateFailsBeforeAnyJobIsCreated() throws Exception {
        when(jobTemplateMapper.loadById(TEMPLATE_ID)).thenReturn(null);

        ReturnT<String> result = service.batchAdd(dto("t_order_1", "t_order_2"), CALLER_USER_ID);

        assertFalse("模板不存在必须返回失败而不是抛异常", result.getCode() == ReturnT.SUCCESS_CODE);
        assertTrue("失败信息要带上查不到的 templateId：" + result.getMsg(),
                result.getMsg().contains(String.valueOf(TEMPLATE_ID)));
        verify(jobInfoMapper, never()).save(any(JobInfo.class));
    }

    private JobTemplate template(int userId) {
        JobTemplate template = new JobTemplate();
        template.setId(TEMPLATE_ID);
        template.setUserId(userId);
        template.setJobDesc("模板任务");
        return template;
    }

    private DataXBatchJsonBuildDto dto(String... readerTables) {
        DataXBatchJsonBuildDto dto = new DataXBatchJsonBuildDto();
        dto.setReaderDatasourceId(1L);
        dto.setWriterDatasourceId(2L);
        dto.setTemplateId(TEMPLATE_ID);
        dto.setReaderTables(Arrays.asList(readerTables));
        dto.setWriterTables(Arrays.asList(readerTables));
        return dto;
    }
}
