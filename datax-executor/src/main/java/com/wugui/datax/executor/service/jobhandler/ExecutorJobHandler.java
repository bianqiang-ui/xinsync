package com.wugui.datax.executor.service.jobhandler;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.wugui.datatx.core.biz.model.HandleProcessCallbackParam;
import com.wugui.datatx.core.biz.model.ReturnT;
import com.wugui.datatx.core.biz.model.TriggerParam;
import com.wugui.datatx.core.handler.IJobHandler;
import com.wugui.datatx.core.handler.annotation.JobHandler;
import com.wugui.datatx.core.log.JobLogger;
import com.wugui.datatx.core.thread.ProcessCallbackThread;
import com.wugui.datatx.core.util.ProcessUtil;
import com.wugui.datax.executor.service.logparse.LogStatistics;
import com.wugui.datax.executor.util.PrivateTmpFiles;
import com.wugui.datax.executor.util.SystemUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.*;
import java.util.concurrent.FutureTask;

import static com.wugui.datax.executor.service.command.BuildCommand.buildDataXExecutorCmd;
import static com.wugui.datax.executor.service.jobhandler.DataXConstant.DEFAULT_JSON;
import static com.wugui.datax.executor.service.logparse.AnalysisStatistics.analysisStatisticsLog;

/**
 * DataX任务运行
 *
 * @author jingwk 2019-11-16
 */

@JobHandler(value = "executorJobHandler")
@Component
public class ExecutorJobHandler extends IJobHandler {

    @Value("${datax.executor.jsonpath}")
    private String jsonPath;

    @Value("${datax.pypath}")
    private String dataXPyPath;

    @Value("${datax.executor.python:python}")
    private String pythonPath;

    /** 启动清理的总开关：设为 false 时一个文件都不删，留给"同机多实例共享 jsonpath"的部署自保 */
    @Value("${datax.executor.tmpclean.enabled:true}")
    private boolean tmpCleanEnabled;

    /**
     * 只删早于这个时长（分钟）的残留文件。
     * 阈值不是越大越好（残留的口令文件多留一分钟就多一分风险），也不能太小：
     * 同机另一个执行器实例正在跑的长任务，它的临时文件要活到任务结束，删了就等于把在跑的任务打死。
     */
    @Value("${datax.executor.tmpclean.staleMinutes:1440}")
    private int tmpCleanStaleMinutes;


    @Override
    public ReturnT<String> execute(TriggerParam trigger) {

        int exitValue = -1;
        Thread errThread = null;
        LogStatistics logStatistics = null;
        //Generate JSON temporary file
        String tmpFilePath;
        try {
            tmpFilePath = generateTemJsonFile(trigger.getJobJson());
        } catch (IOException e) {
            // 临时文件写不出来就别把 "null" 或半截 JSON 交给 datax.py：
            // 那一跑只有"配置解析失败"这种看不懂的报错，真正的因（磁盘满/目录不可写）反而被埋了
            JobLogger.log("数据配置临时文件写入失败，作业终止：" + e.getMessage());
            return new ReturnT<>(IJobHandler.FAIL.getCode(), "生成数据配置临时文件失败：" + e.getMessage());
        }
        Process process = null;
        String prcsId = null;

        try {
            String[] cmdarrayFinal = buildDataXExecutorCmd(trigger, tmpFilePath, dataXPyPath, pythonPath);
            process = Runtime.getRuntime().exec(cmdarrayFinal);
            // 两条日志流要先取出来赋给 final 局部变量：process 本身在 finally 里还要用，不再是 effectively final
            final InputStream stdout = process.getInputStream();
            final InputStream stderr = process.getErrorStream();
            prcsId = ProcessUtil.getProcessId(process);
            JobLogger.log("------------------DataX process id: " + prcsId);
            jobTmpFiles.put(prcsId, tmpFilePath);
            //update datax process id
            HandleProcessCallbackParam prcs = new HandleProcessCallbackParam(trigger.getLogId(), trigger.getLogDateTime(), prcsId);
            ProcessCallbackThread.pushCallBack(prcs);
            // log-thread
            Thread futureThread = null;
            FutureTask<LogStatistics> futureTask = new FutureTask<>(() -> analysisStatisticsLog(new BufferedInputStream(stdout)));
            futureThread = new Thread(futureTask);
            futureThread.start();

            errThread = new Thread(() -> {
                try {
                    analysisStatisticsLog(new BufferedInputStream(stderr));
                } catch (IOException e) {
                    JobLogger.log(e);
                }
            });
            // stderr 必须赶在阻塞等 stdout 结果之前就开始读：子进程写满管道缓冲（Linux 默认 64KB）后会
            // 阻塞在 write(stderr) 上，而主线程正卡在 futureTask.get() 等它的 stdout 结束标记 —— 互相等死（社区 #487）。
            errThread.start();

            logStatistics = futureTask.get();
            // process-wait
            exitValue = process.waitFor();      // exit code: 0=success, 1=error
            // log-thread join
            errThread.join();
        } catch (Exception e) {
            JobLogger.log(e);
        } finally {
            if (errThread != null && errThread.isAlive()) {
                errThread.interrupt();
            }
            // 没跑到 waitFor 就说明本任务被打断/超时/异常了。DataX 子进程不会自己退出，
            // 留着它 = 后台继续往目标表写同一批数据（社区 #348 里"同一个任务被执行两次"的数据面根因）
            if (exitValue == -1 && prcsId != null && !"-1".equals(prcsId)) {
                JobLogger.log("------------------DataX job interrupted, killing process " + prcsId);
                try {
                    ProcessUtil.killProcessByPid(prcsId);
                } catch (Exception e) {
                    JobLogger.log("kill datax process fail: " + e.getMessage());
                }
            }
            if (process != null) {
                process.destroy();
            }
            if (prcsId != null) {
                jobTmpFiles.remove(prcsId);
            }
            //  删除临时文件
            if (FileUtil.exist(tmpFilePath)) {
                FileUtil.del(new File(tmpFilePath));
            }
        }
        if (exitValue == 0) {
            return new ReturnT<>(200, logStatistics != null ? logStatistics.toString() : "datax exit 0, statistics log not parsed");
        } else {
            return new ReturnT<>(IJobHandler.FAIL.getCode(), "command exit value(" + exitValue + ") is failed"
                    + (logStatistics != null ? ", " + logStatistics.toString() : ""));
        }
    }



