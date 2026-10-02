package com.wugui.datax.admin.core.thread;

import com.wugui.datax.admin.core.conf.JobAdminConfig;
import com.wugui.datax.admin.core.cron.CronExpression;
import com.wugui.datax.admin.core.trigger.TriggerTypeEnum;
import com.wugui.datax.admin.entity.JobInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * @author xuxueli 2019-05-21
 */
public class JobScheduleHelper {
    private static Logger logger = LoggerFactory.getLogger(JobScheduleHelper.class);

    private static JobScheduleHelper instance = new JobScheduleHelper();

    public static JobScheduleHelper getInstance() {
        return instance;
    }

    public static final long PRE_READ_MS = 5000;    // pre read

    private static final long ITEM_ERROR_LOG_INTERVAL_MS = 5 * 60 * 1000L;

    private static final long MISFIRE_LOG_INTERVAL_MS = 5 * 60 * 1000L;

    private Thread scheduleThread;
    private Thread ringThread;
    private volatile boolean scheduleThreadToStop = false;
    private volatile boolean ringThreadToStop = false;
    private volatile static Map<Integer, List<Integer>> ringData = new ConcurrentHashMap<>();
    private final Map<Integer, Long> itemErrorLogTime = new ConcurrentHashMap<>();
    private final Map<Integer, Long> misfireLogTime = new ConcurrentHashMap<>();

