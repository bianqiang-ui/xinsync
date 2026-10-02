package com.wugui.datax.admin.security;

import com.wugui.datatx.core.biz.model.ReturnT;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;

/**
 * 用户态权限判定的唯一实现处。
 *
 * 背景：本工程虽然开着 @EnableGlobalMethodSecurity(prePostEnabled=true)，但全仓库
 * 一个 @PreAuthorize/@Secured 都没有 —— 也就是只有"登录校验"、没有"权限模型"。
 * 于是任何登录用户都能调 /api/user/add、/remove，甚至用 /updatePwd 把 admin 的密码改掉。
 *
 * 这里不用 SpEL 注解而用显式判定，原因有两个：
 * 1) 角色值就是 t_user.role 里的那个字符串（列注释：0-普通用户、1-管理员），没有字典表；
 *    把 "1" 这种字面量散到各个注解里，改一次要全盘找、也没法单测。
 * 2) "只能改自己的密码"这类判定要看请求体里的目标用户名，注解表达不清，显式写出来才可审计。
 */
public final class AccessControl {

    /**
     * 管理员角色串。这里必须同时认两种写法，因为库里本来就两种并存：
     *   - 出厂脚本 bin/db/datax_web.sql 的 admin 行写的是 'ROLE_ADMIN'（前端也按 ROLE_ADMIN 判权）；
     *   - job_user.role 的列注释、以及 JobUser#isAdmin() 的历史实现用的是 '1'。
     * 只认其中一种的后果是"管理员也被拒之门外"，所以判定取并集。
     */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    /** 老口径的管理员值（列注释：0-普通用户、1-管理员） */
    public static final String ROLE_ADMIN_LEGACY = "1";
    /** 普通用户：role 缺省时按最小权限处理成这个值 */
    public static final String ROLE_NORMAL = "0";

    private static final String NO_ADMIN_MSG = "该操作需要管理员权限";

    private AccessControl() {
    }

    /** 当前登录用户名；未登录（理论上到不了这里，Security 已拦）返回 null */
    public static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return auth.getName();
    }

    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return isAdmin(auth.getAuthorities());
    }

    static boolean isAdmin(Collection<? extends GrantedAuthority> authorities) {
        if (authorities == null) {
            return false;
        }
        for (GrantedAuthority ga : authorities) {
            if (ga == null) {
                continue;
            }
            String role = ga.getAuthority() == null ? "" : ga.getAuthority().trim();
            if (ROLE_ADMIN.equals(role) || ROLE_ADMIN_LEGACY.equals(role)) {
                return true;
            }
        }
        return false;
    }

    /** role 为 null/空白时 SimpleGrantedAuthority 会直接抛 IllegalArgumentException，必须先兜底 */
    public static String normalizeRole(String rawRole) {
        if (rawRole == null || rawRole.trim().isEmpty()) {
            return ROLE_NORMAL;
        }
        String role = rawRole.trim();
        return ROLE_ADMIN_LEGACY.equals(role) ? ROLE_ADMIN : role;
    }

    /**
     * @return 放行时返回 null；拒绝时返回可直接回给前端的失败体
     */
    public static ReturnT<String> requireAdmin() {
        return isAdmin() ? null : new ReturnT<String>(ReturnT.FAIL_CODE, NO_ADMIN_MSG);
    }

    /**
     * 改密码这类"针对某个用户"的操作：要么是本人，要么是管理员。
     *
     * @param targetUsername 请求体里要点名的目标用户
     */
    public static ReturnT<String> requireSelfOrAdmin(String targetUsername) {
        if (isAdmin()) {
            return null;
        }
        String me = currentUsername();
        if (me != null && targetUsername != null && me.equals(targetUsername.trim())) {
            return null;
        }
        return new ReturnT<String>(ReturnT.FAIL_CODE, "只能修改本人的密码");
    }
}
