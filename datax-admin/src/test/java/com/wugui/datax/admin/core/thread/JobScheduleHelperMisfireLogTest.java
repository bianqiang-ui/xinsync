package com.wugui.datax.admin.core.thread;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wugui.datax.admin.entity.JobInfo;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

/**
 * 坏 cron 任务的 misfire 日志限流。
 *
 * 场景是社区里最容易长期存在的一种：任务 cron 非法、trigger_next_time 又落在 5 秒之前，
 * 于是每个扫描周期都会重新撞进同一个分支。修复前每次一条 WARN，一个坏任务每小时能写七百多条；
 * 修复后同一类日志按 jobId 限流，五个连续周期只留一条。
 */
public class JobScheduleHelperMisfireLogTest {

    @Test
    public void fiveConsecutiveMisfireCyclesShouldLogOneWarn() throws Exception {
        JobInfo job = new JobInfo();
        job.setId(70001);
        // quartz 的 CronExpression 只接受 6~7 段，5 段 unix 写法会抛 ParseException，next 时间原地不动
        job.setJobCron("0/5 * * * *");
        job.setTriggerStatus(1);
        long now = System.currentTimeMillis();
        job.setTriggerNextTime(now - 60000L);

        Logger logger = (Logger) LoggerFactory.getLogger(JobScheduleHelper.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Method handle = JobScheduleHelper.class
                    .getDeclaredMethod("handleScheduleItem", JobInfo.class, long.class);
            handle.setAccessible(true);

            int cycles = 5;
            int thrown = 0;
            for (int i = 0; i < cycles; i++) {
                try {
                    handle.invoke(JobScheduleHelper.getInstance(), job, System.currentTimeMillis());
                } catch (InvocationTargetException e) {
                    // 非法 cron 必然抛出，这正是"下一轮还会再撞一次"的前提
                    thrown++;
                }
            }

            assertEquals("每个周期都应当因为非法 cron 抛异常（否则不存在刷屏）", cycles, thrown);

            long misfireWarns = appender.list.stream()
                    .filter(e -> Level.WARN.equals(e.getLevel()))
                    .filter(e -> e.getFormattedMessage().contains("schedule misfire"))
                    .count();
            assertEquals("5 个扫描周期只应留 1 条 misfire WARN", 1L, misfireWarns);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