    public void start() {

        // schedule thread
        scheduleThread = new Thread(new Runnable() {
            @Override
            public void run() {

                try {
                    TimeUnit.MILLISECONDS.sleep(5000 - System.currentTimeMillis() % 1000);
                } catch (InterruptedException e) {
                    if (!scheduleThreadToStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
                logger.info(">>>>>>>>> init datax-web admin scheduler success.");

                // pre-read count: treadpool-size * trigger-qps (each trigger cost 50ms, qps = 1000/50 = 20)
                int preReadCount = (JobAdminConfig.getAdminConfig().getTriggerPoolFastMax() + JobAdminConfig.getAdminConfig().getTriggerPoolSlowMax()) * 20;

                while (!scheduleThreadToStop) {

                    // Scan Job
                    long start = System.currentTimeMillis();

                    Connection conn = null;
                    Boolean connAutoCommit = null;
                    PreparedStatement preparedStatement = null;

                    boolean preReadSuc = true;
                    try {

                        conn = JobAdminConfig.getAdminConfig().getDataSource().getConnection();
                        connAutoCommit = conn.getAutoCommit();
                        conn.setAutoCommit(false);

                        preparedStatement = conn.prepareStatement("select * from job_lock where lock_name = 'schedule_lock' for update");
                        preparedStatement.execute();

                        // tx start

                        // 1、pre read
                        long nowTime = System.currentTimeMillis();
                        List<JobInfo> scheduleList = JobAdminConfig.getAdminConfig().getJobInfoMapper().scheduleJobQuery(nowTime + PRE_READ_MS, preReadCount);
                        if (scheduleList != null && scheduleList.size() > 0) {
                            // 2、push time-ring
                            for (JobInfo jobInfo : scheduleList) {
                                // 一个 job 抛异常不能拖垮整批：异常若逃到这里，后面的 job 全部不再处理，
                                // 且下面的 scheduleUpdate 也不会执行——已触发 job 的 next-trigger-time 不落地，
                                // 表现为社区报的 #389「定时任务不触发」。cron 非法（例如填了 5 段 unix crons）就在这里抛。
                                try {
                                    handleScheduleItem(jobInfo, nowTime);
                                } catch (Exception e) {
                                    logScheduleItemError(jobInfo, e);
                                }
                            }

                            // 3、update trigger info
                            for (JobInfo jobInfo : scheduleList) {
                                JobAdminConfig.getAdminConfig().getJobInfoMapper().scheduleUpdate(jobInfo);
                            }

                        } else {
                            preReadSuc = false;
                        }

                        // tx stop


                    } catch (Exception e) {
                        if (!scheduleThreadToStop) {
                            logger.error(">>>>>>>>>>> datax-web, JobScheduleHelper#scheduleThread error:{}", e);
                        }
                    } finally {

                        // commit
                        if (conn != null) {
                            try {
                                conn.commit();
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                            try {
                                conn.setAutoCommit(connAutoCommit);
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                            try {
                                conn.close();
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                        }

                        // close PreparedStatement
                        if (null != preparedStatement) {
                            try {
                                preparedStatement.close();
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                        }
                    }
                    long cost = System.currentTimeMillis() - start;


                    // Wait seconds, align second
                    if (cost < 1000) {  // scan-overtime, not wait
                        try {
                            // pre-read period: success > scan each second; fail > skip this period;
                            TimeUnit.MILLISECONDS.sleep((preReadSuc ? 1000 : PRE_READ_MS) - System.currentTimeMillis() % 1000);
                        } catch (InterruptedException e) {
                            if (!scheduleThreadToStop) {
                                logger.error(e.getMessage(), e);
                            }
                        }
                    }

                }

                logger.info(">>>>>>>>>>> datax-web, JobScheduleHelper#scheduleThread stop");
            }
        });
        scheduleThread.setDaemon(true);
        scheduleThread.setName("datax-web, admin JobScheduleHelper#scheduleThread");
        scheduleThread.start();


        // ring thread
        ringThread = new Thread(() -> {

            // align second
            try {
                TimeUnit.MILLISECONDS.sleep(1000 - System.currentTimeMillis() % 1000);
            } catch (InterruptedException e) {
                if (!ringThreadToStop) {
                    logger.error(e.getMessage(), e);
                }
            }

            while (!ringThreadToStop) {

                try {
                    // second data
                    List<Integer> ringItemData = new ArrayList<>();
                    int nowSecond = Calendar.getInstance().get(Calendar.SECOND);   // 避免处理耗时太长，跨过刻度，向前校验一个刻度；
                    for (int i = 0; i < 2; i++) {
                        List<Integer> tmpData = ringData.remove((nowSecond + 60 - i) % 60);
                        if (tmpData != null) {
                            ringItemData.addAll(tmpData);
                        }
                    }

                    // ring trigger
                    logger.debug(">>>>>>>>>>> datax-web, time-ring beat : " + nowSecond + " = " + Arrays.asList(ringItemData));
                    if (ringItemData.size() > 0) {
                        // do trigger
                        for (int jobId : ringItemData) {
                            // do trigger
                            JobTriggerPoolHelper.trigger(jobId, TriggerTypeEnum.CRON, -1, null, null);
                        }
                        // clear
                        ringItemData.clear();
                    }
                } catch (Exception e) {
                    if (!ringThreadToStop) {
                        logger.error(">>>>>>>>>>> datax-web, JobScheduleHelper#ringThread error:{}", e);
                    }
                }

                // next second, align second
                try {
                    TimeUnit.MILLISECONDS.sleep(1000 - System.currentTimeMillis() % 1000);
                } catch (InterruptedException e) {
                    if (!ringThreadToStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
            logger.info(">>>>>>>>>>> datax-web, JobScheduleHelper#ringThread stop");
        });
        ringThread.setDaemon(true);
        ringThread.setName("datax-web, admin JobScheduleHelper#ringThread");
        ringThread.start();
    }

    /**
     * 单个 job 的一个调度刻度处理：过期不补、临期直触、未到入时间轮。
     * 抛出 ParseException（cron 非法）等异常时由调用方按 job 粒度隔离。
     */
    private void handleScheduleItem(JobInfo jobInfo, long nowTime) throws Exception {

        // time-ring jump
        if (nowTime > jobInfo.getTriggerNextTime() + PRE_READ_MS) {
            // 2.1、trigger-expire > 5s：pass && make next-trigger-time
            // cron 非法时下面的 refreshNextValidTime 会抛异常、next 时间原地不动，这个分支每个扫描周期（约 5s）都会再进来一次，
            // 所以 WARN 必须按 job 限流，否则一个坏任务每小时能写 700 多条日志
            if (shouldLogThrottled(misfireLogTime, jobInfo.getId(), MISFIRE_LOG_INTERVAL_MS)) {
                logger.warn(">>>>>>>>>>> datax-web, schedule misfire, jobId = " + jobInfo.getId());
            }

            // fresh next
            refreshNextValidTime(jobInfo, new Date());

        } else if (nowTime > jobInfo.getTriggerNextTime()) {
            // 2.2、trigger-expire < 5s：direct-trigger && make next-trigger-time

            // 1、trigger
            JobTriggerPoolHelper.trigger(jobInfo.getId(), TriggerTypeEnum.CRON, -1, null, null);
            logger.debug(">>>>>>>>>>> datax-web, schedule push trigger : jobId = " + jobInfo.getId());

            // 2、fresh next
            refreshNextValidTime(jobInfo, new Date());

            // next-trigger-time in 5s, pre-read again
            if (jobInfo.getTriggerStatus() == 1 && nowTime + PRE_READ_MS > jobInfo.getTriggerNextTime()) {

                // 1、make ring second
                int ringSecond = (int) ((jobInfo.getTriggerNextTime() / 1000) % 60);

                // 2、push time ring
                pushTimeRing(ringSecond, jobInfo.getId());

                // 3、fresh next
                refreshNextValidTime(jobInfo, new Date(jobInfo.getTriggerNextTime()));

            }

        } else {
            // 2.3、trigger-pre-read：time-ring trigger && make next-trigger-time

            // 1、make ring second
            int ringSecond = (int) ((jobInfo.getTriggerNextTime() / 1000) % 60);

            // 2、push time ring
            pushTimeRing(ringSecond, jobInfo.getId());

            // 3、fresh next
            refreshNextValidTime(jobInfo, new Date(jobInfo.getTriggerNextTime()));

        }
    }

    /**
     * 调度项失败日志限流：坏 cron 的 job 每秒都会再撞上一次，不去重的话日志会把磁盘写满。
     */
    private void logScheduleItemError(JobInfo jobInfo, Exception e) {
        Integer jobId = jobInfo.getId();
        if (!shouldLogThrottled(itemErrorLogTime, jobId, ITEM_ERROR_LOG_INTERVAL_MS)) {
            return;
        }
        // 用字符串拼接而不是 {} 占位符：异常作为最后一个参数时占位符不会被替换，栈也丢了
        logger.error(">>>>>>>>>>> datax-web, schedule item skipped, jobId = " + jobId
                + ", cron = [" + jobInfo.getJobCron() + "]", e);
    }

    /**
     * 按 jobId 限流：同一个 job 的一类日志在 interval 内只留一条。
     * 坏 cron 的任务每个扫描周期都会再撞一次，不限流就是每小时七百多条重复。
     */
    private static boolean shouldLogThrottled(Map<Integer, Long> lastLogTime, Integer jobId, long intervalMs) {
        long now = System.currentTimeMillis();
        Long last = lastLogTime.get(jobId);
        if (last != null && now - last < intervalMs) {
            return false;
        }
        lastLogTime.put(jobId, now);
        return true;
    }

    private void refreshNextValidTime(JobInfo jobInfo, Date fromTime) throws ParseException {
        Date nextValidTime = new CronExpression(jobInfo.getJobCron()).getNextValidTimeAfter(fromTime);
        if (nextValidTime != null) {
            jobInfo.setTriggerLastTime(jobInfo.getTriggerNextTime());
            jobInfo.setTriggerNextTime(nextValidTime.getTime());
        } else {
            jobInfo.setTriggerStatus(0);
            jobInfo.setTriggerLastTime(0);
            jobInfo.setTriggerNextTime(0);
        }
    }

    private void pushTimeRing(int ringSecond, int jobId) {
        // push async ring
        List<Integer> ringItemData = ringData.get(ringSecond);
        if (ringItemData == null) {
            ringItemData = new ArrayList<Integer>();
            ringData.put(ringSecond, ringItemData);
        }
        ringItemData.add(jobId);

        logger.debug(">>>>>>>>>>> datax-web, schedule push time-ring : " + ringSecond + " = " + Arrays.asList(ringItemData));
    }

    public void toStop() {

        // 1、stop schedule
        scheduleThreadToStop = true;
        try {
            TimeUnit.SECONDS.sleep(1);  // wait
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
        if (scheduleThread.getState() != Thread.State.TERMINATED) {
            // interrupt and wait
            scheduleThread.interrupt();
            try {
                scheduleThread.join();
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        // if has ring data
        boolean hasRingData = false;
        if (!ringData.isEmpty()) {
            for (int second : ringData.keySet()) {
                List<Integer> tmpData = ringData.get(second);
                if (tmpData != null && tmpData.size() > 0) {
                    hasRingData = true;
                    break;
                }
            }
        }
        if (hasRingData) {
            try {
                TimeUnit.SECONDS.sleep(8);
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        // stop ring (wait job-in-memory stop)
        ringThreadToStop = true;
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
        if (ringThread.getState() != Thread.State.TERMINATED) {
            // interrupt and wait
            ringThread.interrupt();
            try {
                ringThread.join();
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        logger.info(">>>>>>>>>>> datax-web, JobScheduleHelper stop");
    }

}
