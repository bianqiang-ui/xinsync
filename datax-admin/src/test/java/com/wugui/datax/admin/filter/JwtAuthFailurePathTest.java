package com.wugui.datax.admin.filter;

import com.wugui.datax.admin.controller.JobLogController;
import com.wugui.datax.admin.security.AccessControl;
import com.wugui.datax.admin.util.JwtTokenUtils;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * JWT 的三条"异常路径"。
 *
 * 这批代码接了归属判定之后，"从 token 里取调用者 id"变成每个受管接口的必经步骤，
 * 于是它本身的崩溃面被放大：原先一个缺头 / 一个手工拼造的 Bearer 就能让任意接口 500，
 * 前端只显示"系统异常"，日志里是一条与业务无关的 JWT 解析栈。
 */
public class JwtAuthFailurePathTest {

    // HS512 在 jjwt 0.9 里要求密钥不短于 64 字节，测试用的串特意凑够长度
    private static final String SECRET = "unit-test-secret-0123456789abcdef-xyz-0123456789abcdef-xyz-012345";
    private static final String FOREIGN_SECRET = "another-secret-0123456789abcdef-xyz!-0123456789abcdef-xyz!-012345";
    private static final int USER_ID = 7;
    private static final String USERNAME = "dev01";

    private final JobLogController controller = new JobLogController();

    @Before
    public void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        JwtTokenUtils.configureSecret(SECRET);
    }

    @Test
    public void legitimateTokenStillYieldsCallerId() throws Exception {
        String token = JwtTokenUtils.createToken(USER_ID, USERNAME, AccessControl.ROLE_NORMAL, false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(JwtTokenUtils.TOKEN_HEADER, JwtTokenUtils.TOKEN_PREFIX + token);

        assertEquals("正常 token 的 id 必须照旧解出来，白名单化不能把合法用户挡在门外",
                Integer.valueOf(USER_ID), controller.getCurrentUserId(request));
    }

    /** 这就是 NoSuchElementException 那一条：没有 Authorization 头不是异常，是没有身份 */
    @Test
    public void missingHeaderIsNoIdentityNotCrash() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertNull("缺头必须返回 null（由 AccessControl 按最小权限拒绝），不得抛 NoSuchElementException",
                controller.getCurrentUserId(request));
    }

    @Test
    public void malformedHeaderIsNoIdentityNotCrash() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(JwtTokenUtils.TOKEN_HEADER, "Basic dXNlcjpwdw==");

        assertNull("不是 Bearer 前缀的头同样不能把请求顶成 500", controller.getCurrentUserId(request));
    }

    /**
     * 签名不符的 token（换过密钥、或手工拼造）：getUserId 必须安静地返回 null。
     * 这条同时钉住"异常不能穿透到 controller 之外"。
     */
    @Test
    public void foreignSignatureIsNoIdentityNotCrash() throws Exception {
        String forged = Jwts.builder()
                .setSubject(USER_ID + "," + USERNAME)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(SignatureAlgorithm.HS512, FOREIGN_SECRET.getBytes(StandardCharsets.UTF_8))
                .compact();

        assertNull("签名对不上的 token 不能抛异常", JwtTokenUtils.getUserId(forged));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(JwtTokenUtils.TOKEN_HEADER, JwtTokenUtils.TOKEN_PREFIX + forged);
        assertNull(controller.getCurrentUserId(request));
    }

    @Test
    public void invalidTokenIsRejectedAsUnauthorizedAndStopsTheChain() throws Exception {
        JWTAuthorizationFilter filter = newFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(JwtTokenUtils.TOKEN_HEADER, JwtTokenUtils.TOKEN_PREFIX + "not.a.jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        final boolean[] chained = new boolean[]{false};
        FilterChain chain = (req, res) -> chained[0] = true;

        filter.doFilter(request, response, chain);

        assertFalse("解析失败的 token 不得继续走链路", chained[0]);
        assertEquals("未认证要按语义回 401，而不是 HTTP 200 里塞一个失败体",
                401, response.getStatus());
        assertTrue("响应体仍保持 R.failed 形状，前端按 code 判权的逻辑不受影响：" + response.getContentAsString(),
                response.getContentAsString().contains("\"code\""));
    }

    @Test
    public void expiredTokenIsRejectedAsUnauthorized() throws Exception {
        // 用 HS512 + 同一个密钥造一个已经过期的 token，让 isExpiration 真抛出 TokenIsExpiredException
        String expired = Jwts.builder()
                .setSubject(USER_ID + "," + USERNAME)
                .setIssuedAt(new Date(System.currentTimeMillis() - 120_000L))
                .setExpiration(new Date(System.currentTimeMillis() - 60_000L))
                .signWith(SignatureAlgorithm.HS512, SECRET.getBytes(StandardCharsets.UTF_8))
                .compact();

        JWTAuthorizationFilter filter = newFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(JwtTokenUtils.TOKEN_HEADER, JwtTokenUtils.TOKEN_PREFIX + expired);
        MockHttpServletResponse response = new MockHttpServletResponse();
        final boolean[] chained = new boolean[]{false};
        FilterChain chain = (req, res) -> chained[0] = true;

        filter.doFilter(request, response, chain);

        assertFalse(chained[0]);
        assertEquals(401, response.getStatus());
        assertTrue("过期文案要原样带给前端：" + response.getContentAsString(),
                response.getContentAsString().contains("重新登录"));
    }

    /** 没有头时 filter 直接放行给下游，由 Spring Security 的 anyRequest().authenticated() 兜 —— 这条行为不许被我改掉 */
    @Test
    public void requestWithoutHeaderStillGoesDownstream() throws Exception {
        JWTAuthorizationFilter filter = newFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        final boolean[] chained = new boolean[]{false};
        FilterChain chain = (req, res) -> chained[0] = true;

        filter.doFilter(request, response, chain);

        assertTrue(chained[0]);
        assertEquals(200, response.getStatus());
    }

    /**
     * ProviderManager 不接受空的 provider 列表（构造期就校验），所以必须给一个。
     *
     * 说清楚它并不能当断言用：父类 BasicAuthenticationFilter 只有在 Authorization 头以
     * "Basic " 开头时才会调 authenticationManager，而本过滤器只在头以 "Bearer " 开头时才
     * 走到 super.doFilterInternal（见 JWTAuthorizationFilter:41 的提前 return），两者互斥 ——
     * 这条路径上 manager 根本不可达。换句话说："token 无效不继续往下走"是由下面各用例里的
     * 401 状态码 + chain 未被调用钉住的，不是由这个 provider 钉住的。
     * 保留它只是为了满足构造期校验，同时留一个"真有人改出 Basic 分支就会立刻炸给我看"的哨兵。
     */
    private JWTAuthorizationFilter newFilter() {
        AuthenticationProvider mustNotBeUsed = new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication authentication) {
                throw new IllegalStateException("token 无效时不该走到 authenticationManager");
            }

            @Override
            public boolean supports(Class<?> authentication) {
                return false;
            }
        };
        return new JWTAuthorizationFilter(
                new ProviderManager(Collections.singletonList(mustNotBeUsed)));
    }
}
