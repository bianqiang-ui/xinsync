package com.wugui.datax.admin.entity;

import org.junit.Assert;
import org.junit.Test;

/**
 * 批次 10-J / B1 追加：另外三个"自己就持有凭据"的对象。
 *
 * 前面几条出口堵的是 RPC 链上的 toString（TriggerParam / XxlRpcRequest），这一批堵的是**用户凭据**：
 *   - JwtUser：Spring Security 的 AbstractAuthenticationToken.toString() 会把 principal 原样拼进去，
 *     只要有一处 DEBUG 打出 Authentication 对象，job_user.password 就进日志了；
 *   - LoginUser：登录请求体，password 栏是用户**此刻的明文口令**，@Data 生成的 toString 带着它；
 *   - JobDatasource：jdbcPassword 在内存里是解密后的明文（AESEncryptHandler 只在读写库时加解密）。
 *
 * 用例同时钉反向的一半：**定位信息必须留下**（谁、哪个数据源、连的哪张库），
 * 否则日志页只剩一句看不懂的掩码，故障查不动 —— 这是本项目的红线"守卫不许把功能修坏"。
 */
public class CredentialEntityToStringTest {

    private static final String LOGIN_PWD = "Passw0rd!plain";
    private static final String DS_PWD = "DsS3cret#MySql";

    @Test
    public void jwtUserToStringCarriesNoCredential() {
        JobUser user = new JobUser();
        user.setId(9);
        user.setUsername("ops_ro");
        user.setPassword(LOGIN_PWD);
        user.setRole(null);

        String printed = new JwtUser(user).toString();

        Assert.assertFalse("登录凭据不许从 principal 的 toString 出去：" + printed, printed.contains(LOGIN_PWD));
        Assert.assertTrue("口令一栏要显式标出来，别让人以为这列是空的：" + printed,
                printed.contains("password='[PROTECTED]'"));
    }

    @Test
    public void jwtUserKeepsIdentityForDiagnostics() {
        JobUser user = new JobUser();
        user.setId(9);
        user.setUsername("ops_ro");
        user.setPassword(LOGIN_PWD);
        user.setRole("1");

        String printed = new JwtUser(user).toString();

        Assert.assertTrue("还得认得出是哪个账号：" + printed, printed.contains("username='ops_ro'"));
        Assert.assertTrue("还得认得出是哪个用户 id：" + printed, printed.contains("id=9"));
        // role 归一化后仍是角色名（管理员判定与登录排障都看这一栏）
        Assert.assertTrue("角色栏要照常可诊断：" + printed, printed.contains("ROLE_ADMIN"));
    }

    @Test
    public void loginUserToStringCarriesNoPlaintextPassword() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUsername("ops_ro");
        loginUser.setPassword(LOGIN_PWD);
        loginUser.setRememberMe(1);

        String printed = loginUser.toString();

        Assert.assertFalse("登录请求体的明文口令不许进 toString：" + printed, printed.contains(LOGIN_PWD));
        Assert.assertTrue("其余字段照常打印：" + printed, printed.contains("username=ops_ro"));
        Assert.assertTrue("rememberMe 这种排障线索不许一起被排掉：" + printed, printed.contains("rememberMe=1"));
    }

    @Test
    public void jobDatasourceToStringCarriesNoJdbcPassword() {
        JobDatasource datasource = new JobDatasource();
        datasource.setId(3L);
        datasource.setDatasourceName("订单主库");
        datasource.setJdbcUsername("datax_ro");
        datasource.setJdbcPassword(DS_PWD);
        datasource.setJdbcUrl("jdbc:mysql://10.0.0.5:3306/orders");

        String printed = datasource.toString();

        Assert.assertFalse("解密后的明文口令不许进 toString：" + printed, printed.contains(DS_PWD));
        Assert.assertTrue("连的哪个库要认得出：" + printed, printed.contains("10.0.0.5:3306/orders"));
        Assert.assertTrue("数据源名要认得出：" + printed, printed.contains("datasourceName=订单主库"));
    }
}
