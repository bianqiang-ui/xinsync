package com.wugui.datax.executor.service.jobhandler;

import com.wugui.datatx.core.handler.IJobHandler;
import com.wugui.datax.executor.util.PrivateTmpFiles;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * 批次 10-J / B2：数据配置临时文件的明文口令既不能有一段"全局可读"的裸露期，
 * 也不能在进程被 kill 之后没人来删。
 *
 * 两条红线各自对应一半用例：
 *   写入侧（PrivateTmpFiles）——权限要在"创建"那一次就带上，且内容必须完整；
 *   清理侧（cleanStaleTmpFiles）——只删陈年的 jobTmp-*.conf，且开关关掉时一个都不许动。
 *
 * 用例里不写 Assume/skip：门禁的判定汇总行要求 Skipped=0，跳过等于这条红线当场没人验。
 * Windows 上没有 POSIX 权限视图，涉及权限的用例改成断言"兜底写法确实在跑、内容写全了"，
 * 平台差异摆在明面上，而不是整条用例消失。
 */
public class ExecutorTmpFileTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** Java 8 的 File.setLastModified 对 <=0 的时间戳直接返回 false，造陈年文件必须给正数 */
    private static final long OLD_MTIME = System.currentTimeMillis() - MINUTES.toMillis(10000);

    private static final String CONFIG_WITH_SECRET = "{\"job\":{\"setting\":{},\"content\":[],\"密码\":\"p@ss,中文\"}}";

    private File dir;

    @Before
    public void makeTempDir() throws IOException {
        dir = Files.createTempDirectory("executor-tmpfile-test").toFile();
        Assert.assertTrue(dir.isDirectory());
    }

    @After
    public void removeTempDir() {
        deleteRecursively(dir);
        IJobHandler.jobTmpFiles.remove("test-in-flight-pid");
    }

    // ---------------- 写入侧：创建即 0600 ----------------

    @Test
    public void writesTheConfigVerbatimWithoutAnExtraLineBreak() throws Exception {
        File file = new File(dir, "jobTmp-content.conf");
        PrivateTmpFiles.writeOwnerOnly(file, CONFIG_WITH_SECRET);
        Assert.assertEquals("内容必须逐字节是那份 jobJson，多一行少一行都可能让 datax.py 解析失败",
                CONFIG_WITH_SECRET, read(file));
    }

    @Test
    public void emptyConfigStillProducesAFileInsteadOfSilentlyNothing() throws Exception {
        File file = new File(dir, "jobTmp-empty.conf");
        PrivateTmpFiles.writeOwnerOnly(file, "");
        Assert.assertTrue(file.isFile());
        Assert.assertEquals("", read(file));
    }

    @Test
    public void nullConfigIsWrittenAsAnEmptyFileRatherThanTheWordNull() throws Exception {
        // 历史上的 PrintWriter 会把 null 内容打成 "null" 一行，datax.py 那边只剩一个解析错误
        File file = new File(dir, "jobTmp-null.conf");
        PrivateTmpFiles.writeOwnerOnly(file, null);
        Assert.assertEquals("", read(file));
    }

    @Test
    public void fileIsNotReadableByGroupOrOtherWhenTheFsSupportsPermissions() throws Exception {
        File file = new File(dir, "jobTmp-perm.conf");
        PrivateTmpFiles.writeOwnerOnly(file, CONFIG_WITH_SECRET);

        if (PrivateTmpFiles.supportsCreateTimePermissions()) {
            Set<PosixFilePermission> perms = PrivateTmpFiles.posixPermissions(file);
            Assert.assertEquals("临时配置内含解密后的明文数据源口令，必须只有属主可读写，当前是 "
                            + PosixFilePermissions.toString(perms),
                    PosixFilePermissions.fromString(PrivateTmpFiles.OWNER_READ_WRITE), perms);
            Assert.assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
            Assert.assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
            Assert.assertFalse(perms.contains(PosixFilePermission.OTHERS_WRITE));
        } else {
            // 非 POSIX 文件系统走的是"先建后改"兜底：这条用例不退化成空断言，
            // 钉住的是兜底路径同样把内容写全（Windows 上真正能给的补偿是启动清理 + 目录继承权限）
            Assert.assertTrue(file.isFile());
            Assert.assertEquals(CONFIG_WITH_SECRET, read(file));
        }
    }

    @Test
    public void refusesToClobberAnExistingFileWhenCreateTimePermissionsAreSupported() throws Exception {
        File file = new File(dir, "jobTmp-collision.conf");
        PrivateTmpFiles.writeOwnerOnly(file, "first");
        if (PrivateTmpFiles.supportsCreateTimePermissions()) {
            try {
                PrivateTmpFiles.writeOwnerOnly(file, "second");
                Assert.fail("同名文件必须拒绝覆盖：静默改写别人正在用的临时配置是数据面事故");
            } catch (FileAlreadyExistsException expected) {
                // CREATE_NEW 生效
            }
            Assert.assertEquals("first", read(file));
        } else {
            PrivateTmpFiles.writeOwnerOnly(file, "second");
            Assert.assertEquals("second", read(file));
        }
    }

    @Test
    public void reportsAWriteFailureInsteadOfHandingTheJobABrokenPath() throws Exception {
        // 父目录不存在时 hutool 那条 mkdir 不在本方法职责内，这里钉的是"失败必须抛出"：
        // 历史上 FileNotFoundException 只进日志、路径照样返回，作业只看到 datax.py 的配置报错
        File insideAFile = new File(new File(dir, "jobTmp-not-a-dir.conf"), "child.conf");
        try {
            PrivateTmpFiles.writeOwnerOnly(insideAFile, CONFIG_WITH_SECRET);
            Assert.fail("写不进去必须抛 IOException，不能返回一个不存在的路径");
        } catch (IOException expected) {
            Assert.assertFalse(insideAFile.exists());
        }
    }

    // ---------------- 清理侧：陈年残留必须有人删，但要挑着删 ----------------

    @Test
    public void deletesOnlyFilesOlderThanTheCutoff() throws Exception {
        File stale = touch(new File(dir, "jobTmp-stale.conf"), System.currentTimeMillis() - MINUTES.toMillis(600));
        File fresh = touch(new File(dir, "jobTmp-fresh.conf"), System.currentTimeMillis());
        long cutoff = System.currentTimeMillis() - MINUTES.toMillis(1440);

        // 上面的 stale 才 10 小时，按默认的 24 小时阈值不该删 —— 阈值这条线必须有反例才站得住
        Assert.assertEquals(0, ExecutorJobHandler.cleanStaleTmpFiles(dir, cutoff));
        Assert.assertTrue("早于阈值一天还不该删，说明阈值算错了", stale.exists());

        cutoff = System.currentTimeMillis() - MINUTES.toMillis(120);
        Assert.assertEquals(1, ExecutorJobHandler.cleanStaleTmpFiles(dir, cutoff));
        Assert.assertFalse("进程被 kill 留下的明文口令文件必须被清掉", stale.exists());
        Assert.assertTrue("同机另一个实例正在跑的任务，其临时文件不能被启动清理打死", fresh.exists());
    }

    @Test
    public void touchesNothingThatIsNotAJobTmpConfig() throws Exception {
        File readme = touch(new File(dir, "README.md"), OLD_MTIME);
        File backup = touch(new File(dir, "jobTmp-abc.conf.bak"), OLD_MTIME);
        File otherPrefix = touch(new File(dir, "datax-jobTmp-abc.conf"), OLD_MTIME);
        File target = touch(new File(dir, "jobTmp-abc.conf"), OLD_MTIME);

        Assert.assertEquals(1, ExecutorJobHandler.cleanStaleTmpFiles(dir, System.currentTimeMillis()));
        Assert.assertFalse(target.exists());
        Assert.assertTrue("清理范围越界就会删掉别人的文件：" + readme.getName(), readme.exists());
        Assert.assertTrue(backup.exists());
        Assert.assertTrue(otherPrefix.exists());
    }

    @Test
    public void keepsFilesStillReferencedByARunningJobOfThisJvm() throws Exception {
        File inFlight = touch(new File(dir, "jobTmp-inflight.conf"), OLD_MTIME);
        IJobHandler.jobTmpFiles.put("test-in-flight-pid", inFlight.getAbsolutePath());
        try {
            Assert.assertEquals(0, ExecutorJobHandler.cleanStaleTmpFiles(dir, System.currentTimeMillis()));
            Assert.assertTrue("本进程的 jobTmpFiles 里还有引用，说明任务正在跑，一个陈年判定不许把它删掉",
                    inFlight.exists());
        } finally {
            IJobHandler.jobTmpFiles.remove("test-in-flight-pid");
        }
        // 引用摘掉之后同一批文件就该按陈年规则删掉，否则上面那条断言只是"永远不删"的假绿
        Assert.assertEquals(1, ExecutorJobHandler.cleanStaleTmpFiles(dir, System.currentTimeMillis()));
    }

    @Test
    public void survivesAMissingOrNonDirectoryJsonPath() throws Exception {
        Assert.assertEquals(0, ExecutorJobHandler.cleanStaleTmpFiles(new File(dir, "not-there"), 0L));
        File aFile = touch(new File(dir, "jobTmp-is-a-file.conf"), OLD_MTIME);
        Assert.assertEquals(0, ExecutorJobHandler.cleanStaleTmpFiles(aFile, 0L));
        Assert.assertTrue(aFile.exists());
    }

    // ---------------- 接缝：清理方法必须真的有人调 ----------------

    @Test
    public void startupHookIsAnnotatedSoNobodyHasToRememberToCallIt() throws Exception {
        PostConstruct annotation = ExecutorJobHandler.class
                .getMethod("cleanStaleTmpFilesOnStartup").getAnnotation(PostConstruct.class);
        Assert.assertNotNull("@PostConstruct 掉了，启动清理就又变回那个零调用的方法", annotation);
    }

    @Test
    public void startupHookCleansResidueInItsOwnResolvedDirectory() throws Exception {
        ExecutorJobHandler handler = handler(true, 1440);
        File jsonDir = new File(handler.resolveJsonDir());
        Assert.assertTrue(jsonDir.isDirectory() || jsonDir.mkdirs());
        File stale = touch(new File(jsonDir, "jobTmp-hook-stale.conf"),
                System.currentTimeMillis() - MINUTES.toMillis(1441));
        File fresh = touch(new File(jsonDir, "jobTmp-hook-fresh.conf"), System.currentTimeMillis());

        handler.cleanStaleTmpFilesOnStartup();

        Assert.assertFalse("启动钩子没删陈年残留，口令文件还在磁盘上", stale.exists());
        Assert.assertTrue(fresh.exists());
    }

    @Test
    public void startupHookIsTheEscapeHatchWhenDisabled() throws Exception {
        ExecutorJobHandler handler = handler(false, 1440);
        File jsonDir = new File(handler.resolveJsonDir());
        Assert.assertTrue(jsonDir.isDirectory() || jsonDir.mkdirs());
        File stale = touch(new File(jsonDir, "jobTmp-hook-off.conf"),
                System.currentTimeMillis() - MINUTES.toMillis(99999));

        handler.cleanStaleTmpFilesOnStartup();

        Assert.assertTrue("tmpclean.enabled=false 是逃生口：关掉就必须一个文件都不删，" +
                "否则同机多实例共享 jsonpath 的部署没有自保手段", stale.exists());
    }

    private ExecutorJobHandler handler(boolean enabled, int staleMinutes) {
        ExecutorJobHandler handler = new ExecutorJobHandler();
        ReflectionTestUtils.setField(handler, "jsonPath", dir.getAbsolutePath());
        ReflectionTestUtils.setField(handler, "tmpCleanEnabled", enabled);
        ReflectionTestUtils.setField(handler, "tmpCleanStaleMinutes", staleMinutes);
        return handler;
    }

    private static File touch(File file, long lastModifiedMillis) throws IOException {
        PrivateTmpFiles.writeOwnerOnly(file, CONFIG_WITH_SECRET);
        // setLastModified 传的是秒级时间戳，这里给毫秒值；部分文件系统会做截整，
        // 所以上面的用例阈值都留了分钟级余量，不靠边界相等判定
        Assert.assertTrue("改不动 mtime 就没法造出陈年文件：" + file, file.setLastModified(lastModifiedMillis));
        return file;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), UTF8);
    }

    private void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete() && !file.equals(dir)) {
            throw new IllegalStateException("临时目录没清干净：" + file.getAbsolutePath());
        }
    }
}
