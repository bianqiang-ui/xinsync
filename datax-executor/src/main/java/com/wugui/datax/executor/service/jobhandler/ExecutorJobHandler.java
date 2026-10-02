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
import com.wugui.datax.executor.util.SystemUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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


    @Override
    public ReturnT<String> execute(TriggerParam trigger) {

        int exitValue = -1;
        Thread errThread = null;
        LogStatistics logStatistics = null;
        //Generate JSON temporary file
        String tmpFilePath = generateTemJsonFile(trigger.getJobJson());
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



    private String generateTemJsonFile(String jobJson) {
        // 原先这里回写注入字段 jsonPath：handler 是跨 JobThread 共享的单例，并发执行会互相改路径
        String dataXHomePath = SystemUtils.getDataXHomePath();
        String jsonDir = StringUtils.isNotEmpty(dataXHomePath) ? dataXHomePath + DEFAULT_JSON : jsonPath;
        if (!FileUtil.exist(jsonDir)) {
            FileUtil.mkdir(jsonDir);
        }
        // 配置的 jsonpath 不带结尾分隔符，直接字符串拼接会生成 "…/data/jsonjobTmp-xxx.conf"
        String tmpFilePath = new File(jsonDir, "jobTmp-" + IdUtil.simpleUUID() + ".conf").getAbsolutePath();
        // 根据json写入到临时本地文件
        // 临时文件包含解密后的数据源明文口令，权限设为 owner 可读写（0600）：
        // 进程被 kill 时 finally 里的删除可能跑不到，文件留在磁盘上如果是默认 umask（通常 0644）
        // 则同机其他用户可直接读走口令。
        File tmpFile = new File(tmpFilePath);
        try (PrintWriter writer = new PrintWriter(tmpFile, "UTF-8")) {
            writer.println(jobJson);
        } catch (FileNotFoundException | UnsupportedEncodingException e) {
            JobLogger.log("JSON 临时文件写入异常：" + e.getMessage());
        }
        // Java 6+ 的 setReadable/setWritable：第二个参数 ownerOnly=true 等效于 chmod 0600
        tmpFile.setReadable(false, false);   // 先全部撤
        tmpFile.setWritable(false, false);
        tmpFile.setReadable(true, true);     // 只给 owner
        tmpFile.setWritable(true, true);
        return tmpFilePath;
    }

    /**
     * 启动时清理上一次进程异常退出可能残留的临时配置文件。
     * 这些文件包含解密后的数据源明文口令。
     */
    public void cleanStaleTmpFiles() {
        String dataXHomePath = SystemUtils.getDataXHomePath();
        String jsonDir = StringUtils.isNotEmpty(dataXHomePath) ? dataXHomePath + DEFAULT_JSON : jsonPath;
        File dir = new File(jsonDir);
        if (!dir.isDirectory()) {
            return;
        }
        File[] staleFiles = dir.listFiles((d, name) -> name.startsWith("jobTmp-") && name.endsWith(".conf"));
        if (staleFiles != null) {
            for (File f : staleFiles) {
                if (f.delete()) {
                    JobLogger.log("cleaned stale tmp file: " + f.getName());
                }
            }
        }
    }

}
