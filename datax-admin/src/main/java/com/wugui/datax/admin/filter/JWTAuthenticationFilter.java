package com.wugui.datax.admin.filter;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wugui.datatx.core.biz.model.ReturnT;
import com.wugui.datax.admin.core.util.I18nUtil;
import com.wugui.datax.admin.entity.JwtUser;
import com.wugui.datax.admin.entity.LoginUser;
import com.wugui.datax.admin.util.JwtTokenUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static com.wugui.datatx.core.util.Constants.SPLIT_COMMA;

/**
 * Created by jingwk on 2019/11/17
 */
@Slf4j
public class JWTAuthenticationFilter extends UsernamePasswordAuthenticationFilter {

    private final ThreadLocal<Boolean> rememberMe = new ThreadLocal<>();
    private AuthenticationManager authenticationManager;

    public JWTAuthenticationFilter(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
        super.setFilterProcessesUrl("/api/auth/login");
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request,
                                                HttpServletResponse response) throws AuthenticationException {

        // 从输入流中获取到登录的信息
        LoginUser loginUser;
        try {
            loginUser = new ObjectMapper().readValue(request.getInputStream(), LoginUser.class);
        } catch (IOException e) {
            // 运行期实测：这一支原来 return null，而父类把 null 解释成"子类还没走完"直接 return，
            // 于是请求既不认证也不拒绝 —— 客户端拿到 HTTP 200 + Content-Length: 0。
            // 请求体读不进来是登录失败的一种，必须交给失败分支写出一个带原因的身体。
            rememberMe.remove();
            // 只记异常类型，不记 message 也不记栈。实测这一路 Jackson 的消息里只有
            // `[Source: (…CoyoteInputStream); line: 1, column: 54]` 这样的定位、没有原文片段
            // （口令因此没进日志，本轮 grep 过：console.out 里 `123456` 命中 0 次）；
            // 但这个"有没有原文"取决于请求体是以什么来源喂进解析器的 —— 换成先读成
            // byte[]/String（很常见的一次重构）就会带上出错位置附近的原文，而这一栏的邻居
            // 就是 password 明文。按最小面记：类型名足够定位，内容一律不进日志。
            logger.error("Login request body could not be read: " + e.getClass().getName());
            throw new AuthenticationServiceException("Login request body could not be read");
        }
        rememberMe.set(loginUser.getRememberMe());
        return authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginUser.getUsername(), loginUser.getPassword(), new ArrayList<>())
        );
    }

    /**
     * 登录失败该说哪一句。
     *
     * 拆成不碰 I18nUtil 的纯静态方法，是为了让"请求体读不进来"与"账号或密码不对"这两档
     * 各自可单测；判定只有一处，调用点在 unsuccessfulAuthentication。
     */
    static String loginFailureMessageKey(AuthenticationException failed) {
        // AuthenticationServiceException 只由上面"请求体不可读"那一支抛出。其余一律沿用原来的
        // "账号或密码错误" —— 包括"账号不存在"，不给外部任何一条能区分账号有无的额外信息。
        return failed instanceof AuthenticationServiceException
                ? "login_param_malformed" : "login_param_invalid";
    }

    /**
     * 把失败键翻成客户端看得见的那句话，取不到文案时逐层退回。
     *
     * 为什么不直接信 i18n 一定取得到：部署包的文案不在 jar 里，而在 conf/i18n/ 下
     * （见 src/main/assembly/deploy.xml 的 fileSet 与 bin/datax-admin.sh 的
     * CLASSPATH=lib/*:conf:.），那是运维可编辑、也可被单独换掉的外部文件。实测缺键时
     * 响应体是 12 字节的 {"code":500} —— 有状态码、没有一个字的理由，与本次要消灭的
     * "静默失败"只差一层皮。层次：专用键 -> 通用键 -> 字面，最后一层保证身体永远带理由。
     */
    static String loginFailureMessageFor(String key) {
        return firstNonBlank(I18nUtil.getString(key),
                I18nUtil.getString("login_param_invalid"), "Login request failed");
    }

    static String loginFailureMessage(AuthenticationException failed) {
        return loginFailureMessageFor(loginFailureMessageKey(failed));
    }

    /** 取第一个非空非全白值的候选；全空才返回空串（上面的调用点备着字面兜底，走不到）。 */
    static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                return candidate;
            }
        }
        return "";
    }

    // 成功验证后调用的方法
    // 如果验证成功，就生成token并返回
    @Override
    protected void successfulAuthentication(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain,
                                            Authentication authResult) throws IOException {

        try {
            JwtUser jwtUser = (JwtUser) authResult.getPrincipal();
            // rememberMe 是可选字段，前端不传时为 null，直接拆箱会 NPE（实测登录返回 403 空响应）
            boolean isRemember = Boolean.TRUE.equals(rememberMe.get());

            String role = "";
            Collection<? extends GrantedAuthority> authorities = jwtUser.getAuthorities();
            for (GrantedAuthority authority : authorities) {
                role = authority.getAuthority();
            }

            String token = JwtTokenUtils.createToken(jwtUser.getId(), jwtUser.getUsername(), role, isRemember);
            response.setHeader("token", JwtTokenUtils.TOKEN_PREFIX + token);
            response.setCharacterEncoding("UTF-8");
            Map<String, Object> maps = new HashMap<>();
            maps.put("data", JwtTokenUtils.TOKEN_PREFIX + token);
            maps.put("roles", role.split(SPLIT_COMMA));
            response.getWriter().write(JSON.toJSONString(new ReturnT<>(maps)));
        } finally {
            // 本过滤器是单例，这个线程变量挂在 Tomcat 工作线程上、线程跨请求复用。
            // 只在 attemptAuthentication 里 set、出口不清，下一位复用同一线程的请求即使没传
            // rememberMe，也会继承上一位的 true —— 发出去的 token 从 24 小时悄悄变成 7 天。
            // 清线程变量这件事与批次7 的 ShardingUtil 同一条口径。
            rememberMe.remove();
        }
    }

    @Override
    protected void unsuccessfulAuthentication(HttpServletRequest request,
                                              HttpServletResponse response,
                                              AuthenticationException failed) throws IOException, ServletException {
        try {
            response.setCharacterEncoding("UTF-8");
            // HTTP 状态维持 200、失败信息放在响应体的 code 里：装机前端的响应拦截器就是按
            // body 的 code 判失败的，改成 401 会让它走另一条分支、错误文案反而变空。
            response.getWriter().write(JSON.toJSON(
                    new ReturnT<>(ReturnT.FAIL_CODE, loginFailureMessage(failed))).toString());
        } finally {
            rememberMe.remove();
        }
    }
}
