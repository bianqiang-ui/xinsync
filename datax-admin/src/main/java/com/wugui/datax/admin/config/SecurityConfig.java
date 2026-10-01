package com.wugui.datax.admin.config;


import com.wugui.datatx.core.util.Constants;
import com.wugui.datax.admin.filter.JWTAuthenticationFilter;
import com.wugui.datax.admin.filter.JWTAuthorizationFilter;
import com.wugui.datax.admin.service.impl.UserDetailsServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.annotation.web.configurers.ExpressionUrlAuthorizationConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

/**
 * Created by jingwk on 2019/11/17
 */
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Autowired
    private UserDetailsService userDetailsService;

    /**
     * 允许跨域调用 admin 接口的前端来源，逗号分隔。默认 "*" 且不下发凭证放行，
     * 配置为具体来源时才会开启 allowCredentials。
     */
    @Value("${datax.security.corsAllowedOrigins:*}")
    private String corsAllowedOrigins;

    /**
     * 是否匿名开放 swagger 文档；生产环境应保持 false，避免接口清单外泄。
     */
    @Value("${datax.security.swaggerAnonymous:false}")
    private boolean swaggerAnonymous;

    @Bean
    UserDetailsService customUserService(){ //注册UserDetailsService 的bean
        return new UserDetailsServiceImpl();
    }



    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.userDetailsService(userDetailsService).passwordEncoder(bCryptPasswordEncoder());
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry registry =
                http.cors().and().csrf().disable()
                        .authorizeRequests()
                        .antMatchers("/static/**", "/index.html", "/favicon.ico", "/avatar.jpg").permitAll()
                        .antMatchers("/api/callback", "/api/processCallback", "/api/registry", "/api/registryRemove").permitAll();
        if (swaggerAnonymous) {
            registry.antMatchers("/doc.html", "/swagger-resources/**", "/webjars/**", "/*/api-docs").anonymous();
        }
        registry.anyRequest().authenticated()
                .and()
                .addFilter(new JWTAuthenticationFilter(authenticationManager()))
                .addFilter(new JWTAuthorizationFilter(authenticationManager()))
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        final UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedMethods(Arrays.asList(Constants.SPLIT_STAR));
        config.setAllowedHeaders(Arrays.asList(Constants.SPLIT_STAR));
        if (Constants.SPLIT_STAR.equals(corsAllowedOrigins == null ? Constants.SPLIT_STAR : corsAllowedOrigins.trim())) {
            // 通配来源下必须关闭凭证，否则任意站点可带着用户 Cookie 打接口
            config.setAllowedOrigins(Arrays.asList(Constants.SPLIT_STAR));
            config.setAllowCredentials(false);
        } else {
            config.setAllowedOrigins(Arrays.asList(corsAllowedOrigins.trim().split(Constants.SPLIT_COMMA)));
            config.setAllowCredentials(true);
        }
        source.registerCorsConfiguration("/**", config);
        return source;
    }

}