    private String generateTemJsonFile(String jobJson) throws IOException {
        // 原先这里回写注入字段 jsonPath：handler 是跨 JobThread 共享的单例，并发执行会互相改路径
        String dataXHomePath = SystemUtils.getDataXHomePath();
        String jsonDir = StringUtils.isNotEmpty(dataXHomePath) ? dataXHomePath + DEFAULT_JSON : jsonPath;
        if (!FileUtil.exist(jsonDir)) {
            FileUtil.mkdir(jsonDir);
        }
        // 配置的 jsonpath 不带结尾分隔符，直接字符串拼接会生成 "…/data/jsonjobTmp-xxx.conf"
        String tmpFilePath = new File(jsonDir, "jobTmp-" + IdUtil.simpleUUID() + ".conf").getAbsolutePath();
        // 根据json写入到临时本地文件
        // 临时文件包含解密后的数据源明文口令。权限必须是「创建的那一次就 0600」，
        // 不能"先建后 chmod"——建文件时的权限由 umask 决定（Linux 默认 0644，同机任意用户可读），
        // 从落盘到收回权限之间口令已经全局可读了一回，进程被 kill 时这个文件还会留在磁盘上。
        PrivateTmpFiles.writeOwnerOnly(new File(tmpFilePath), jobJson);
        return tmpFilePath;
    }

    /**
     * 启动时清理上一次进程异常退出可能残留的临时配置文件。
     * 这些文件包含解密后的数据源明文口令。
     *
     * 挂在 @PostConstruct 上：execute() 的 finally 只有在任务正常走完时才跑得到，
     * kill -9 / OOM / 断电都跑不到，那些文件此前没有任何人会来删。
     */
    @PostConstruct
    public void cleanStaleTmpFilesOnStartup() {
        String jsonDir = resolveJsonDir();
        if (!tmpCleanEnabled) {
            JobLogger.log("datax.executor.tmpclean.enabled=false，跳过启动清理，" +
                    "残留的 jobTmp-*.conf（内含明文数据源口令）需自行处理，目录：" + jsonDir);
            return;
        }
        long cutoffMillis = System.currentTimeMillis() - tmpCleanStaleMinutes * 60_000L;
        int deleted = cleanStaleTmpFiles(new File(jsonDir), cutoffMillis);
        JobLogger.log("启动清理数据配置临时文件：目录 {}，早于 {} 分钟前的残留删除 {} 个",
                jsonDir, tmpCleanStaleMinutes, deleted);
    }

    /**
     * 删除目录里已经"陈旧"的 jobTmp-*.conf，返回真正删掉的个数。
     *
     * 判定收窄到三条，越界的一律不动：文件名匹配、时间早于阈值、且不在本 JVM 在跑任务的引用表里。
     * 抽成静态纯函数是为了能被单测直接喂一个临时目录跑真删（不需要 Spring 上下文，也不需要反射）。
     */
    static int cleanStaleTmpFiles(File dir, long cutoffMillis) {
        if (!dir.isDirectory()) {
            return 0;
        }
        File[] staleFiles = dir.listFiles((d, name) -> name.startsWith("jobTmp-") && name.endsWith(".conf"));
        if (staleFiles == null) {
            return 0;
        }
        int deleted = 0;
        for (File f : staleFiles) {
            long lastModified = f.lastModified();
            if (lastModified >= cutoffMillis) {
                continue;
            }
            if (jobTmpFiles.values().contains(f.getAbsolutePath())) {
                // 本进程还有任务在引用它，删了就是在跑的作业当场断粮
                continue;
            }
            if (f.delete()) {
                deleted++;
                // 时间要在 delete 之前取：删完再读 lastModified() 只会得到 0
                JobLogger.log("cleaned stale tmp file: {} (last modified {})", f.getName(),
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(lastModified)));
            } else {
                // 删不掉多半是权限或被占用；不吭声就等于"清过了"，那正是这条修复原本要治的病
                JobLogger.log("删除数据配置临时文件失败，仍在磁盘上保留明文口令： {}", f.getAbsolutePath());
            }
        }
        return deleted;
    }

    /** 包内可见：单测要按 handler 实际算出来的目录去摆残留文件，不能假设 DATAX_HOME 没设 */
    String resolveJsonDir() {
        String dataXHomePath = SystemUtils.getDataXHomePath();
        return StringUtils.isNotEmpty(dataXHomePath) ? dataXHomePath + DEFAULT_JSON : jsonPath;
    }

}
