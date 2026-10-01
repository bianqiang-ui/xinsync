package com.wugui.datatx.core.util;

import com.sun.jna.Platform;
import com.wugui.datatx.core.log.JobLogger;
import com.wugui.datatx.core.thread.JobThread;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * ProcessUtil
 *
 * @author jingwk
 * @version 1.0
 * @since 2019/11/09
 */

public class ProcessUtil {

    private static Logger logger = LoggerFactory.getLogger(JobThread.class);

    public static String getProcessId(Process process) {
        long pid = -1;
        Field field;
        if (Platform.isWindows()) {
            try {
                field = process.getClass().getDeclaredField("handle");
                field.setAccessible(true);
                pid = Kernel32.INSTANCE.GetProcessId((Long) field.get(process));
            } catch (Exception ex) {
                logger.error("get process id for windows error {0}", ex);
            }
        } else if (Platform.isLinux() || Platform.isAIX()) {
            try {
                Class<?> clazz = Class.forName("java.lang.UNIXProcess");
                field = clazz.getDeclaredField("pid");
                field.setAccessible(true);
                pid = (Integer) field.get(process);
            } catch (Throwable e) {
                logger.error("get process id for unix error {0}", e);
            }
        }
        return String.valueOf(pid);
    }

    /**
     * 关闭Linux进程
     *
     * @param pid 进程的PID
     */
    public static boolean killProcessByPid(String pid) {
        if (StringUtils.isEmpty(pid) || "-1".equals(pid)) {
            throw new RuntimeException("Pid ==" + pid);
        }
        // pid 会被拼进命令行参数数组，只允许纯数字，避免把别的内容当成附加参数传给 kill
        if (!pid.matches("\\d+")) {
            throw new RuntimeException("Pid not numeric ==" + pid);
        }
        Process process = null;
        BufferedReader reader = null;
        String command = "";
        boolean result;
        if (Platform.isWindows()) {
            command = "cmd.exe /c taskkill /PID " + pid + " /F /T ";
        } else if (Platform.isLinux() || Platform.isAIX()) {
            // 直接子进程是 datax.py，它再经 shell 拉起 DataX 的 JVM；只 kill 父进程会留下继续往目标表写数的孤儿 JVM
            killDescendantsLinux(pid);
            command = "kill " + pid;
        }
        try {
            //杀掉进程
            process = Runtime.getRuntime().exec(command);
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                JobLogger.log(line);
            }
            result = true;
        } catch (Exception e) {
            logger.error("kill pid error {0}", e);
            result = false;
        } finally {
            if (process != null) {
                process.destroy();
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    logger.error("reader close error {0}", e);
                }
            }
        }
        return result;
    }

    /**
     * 先杀干净后代进程再杀本身：Linux 上没有 taskkill /T 这样的整树终止，用 /proc/&lt;pid&gt;/task/&lt;tid&gt;/children 递归取整棵子树。
     * 读不到（非 Linux、权限、procfs 关闭）就什么都不做，退化成只杀父进程，不影响原有流程。
     */
    private static void killDescendantsLinux(String pid) {
        List<String> tree = new ArrayList<>();
        collectDescendants(pid, tree);
        // 由深到浅杀，避免子进程被 init 收养后再也找不到
        for (int i = tree.size() - 1; i >= 0; i--) {
            try {
                Runtime.getRuntime().exec(new String[]{"kill", tree.get(i)}).waitFor();
            } catch (Exception e) {
                logger.warn("kill descendant pid {} error: {}", tree.get(i), e.getMessage());
            }
        }
    }

    private static void collectDescendants(String pid, List<String> out) {
        File taskDir = new File("/proc/" + pid + "/task");
        File[] tids = taskDir.listFiles();
        if (tids == null) {
            return;
        }
        for (File tid : tids) {
            File childFile = new File(tid, "children");
            if (!childFile.isFile()) {
                continue;
            }
            String raw;
            try {
                raw = new String(Files.readAllBytes(childFile.toPath()), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                continue;
            }
            for (String child : raw.split("\\s+")) {
                if (child.isEmpty() || !child.matches("\\d+") || out.contains(child)) {
                    continue;
                }
                out.add(child);
                collectDescendants(child, out);
            }
        }
    }

}
