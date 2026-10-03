package com.wugui.datax.executor.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * 只允许属主读写的文件写入（批次 10-J / B2）。
 *
 * 为什么单独一处：执行器把 jobJson 落成 jobTmp-&lt;uuid&gt;.conf 时，那份 JSON 里带着**解密后的
 * 数据源明文口令**。历史上是"先建文件、写完再 setReadable/setWritable 收回权限"，
 * 而建文件用的权限由 umask 决定（Linux 默认 0644 = 同机任意用户可读），
 * 从创建到 chmod 之间的那几毫秒里，口令已经落盘且全局可读 —— 窗口再小也是窗口。
 * 这里把权限要进"创建"那一步，同一次 open 里带上属性，不再存在裸露期。
 *
 * 唯一实现处，判定形状由 devops/checks/check_executor_tmpfile.sh 守着。
 */
public final class PrivateTmpFiles {

    private static final Logger LOGGER = LoggerFactory.getLogger(PrivateTmpFiles.class);

    /** owner 读写、组内与其他用户全无：等效 0600 */
    public static final String OWNER_READ_WRITE = "rw-------";

    /**
     * 兜底写法（先建后 chmod）的告警只在本 JVM 说一次。
     * 本项目常在 Windows 上跑，那条路径每条作业都会走到，每条都 WARN 会把作业日志刷满，
     * 反而淹没了真正要看的那一行。
     */
    private static final AtomicBoolean FALLBACK_WARNED = new AtomicBoolean(false);

    private PrivateTmpFiles() {
    }

    /**
     * 当前文件系统能否在创建时指定 POSIX 权限。Windows（含 NTFS 上的 JDK）返回 false，
     * 这时只剩"先建后改"的兜底写法，权限窗口无法消除，必须由调用方把这件事说出去。
     */
    public static boolean supportsCreateTimePermissions() {
        return java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /**
     * 以「创建即 owner 读写」写入内容，文件已存在时不覆盖（抛 FileAlreadyExistsException）。
     *
     * @param file    目标文件，父目录必须已存在
     * @param content 文件内容，按 UTF-8 原样落盘，不追加行分隔符
     */
    public static void writeOwnerOnly(File file, String content) throws IOException {
        byte[] bytes = (content == null ? "" : content).getBytes("UTF-8");
        if (supportsCreateTimePermissions()) {
            try {
                writeWithCreateTimePermissions(file, bytes);
                return;
            } catch (UnsupportedOperationException e) {
                // 视图里有 posix 但底层文件系统不认（如 FAT/网络盘），只能退回兜底
                LOGGER.warn("创建时指定 0600 权限不被 {} 支持：{}", file.getAbsolutePath(), e.getMessage());
            } catch (IOException e) {
                if (file.exists()) {
                    // 文件已存在 = CREATE_NEW 撞上了同名文件，或写内容时真的坏了；
                    // 后者不能让作业带着半截 JSON 起跑，必须原样抛出去
                    throw e;
                }
                LOGGER.warn("创建时指定 0600 权限失败：{}，退回先建后改权限", e.getMessage());
            }
        }
        writeThenRestrictPermissions(file, bytes);
    }

    /** 供测试与门禁核对实际落盘权限；非 POSIX 文件系统上抛 UnsupportedOperationException */
    public static Set<PosixFilePermission> posixPermissions(File file) throws IOException {
        return java.nio.file.Files.getPosixFilePermissions(file.toPath());
    }

    private static void writeWithCreateTimePermissions(File file, byte[] bytes) throws IOException {
        FileAttribute<Set<PosixFilePermission>> attributes =
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_READ_WRITE));
        try (FileChannel channel = FileChannel.open(file.toPath(), EnumSet.of(CREATE_NEW, WRITE), attributes)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }

    /**
     * 非 POSIX 文件系统（主要是 Windows）上的兜底：先写再收权限。
     *
     * 这里存在上面注释说的裸露窗口，本机无法消除，所以第一次走到就显式告警；
     * Windows 上 setReadable/setWritable 只落到"只读属性"，起不到 POSIX 0600 的作用，
     * 真正的补偿手段是收紧 jsonpath 目录的继承权限，以及启动清理（见 ExecutorJobHandler）。
     */
    private static void writeThenRestrictPermissions(File file, byte[] bytes) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        if (FALLBACK_WARNED.compareAndSet(false, true)) {
            LOGGER.warn("[SECURITY] 数据源明文口令落盘在 {}，本机不支持「创建即 0600」，" +
                    "先建文件再改权限之间存在同机其他用户可读的窗口。" +
                    "请收紧该目录的权限继承（Windows：icacls <jsonpath> /inheritance:r /grant:r \"%USERNAME%:F\"），" +
                    "并保留 datax.executor.tmpclean 的启动清理。", file.getAbsolutePath());
        }
    }
}
