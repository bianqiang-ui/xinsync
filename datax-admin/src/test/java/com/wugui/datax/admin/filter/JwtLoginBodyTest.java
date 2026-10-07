package com.wugui.datax.admin.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wugui.datax.admin.core.util.I18nUtil;
import com.wugui.datax.admin.entity.JobUser;
import com.wugui.datax.admin.entity.JwtUser;
import com.wugui.datax.admin.entity.LoginUser;
import com.wugui.datax.admin.util.JwtTokenUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 登录请求体这一侧的行为（批次15 运行期冒烟实测出来的两条缺陷）。
 *
 * 冒烟那一跑是把装机包里的 admin 真起在一个真 MySQL 8 上，然后按请求体矩阵打
 * POST /api/auth/login：
 *   1) rememberMe 传 true/false（JSON 里布尔量的自然写法）⇒ HTTP 200 + Content-Length: 0，
 *      既没有 token 也没有原因，用户侧的表现就是"点了登录没反应"；服务端只留一行日志。
 *      根因是两处叠起来的：字段声明成 Integer 接不住布尔量，而 IOException 又被咽掉、return null，
 *      父类把 null 解释成"子类还没走完"就静默结束了整条链。
 *   2) 顺着这条空响应找到同族的第二条：rememberMe 挂在 Tomcat 工作线程上、只 set 不清，
 *      线程复用时会把上一位的"记住我 = 7 天"带给下一位。
 *
 * 这两条都不是读代码读出来的 —— 单看形状什么都对（try/catch 在、字段在、token 也在），
 * 只有真发一次请求才知道"接住了异常"和"回答了客户端"是两件事。
 */
public class JwtLoginBodyTest {

    // HS512 在 jjwt 0.9 里要求密钥不短于 64 字节，测试用的串特意凑够长度
    private static final String SECRET = "unit-test-secret-0123456789abcdef-xyz-0123456789abcdef-xyz-012345";
    private static final long ONE_DAY = 86400L;
    private static final long SEVEN_DAYS = 7 * ONE_DAY;

    /** 一份合法请求体的前半段：把变动的部分只剩 rememberMe 这一栏 */
    private static final String CRED = "{\"username\":\"dev01\",\"password\":\"pw123456\"";

    private final ObjectMapper mapper = new ObjectMapper();

    /** 同一个 filter 实例 = 同一个单例 Bean，两次请求共用它才谈得上线程变量残留 */
    private final boolean[] rejectNext = new boolean[]{false};
    private JWTAuthenticationFilter filter;

    @Before
    public void setUp() {
        JwtTokenUtils.configureSecret(SECRET);
        JobUser user = new JobUser();
        user.setId(7);
        user.setUsername("dev01");
        user.setPassword("库里的口令散列，认证由下面的假 manager 代答");
        user.setRole("0");
        final JwtUser principal = new JwtUser(user);
        filter = new JWTAuthenticationFilter(new AuthenticationManager() {
            @Override
            public Authentication authenticate(Authentication authentication) {
                if (rejectNext[0]) {
                    rejectNext[0] = false;
                    throw new BadCredentialsException("password mismatch");
                }
                return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            }
        });
    }

    /**
     * 反向红线（这条最容易踩）：修"布尔量接不住"不能把装机 UI 原来的 1/0 写法改坏 ——
     * UI 发的就是 1，它要是变成登录不上，等于用一个 bug 换掉一个功能。
     */
    @Test
    public void rememberMeAcceptsBothFlagShapes() throws Exception {
        assertEquals(Boolean.TRUE, mapper.readValue(CRED + ",\"rememberMe\":1}", LoginUser.class).getRememberMe());
        assertEquals(Boolean.FALSE, mapper.readValue(CRED + ",\"rememberMe\":0}", LoginUser.class).getRememberMe());
        assertEquals(Boolean.TRUE, mapper.readValue(CRED + ",\"rememberMe\":true}", LoginUser.class).getRememberMe());
        assertEquals(Boolean.FALSE, mapper.readValue(CRED + ",\"rememberMe\":false}", LoginUser.class).getRememberMe());
        assertNull("不传这一栏 = 不记住我，不是错误",
                mapper.readValue(CRED + "}", LoginUser.class).getRememberMe());
    }

