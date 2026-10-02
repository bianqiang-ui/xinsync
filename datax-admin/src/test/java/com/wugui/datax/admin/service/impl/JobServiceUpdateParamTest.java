package com.wugui.datax.admin.service.impl;

import com.wugui.datatx.core.biz.model.ReturnT;
import com.wugui.datatx.core.enums.IncrementTypeEnum;
import com.wugui.datax.admin.entity.JobGroup;
import com.wugui.datax.admin.entity.JobInfo;
import com.wugui.datax.admin.mapper.JobGroupMapper;
import com.wugui.datax.admin.mapper.JobInfoMapper;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次10-H：改任务（/api/job/update）这一侧的作业参数处理。
 *
 * update 与 add 是两条独立的代码路径，历史上它们并不同步：
 *   1) 日期格式这一栏在 update 上没有任何校验，而它会被喂给 SimpleDateFormat，
 *      产出再拼进 -p"..."（datax.py 收尾是 Popen(shell=True)）—— 反引号写在这一栏就是命令注入；
 *   2) update 里那段"空白就退回 Timestamp"的归一化写在了 BeanUtils.copyProperties 之后，
 *      改的是源对象 jobInfo，落库的是 exists_jobInfo，等于改了个没用的对象；
 *   3) 判哪几个字段没跟着 incrementType 走，用主键增量的任务会因为 partition_info 里
 *      一段早就没人读的残留值被拒 —— 守卫过宽到把存量任务改不动。
 */
public class JobServiceUpdateParamTest {

    private static final int JOB_ID = 11;
    private static final int USER_ID = 7;
    private static final int GROUP_ID = 1;
    private static final Integer TIME = Integer.valueOf(IncrementTypeEnum.TIME.getCode());
    private static final Integer ID = Integer.valueOf(IncrementTypeEnum.ID.getCode());
    private static final Integer PARTITION = Integer.valueOf(IncrementTypeEnum.PARTITION.getCode());

    private JobServiceImpl service;
    private JobInfoMapper jobInfoMapper;

    @Before
    public void setUp() {
        service = new JobServiceImpl();
        jobInfoMapper = Mockito.mock(JobInfoMapper.class);
        JobGroupMapper jobGroupMapper = Mockito.mock(JobGroupMapper.class);

        ReflectionTestUtils.setField(service, "jobInfoMapper", jobInfoMapper);
        ReflectionTestUtils.setField(service, "jobGroupMapper", jobGroupMapper);

        when(jobGroupMapper.load(anyInt())).thenReturn(new JobGroup());
        // triggerStatus 保持 0：不为 1 时 update 不会去算下一次触发时间，用例只关心参数处理
        when(jobInfoMapper.loadById(JOB_ID)).thenReturn(exists());
        when(jobInfoMapper.update(any(JobInfo.class))).thenReturn(1);
    }

    private static JobInfo exists() {
        JobInfo exists = new JobInfo();
        exists.setId(JOB_ID);
        exists.setUserId(USER_ID);
        exists.setJobDesc("存量任务");
        exists.setTriggerStatus(0);
        return exists;
    }

    /** 一条字段齐全、能通过 update 全部前置校验的请求体 */
    private static JobInfo request(Integer incrementType, String replaceParam,
                                   String replaceParamType, String partitionInfo) {
        JobInfo jobInfo = new JobInfo();
        jobInfo.setId(JOB_ID);
        jobInfo.setUserId(USER_ID);
        jobInfo.setJobDesc("每日抽单");
        jobInfo.setJobGroup(GROUP_ID);
        jobInfo.setProjectId(1);
        jobInfo.setJobCron("0 0 2 * * ?");
        jobInfo.setGlueType("BEAN");
        jobInfo.setJobJson("{\"job\":{\"content\":[]}}");
        jobInfo.setExecutorHandler("dataxJobHandler");
        jobInfo.setExecutorRouteStrategy("FIRST");
        jobInfo.setExecutorBlockStrategy("SERIAL_EXECUTION");
        jobInfo.setIncrementType(incrementType == null ? 0 : incrementType.intValue());
        jobInfo.setReplaceParam(replaceParam);
        jobInfo.setReplaceParamType(replaceParamType);
        jobInfo.setPartitionInfo(partitionInfo);
        return jobInfo;
    }

    private JobInfo savedOnce() {
        ArgumentCaptor<JobInfo> captor = ArgumentCaptor.forClass(JobInfo.class);
        verify(jobInfoMapper).update(captor.capture());
        return captor.getValue();
    }

