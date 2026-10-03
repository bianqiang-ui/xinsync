package com.wugui.datax.admin.entity;

import com.wugui.datax.admin.security.AccessControl;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

/**
 * Created by jingwk on 2019/11/17
 */
public class JwtUser implements UserDetails {

    private Integer id;
    private String username;
    private String password;
    private Collection<? extends GrantedAuthority> authorities;

    public JwtUser() {
    }

    // 写一个能直接使用user创建jwtUser的构造器
    public JwtUser(JobUser user) {
        id = user.getId();
        username = user.getUsername();
        password = user.getPassword();
        // job_user.role 是可空列，而 SimpleGrantedAuthority 不接受空串——历史上 role 没填的账号会直接在登录时抛异常。
        // 归一化统一走 AccessControl：缺省按最小权限当普通用户，老口径的 '1' 翻成 ROLE_ADMIN。
        authorities = Collections.singleton(new SimpleGrantedAuthority(AccessControl.normalizeRole(user.getRole())));
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    @Override
    public String toString() {
        // Spring Security 的 AbstractAuthenticationToken.toString() 会把 principal 原样拼进去
        // （"Principal: " + principal），而 DaoAuthenticationProvider / 各类 filter 在 DEBUG 下就会打这条。
        // Spring 自己的 UserDetails 实现（org...userdetails.User）刻意不打 credentials，本类照同一口径：
        // 整段不出现，连长度也不给 —— 这一栏是登录凭据（历史行还可能是明文），不是 RPC 令牌那种
        // "给个长度好排查"的场景。
        return "JwtUser{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", password='[PROTECTED]'" +
                ", authorities=" + authorities +
                '}';
    }

}
