package com.wugui.datax.admin.security;

import com.wugui.datatx.core.glue.GlueTypeEnum;

/**
 * "这个 glueType 是不是只有管理员能写" 的唯一判定处。
 *
 * 为什么要单独抽出来：job_info 的 glue_type / glue_source 只有两条 SQL 会写
 * （{@code JobInfoMapper.xml} 的 save 与 update），但落到入口上有五个
 * —— /api/job/add、/api/job/update、/jobcode/save、/api/job/batchAdd、/api/jobTemplate/add。
 * 判定散在控制器里就会出现"补了 add 漏了 batchAdd"这类缺口，历史上已经发生过一次。
 *
 * 判定口径是**白名单**：只有 BEAN 对普通用户开放，其余一律管理员专属。
 * 这里不要改成按 {@link GlueTypeEnum#isScript()} 判 —— GLUE_GROOVY 的 isScript 是 false，
 * 但 {@code ExecutorBizImpl} 会走 {@code GlueFactory.loadNewInstance(glueSource)}
 * （groovy.lang.GroovyClassLoader.parseClass）在执行器 JVM 里编译并运行这段代码，
 * 它是货真价实的远程代码执行。按 isScript 判会正好漏掉危害最大的那一类。
 */
public final class GlueScriptAccess {

    public static final String DENY_MSG =
            "普通用户只能创建和修改 BEAN 型（数据同步）任务；"
            + "GLUE 脚本型与 GLUE_GROOVY 任务的内容会在执行器主机或执行器 JVM 上直接执行，需要管理员权限";

    private GlueScriptAccess() {
    }

    /**
     * 枚举 name 与 desc 两种形态都要认：前端发的是 name（"GLUE_SHELL"），
     * 而 {@code JobServiceImpl} 的历史判定比的是 desc（"GLUE(Shell)"），
     * 只认一种会让判定对另一半调用恒为 false。BEAN 的两同名，所以主流程不受影响。
     */
    public static GlueTypeEnum parse(String rawGlueType) {
        if (rawGlueType == null) {
            return null;
        }
        String raw = rawGlueType.trim();
        for (GlueTypeEnum item : GlueTypeEnum.values()) {
            if (item.name().equals(raw) || item.getDesc().equals(raw)) {
                return item;
            }
        }
        return null;
    }

    /**
     * @return true = 这个 glueType 不能让普通用户写
     */
    public static boolean isAdminOnly(String rawGlueType) {
        if (rawGlueType == null || rawGlueType.trim().isEmpty()) {
            // 空值由 service 的既有校验负责拒绝，不在这里改判；本判定只管"非 BEAN 收归管理员"
            return false;
        }
        return parse(rawGlueType) != GlueTypeEnum.BEAN;
    }

    /**
     * @return 放行返回 null；拒绝返回可直接回给前端的文案。管理员永远放行（反向红线：不得关掉管理员自己的能力）
     */
    public static String denyMessage(String rawGlueType) {
        if (!isAdminOnly(rawGlueType)) {
            return null;
        }
        return AccessControl.isAdmin() ? null : DENY_MSG;
    }
}
