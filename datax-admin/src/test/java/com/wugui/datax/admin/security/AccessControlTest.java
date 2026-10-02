package com.wugui.datax.admin.security;

import com.wugui.datatx.core.biz.model.ReturnT;
import com.wugui.datax.admin.entity.JobUser;
import com.wugui.datax.admin.entity.JwtUser;
import org.junit.After;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 权限模型本身的可复跑判据。
 *
 * 关键回归点：/api/user/updatePwd 原先只按请求体里的 username 找人，
 * 任何登录用户都能改掉 admin 的密码 —— 拿到一个普通账号就等于拿到整个平台。
 */
public class AccessControlTest {

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String username, String role) {
        JwtUser principal = new JwtUser(user(username, role));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    private static JobUser user(String username, String role) {
        JobUser u = new JobUser();
        u.setUsername(username);
        u.setPassword("hash");
        u.setRole(role);
        return u;
    }

    private static final String SEED_ADMIN_ROLE = "ROLE_ADMIN";
    private static final String LEGACY_ADMIN_ROLE = "1";
    private static final String NORMAL_ROLE = "0";

    @Test
    public void normalUserCannotDoAdminOperations() {
        loginAs("dev01", NORMAL_ROLE);

        assertFalse(AccessControl.isAdmin());
        assertNotNull(AccessControl.requireAdmin());
        assertEquals(ReturnT.FAIL_CODE, AccessControl.requireAdmin().getCode());
    }

    /**
     * 出厂脚本里 admin 那行的 role 字面量就是 'ROLE_ADMIN'（bin/db/datax_web.sql），
     * 前端也是按 ROLE_ADMIN 判权。如果只认列注释里的 '1'，结果就是管理员也被锁在外面。
     */
    @Test
    public void seedAdminRolePassesAdminCheck() {
        loginAs("admin", SEED_ADMIN_ROLE);

        assertTrue("出厂 role 必须被认成管理员", AccessControl.isAdmin());
        assertNull(AccessControl.requireAdmin());
    }

    /** 列注释的老口径：'1' 也必须是管理员 */
    @Test
    public void legacyAdminRolePassesAdminCheck() {
        loginAs("admin2", LEGACY_ADMIN_ROLE);

        assertTrue(AccessControl.isAdmin());
        // 归一化后写进 authority 的是 ROLE_ADMIN，前端 roles:['ROLE_ADMIN'] 才拿得到权限
        assertEquals(SEED_ADMIN_ROLE, AccessControl.normalizeRole(LEGACY_ADMIN_ROLE));
    }

    @Test
    public void adminPassesAdminCheck() {
        loginAs("admin", SEED_ADMIN_ROLE);

        assertTrue(AccessControl.isAdmin());
        assertNull(AccessControl.requireAdmin());
    }

    @Test
    public void normalUserCanOnlyChangeOwnPassword() {
        loginAs("dev01", AccessControl.ROLE_NORMAL);

        assertNull(AccessControl.requireSelfOrAdmin("dev01"));
        // 尾部空格是表单里常见的脏输入，不能借此绕过"本人"判定
        assertNull(AccessControl.requireSelfOrAdmin("dev01 "));
        assertEquals(ReturnT.FAIL_CODE, AccessControl.requireSelfOrAdmin("admin").getCode());
        assertEquals(ReturnT.FAIL_CODE, AccessControl.requireSelfOrAdmin(null).getCode());
    }

    @Test
    public void adminCanChangeAnyonePassword() {
        loginAs("admin", AccessControl.ROLE_ADMIN);
        assertNull(AccessControl.requireSelfOrAdmin("dev01"));
    }

    @Test
    public void anonymousIsNeitherSelfNorAdmin() {
        SecurityContextHolder.clearContext();

        assertFalse(AccessControl.isAdmin());
        assertNull(AccessControl.currentUsername());
        assertEquals(ReturnT.FAIL_CODE, AccessControl.requireSelfOrAdmin("admin").getCode());
    }

    /** role 是可空列：缺省必须落到最小权限，而不是让登录直接炸或落进管理员 */
    @Test
    public void blankRoleFallsBackToNormalUser() {
        loginAs("legacy", null);

        assertFalse("role 为 null 的账号绝不能被当成管理员", AccessControl.isAdmin());
        assertEquals(AccessControl.ROLE_NORMAL,
                new JwtUser(user("legacy", "  ")).getAuthorities().iterator().next().getAuthority());
    }

    @Test
    public void currentUsernameComesFromPrincipalAndMatchIsExact() {
        loginAs("dev02", AccessControl.ROLE_NORMAL);

        assertEquals("dev02", AccessControl.currentUsername());
        // 用户名大小写不同就是另一个人，不能放行
        assertEquals(ReturnT.FAIL_CODE, AccessControl.requireSelfOrAdmin("DEV02").getCode());
    }
}