    @Test
    public void blankTimeFormatIsNormalizedBeforeTheCopySoItReachesTheDatabase() {
        ReturnT<String> result = service.update(request(TIME, "-Dstart=%s", null, null));

        assertTrue("改任务本应成功：" + result.getMsg(), result.getCode() == ReturnT.SUCCESS_CODE);
        assertEquals("归一化必须落在入库的那一行上：这段历史上排在 copyProperties 之后，改了个没用的对象",
                "Timestamp", savedOnce().getReplaceParamType());
    }

    /**
     * update 不照抄 add 的"不在下拉列表就降级成 Timestamp"。
     *
     * add 那条白名单会把 yyyy-MM-dd HH:mm:ss 这种合法写法静默换成时间戳语义，用户的 WHERE
     * 条件跟着变味；判掉注入和非法样式之后，剩下的都是 SimpleDateFormat 认的合法样式，
     * 原样保留才对得上用户填的东西。这条钉住这个取舍，免得下次"顺手同步一下"。
     */
    @Test
    public void legitimateIsoTimeFormatIsKeptAsIs() {
        ReturnT<String> result = service.update(request(TIME, "-Dstart=%s", "yyyy-MM-dd HH:mm:ss", null));

        assertTrue("合法的日期样式不该被换掉：" + result.getMsg(), result.getCode() == ReturnT.SUCCESS_CODE);
        assertEquals("yyyy-MM-dd HH:mm:ss", savedOnce().getReplaceParamType());
    }

    @Test
    public void shellPayloadInTimeFormatIsRefusedAndNothingIsWritten() {
        // 反引号不是样式字母，会被 sdf.format() 当字面量带进 -p"..."
        ReturnT<String> result = service.update(request(TIME, "-Dstart=%s", "yyyy`touch /tmp/pwned`MM", null));

        assertNotEquals("日期格式这一栏是注入点，必须拒收", ReturnT.SUCCESS_CODE, result.getCode());
        assertTrue(result.getMsg(), result.getMsg().contains("增量时间格式"));
        verify(jobInfoMapper, never()).update(any(JobInfo.class));
    }

    @Test
    public void unparseableTimeFormatIsRefusedAndNothingIsWritten() {
        ReturnT<String> result = service.update(request(TIME, "-Dstart=%s", "yyyy-MM-dd-tt", null));

        assertNotEquals(ReturnT.SUCCESS_CODE, result.getCode());
        assertTrue(result.getMsg(), result.getMsg().contains("增量时间格式"));
        verify(jobInfoMapper, never()).update(any(JobInfo.class));
    }

    @Test
    public void partitionPayloadIsRefusedOnlyWhenTheJobActuallyUsesPartition() {
        ReturnT<String> result = service.update(request(PARTITION, null, null, "dt,-1,yyyy`id`MM"));

        assertNotEquals(ReturnT.SUCCESS_CODE, result.getCode());
        assertTrue(result.getMsg(), result.getMsg().contains("分区信息的日期格式"));
        verify(jobInfoMapper, never()).update(any(JobInfo.class));
    }

    /**
     * 用自增主键的任务，partition_info 里往往还留着早先按 HIVE 分区配置时写下的值。
     * 这种残留值此时一段都不进命令行，拿它拒任务等于把存量任务锁死。
     */
    @Test
    public void stalePartitionInfoDoesNotBlockAnIdIncrementJob() {
        ReturnT<String> result = service.update(request(ID, "-Did>=%s and id<=%s", null, "dt,-1,timestamp"));

        assertTrue("主键增量任务不该被不相干的分区残留值拒掉：" + result.getMsg(),
                result.getCode() == ReturnT.SUCCESS_CODE);
        assertEquals("-Did>=%s and id<=%s", savedOnce().getReplaceParam());
    }

    /** JVM 参数与增量类型无关，任何时候都要判 */
    @Test
    public void jvmPayloadIsRefusedWhateverTheIncrementTypeIs() {
        JobInfo jobInfo = request(ID, "-Did>=%s", null, null);
        jobInfo.setJvmParam("-Xmx1g `id`");

        ReturnT<String> result = service.update(jobInfo);

        assertNotEquals(ReturnT.SUCCESS_CODE, result.getCode());
        assertTrue(result.getMsg(), result.getMsg().contains("JVM 参数"));
        verify(jobInfoMapper, never()).update(any(JobInfo.class));
    }
}