    @Test
    public void numberAndBooleanShapesIssueTheSameToken() throws Exception {
        long fromOne = tokenLifetimeOf(CRED + ",\"rememberMe\":1}");
        long fromTrue = tokenLifetimeOf(CRED + ",\"rememberMe\":true}");
        assertTrue("1 与 true 必须是同一档（记住我 = 7 天），实测 1=" + fromOne + " true=" + fromTrue,
                fromOne >= SEVEN_DAYS - 3600 && fromTrue >= SEVEN_DAYS - 3600);
        assertTrue("不传该字段仍旧是默认 24 小时：" + tokenLifetimeOf(CRED + "}"),
                tokenLifetimeOf(CRED + "}") < 2 * ONE_DAY);
    }

    /** 缺陷本体：请求体读不进来时不许"没反应"，必须落到失败分支、写出带原因的身体 */
    @Test
    public void unreadableBodyFailsLoudlyInsteadOfSilentEmptyResponse() throws Exception {
        MockHttpServletRequest request = loginRequest(CRED + ",\"rememberMe\":\"not-a-flag\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException expected = null;
        try {
            Authentication result = filter.attemptAuthentication(request, response);
            fail("请求体读不进来却返回 " + result + " —— 父类拿到 null 就静默结束，"
                    + "客户端收到的是实测到的 HTTP 200 + 零字节");
        } catch (AuthenticationServiceException e) {
            expected = e;
        }
        filter.unsuccessfulAuthentication(request, response, expected);

        String body = response.getContentAsString();
        assertTrue("失败响应体不许为空，也不许只带个 code：" + body, body.contains("\"code\":500"));
        String message = I18nUtil.getString(JWTAuthenticationFilter.loginFailureMessageKey(expected));
        assertNotNull("文案键必须真能查到值，否则 msg 是 null（前端显示空错误）", message);
        assertTrue("要给\"请求格式不对\"这一档：" + body, body.contains(message));
        assertFalse("明文口令不许进响应体：" + body, body.contains("pw123456"));
    }

    @Test
    public void failureMessageKeyOnlySeparatesMalformedBody() throws Exception {
        assertEquals("login_param_malformed",
                JWTAuthenticationFilter.loginFailureMessageKey(new AuthenticationServiceException("x")));
        assertEquals("账号不存在与口令错必须是同一句话，否则登录接口成了账号枚举器",
                "login_param_invalid", JWTAuthenticationFilter.loginFailureMessageKey(
                        new UsernameNotFoundException("x")));
        assertEquals("login_param_invalid",
                JWTAuthenticationFilter.loginFailureMessageKey(new BadCredentialsException("x")));
    }

    /** 两份语言文件都得有这条新文案：缺一份就对应语言下 msg 直接是 null */
    @Test
    public void bothFailureKeysExistInBothLocaleFiles() throws Exception {
        Properties zh = loadLocale("i18n/message.properties");
        Properties en = loadLocale("i18n/message_en.properties");
        for (String key : new String[]{"login_param_malformed", "login_param_invalid"}) {
            assertText(key, zh.getProperty(key), "中文");
            assertText(key, en.getProperty(key), "英文");
        }
        assertFalse("两条失败提示必须不同，否则分档没有意义",
                zh.getProperty("login_param_malformed").equals(zh.getProperty("login_param_invalid")));
    }

    /**
     * conf/i18n 缺键时不许把失败响应写成"只有状态码、没有理由"。
     *
     * 这一条不是假想：换 jar 不换 conf 跑过一次，坏体响应就退化成 {"code":500}（12 字节）。
     */
    @Test
    public void missingI18nKeyFallsBackToGenericText() {
        String malformed = JWTAuthenticationFilter.loginFailureMessageFor("login_param_malformed");
        String generic = JWTAuthenticationFilter.loginFailureMessageFor("login_param_invalid");
        assertFalse("专用键的文案不许是空串：" + malformed, malformed.trim().isEmpty());
        assertFalse("两档必须真的给两句话，否则分键没有意义：" + malformed, malformed.equals(generic));
        assertEquals("旧 conf 里没有新键时，退回通用键的文案而不是 null", generic,
                JWTAuthenticationFilter.loginFailureMessageFor("login_param_absent_in_old_conf"));
    }

    /** 最后一层的可测性：分层写成纯静态，就不必真去删运维手上那份 conf 才能验它 */
    @Test
    public void blankCandidatesNeverWinTheFallbackChain() {
        assertEquals("第一个非空值胜出", "first",
                JWTAuthenticationFilter.firstNonBlank(null, "   ", "\t", "first", "never-seen"));
        assertEquals("全为空串/空白才允许空串 —— 而调用点最后一层备的是字面量，退不到这里", "",
                JWTAuthenticationFilter.firstNonBlank(null, "  ", "\n"));
    }

    /**
     * 缺键 conf 下的完整失败分支仍然要带理由 —— 这一条钉的是调用点，不是分层函数本身。
     *
     * 分工：missingI18nKeyFallsBackToGenericText 钉分层，本条钉"写响应的那一行确实走了分层"。
     * 反证 L9（调用点改回裸 I18nUtil.getString）只有这一条会红。
     */
    @Test
    public void staleConfStillProducesAReadableReason() throws Exception {
        Properties cached = I18nUtil.loadI18nProp();
        Field propField = I18nUtil.class.getDeclaredField("prop");
        propField.setAccessible(true);
        try {
            propField.set(null, new Properties());
            MockHttpServletRequest request = loginRequest(CRED + ",\"rememberMe\":\"nope\"}");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AuthenticationException expected = null;
            try {
                filter.attemptAuthentication(request, response);
                fail("请求体读不进来必须抛出异常，否则失败分支根本不会被调用");
            } catch (AuthenticationServiceException e) {
                expected = e;
            }
            filter.unsuccessfulAuthentication(request, response, expected);
            String body = response.getContentAsString();
            assertTrue("conf 缺键时失败响应不许退化成只有状态码：" + body,
                    body.contains("Login request failed"));
            assertFalse("明文口令不许进响应体：" + body, body.contains("pw123456"));
        } finally {
            // 这是进程级的静态缓存，不清回去后面每条用例读到的都是空文案表
            propField.set(null, cached);
        }
    }

    /** 第二条缺陷：线程复用时的 rememberMe 残留 —— 成功分支也要清 */
    @Test
    public void successfulLoginDoesNotLeaveRememberMeOnTheThread() throws Exception {
        long remembered = tokenLifetimeOf(CRED + ",\"rememberMe\":true}");
        assertTrue("第一次的记住我要真生效（清线程变量不许把这功能清掉）：" + remembered,
                remembered >= SEVEN_DAYS - 3600);

        long ordinary = tokenLifetimeOf(CRED + "}");
        assertTrue("同一线程紧接着的下一次登录没传 rememberMe，token 不许继承上一位的 7 天，实测 "
                + ordinary + " 秒", ordinary < 2 * ONE_DAY);
    }

    /**
     * 成功出口必须把线程变量交回去：只看"这一次之后线程上还剩什么"，不看下一位的 token。
     *
     * 与上面那条的分工必须写清楚，否则会被当成重复用例：上面那条量的是可观测行为（7 天有没有被继承），
     * 而这个观测值其实由 attemptAuthentication 里那句无条件 rememberMe.set(...) 决定 —— 本轮反证实测：
     * 把成功分支的 finally remove() 撤掉，上面那条照样全绿。清理这一步现在不承重，但它是契约：
     * 那句 set() 一旦被重构成"传了值才 set"（很自然的一次改动），残留立刻重新变成可达缺陷，
     * 而只观测 token 的用例一条都不会响。所以线程归还单独钉死，不许靠邻居那行代码顺带救。
     */
    @Test
    public void successfulLoginHandsTheThreadBackClean() throws Exception {
        MockHttpServletRequest request = loginRequest(CRED + ",\"rememberMe\":true}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication auth = filter.attemptAuthentication(request, response);
        filter.successfulAuthentication(request, response, new MockFilterChain(), auth);
        assertNotNull("这一次必须真发出 token，否则下面的线程断言是空跑",
                response.getHeader("token"));
        assertNull("成功出口必须把 rememberMe 从工作线程上摘掉：请求态不许跨请求残留", rememberedOnThread());
    }

    /** 失败分支同样要清：一次"口令错"之后不许把 rememberMe 遗留在该线程上 */
    @Test
    public void failedLoginDoesNotLeaveRememberMeOnTheThread() throws Exception {
        rejectNext[0] = true;
        MockHttpServletRequest request = loginRequest(CRED + ",\"rememberMe\":true}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            filter.attemptAuthentication(request, response);
            fail("口令错时必须抛出 AuthenticationException，过滤器才会走失败分支");
        } catch (BadCredentialsException expected) {
            filter.unsuccessfulAuthentication(request, response, expected);
        }
        assertFalse("失败分支不许再把空身体留给客户端：" + response.getContentAsString(),
                response.getContentAsString().isEmpty());
        assertNull("失败之后这条线程上不该还留着 rememberMe", rememberedOnThread());

        long ordinary = tokenLifetimeOf(CRED + "}");
        assertTrue("失败请求留下的 rememberMe 不许把下一次登录抬成 7 天，实测 " + ordinary + " 秒",
                ordinary < 2 * ONE_DAY);
    }

    // ------------------------------------------------------------------ 工具

    /** 走完一次真登录（请求体解析 -> 认证 -> 发 token），返回那枚 token 的有效期秒数 */
    private long tokenLifetimeOf(String body) throws Exception {
        MockHttpServletRequest request = loginRequest(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication auth = filter.attemptAuthentication(request, response);
        assertNotNull("attemptAuthentication 必须交回认证结果", auth);
        filter.successfulAuthentication(request, response, new MockFilterChain(), auth);

        String header = response.getHeader("token");
        assertNotNull("成功登录必须在 token 响应头带回 JWT：" + body, header);
        assertTrue("响应头要带 Bearer 前缀（前端原样存进后续请求头）：" + header,
                header.startsWith(JwtTokenUtils.TOKEN_PREFIX));
        Claims claims = Jwts.parser()
                .setSigningKey(SECRET.getBytes(StandardCharsets.UTF_8))
                .parseClaimsJws(header.substring(JwtTokenUtils.TOKEN_PREFIX.length()))
                .getBody();
        return (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000L;
    }

    private Object rememberedOnThread() throws Exception {
        Field field = JWTAuthenticationFilter.class.getDeclaredField("rememberMe");
        field.setAccessible(true);
        return ((ThreadLocal<?>) field.get(filter)).get();
    }

    private static MockHttpServletRequest loginRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static Properties loadLocale(String path) throws Exception {
        // 与 I18nUtil 同一口径按 UTF-8 读 —— 这两份文件里写的就是中文原文
        return PropertiesLoaderUtils.loadProperties(
                new EncodedResource(new ClassPathResource(path), StandardCharsets.UTF_8));
    }

    private static void assertText(String key, String value, String language) {
        assertNotNull(key + " 在" + language + "语言文件里没有，响应体的 msg 会是 null", value);
        assertFalse(key + " 的" + language + "文案不许是空串", value.trim().isEmpty());
    }
}
