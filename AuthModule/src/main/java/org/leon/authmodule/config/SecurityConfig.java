package org.leon.authmodule.config;

import jakarta.servlet.http.HttpServletResponse; // 注意：Spring Boot 3 使用 jakarta，Boot 2 使用 javax
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
// 1. 关闭 CSRF（JWT 无状态项目必须关闭，否则 POST 请求会被拦截）
                .csrf(csrf -> csrf.disable())

// 2. 关闭 Session（JWT 无状态项目不需要 Session）
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

// 3. 配置路由权限（⚠️ 确保这里的大小写与 Controller 中的 @RequestMapping 完全一致！）
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/login", "/auth/register","/auth/logout").permitAll() // 统一用小写
                        .anyRequest().authenticated()
                )

// 4.  自定义异常处理（前后端分离必加，否则 401/403 返回空页面或空 Body）
                .exceptionHandling(ex -> ex
// 处理 401：未登录或 Token 过期
                                .authenticationEntryPoint((request, response, authException) -> {
                                    response.setContentType("application/json;charset=UTF-8");
                                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                    response.getWriter().write("{\"code\":401,\"msg\":\"未登录或Token已过期\"}");
                                })
// 处理 403：权限不足
                                .accessDeniedHandler((request, response, accessDeniedException) -> {
                                    response.setContentType("application/json;charset=UTF-8");
                                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                    response.getWriter().write("{\"code\":403,\"msg\":\"权限不足\"}");
                                })
                );

// 5. 如果你有自定义的 JWT 过滤器，记得在这里加进去（目前你还没写的话可以先注释掉）
// .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}