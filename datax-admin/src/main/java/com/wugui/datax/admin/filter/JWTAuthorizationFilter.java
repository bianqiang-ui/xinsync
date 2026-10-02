package com.wugui.datax.admin.filter;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.extension.api.R;
import com.wugui.datax.admin.exception.TokenIsExpiredException;
import com.wugui.datax.admin.security.AccessControl;
import com.wugui.datax.admin.util.JwtTokenUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

/**
 * Created by jingwk on 2019/11/17
 */
public class JWTAuthorizationFilter extends BasicAuthenticationFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(JWTAuthorizationFilter.class);

    public JWTAuthorizationFilter(AuthenticationManager authenticationManager) {
        super(authenticationManager);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {

        String tokenHeader = request.getHeader(JwtTokenUtils.TOKEN_HEADER);
        // 如果请求头中没有Authorization信息则直接放行
        if (tokenHeader == null || !tokenHeader.startsWith(JwtTokenUtils.TOKEN_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        // 如果请求头中有token，则进行解析，并且设置认证信息
        try {
            UsernamePasswordAuthenticationToken authentication = getAuthentication(tokenHeader);
            if (authentication == null) {
                // token 能解析但没有可用的 subject：原先把 null 塞进 SecurityContext 继续走链路，
                // 于是"未认证"这件事要等到 Spring 的入口点才爆，形态是不透明的 403。
                reject(response, "登录状态无效，请重新登录");
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (TokenIsExpiredException e) {
            reject(response, e.getMessage());
            return;
        } catch (Exception e) {
            // 签名不符、格式畸形、算法不对…… 这些都从 JwtParser 直接抛出来。
            // 上一版只 catch TokenIsExpiredException，剩下的异常一路顶到容器 → HTTP 500 + 堆栈，
            // 前端显示"系统异常"、日志里却是一个跟业务无关的 JWT 解析栈。
            LOGGER.warn("JWT 校验失败，按未认证处理：{}", e.getClass().getSimpleName());
            reject(response, "登录状态无效，请重新登录");
            return;
        }
        super.doFilterInternal(request, response, chain);
    }

    /**
     * 未认证的响应：状态码按语义给 401（而不是"HTTP 200 里塞一个失败体"），
     * 响应体仍保持原来的 {@code R.failed} 形状，前端按 code 判权的逻辑不受影响。
     */
    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=utf-8");
        response.getWriter().write(JSON.toJSONString(R.failed(message)));
        response.getWriter().flush();
    }

    // 这里从token中获取用户信息并新建一个token
    private UsernamePasswordAuthenticationToken getAuthentication(String tokenHeader) throws TokenIsExpiredException {
        String token = tokenHeader.replace(JwtTokenUtils.TOKEN_PREFIX, "");
        boolean expiration = JwtTokenUtils.isExpiration(token);
        if (expiration) {
            throw new TokenIsExpiredException("登录时间过长，请退出重新登录");
        }
        else {
            String username = JwtTokenUtils.getUsername(token);
            String role = JwtTokenUtils.getUserRole(token);
            if (username != null) {
                // role 从 token 里取，可能是 null 或空串（老 token、或建号时没填 role）。
                // SimpleGrantedAuthority 对空串直接抛 IllegalArgumentException，而这个 filter 只 catch
                // TokenIsExpiredException，一抛就是整个请求 500 —— 每个接口都挂。
                return new UsernamePasswordAuthenticationToken(username, null,
                        Collections.singleton(new SimpleGrantedAuthority(AccessControl.normalizeRole(role)))
                );
            }
        }
        return null;
    }

}
