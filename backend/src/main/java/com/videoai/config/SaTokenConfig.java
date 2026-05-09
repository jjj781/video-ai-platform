package com.videoai.config;

import cn.dev33.satoken.filter.SaServletFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.strategy.SaStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token 配置
 * 开发阶段: 所有接口放行
 */
@Configuration
public class SaTokenConfig {

    @Bean
    public SaServletFilter getSaServletFilter() {
        return new SaServletFilter()
                // 拦截所有路径
                .addInclude("/**")
                // 排除静态资源和Swagger
                .addExclude("/favicon.ico", "/doc.html", "/webjars/**", "/v3/api-docs/**")
                // 认证处理: 所有请求放行 (开发阶段)
                .setAuth(obj -> {
                    // 空实现 = 所有请求放行
                })
                // 异常处理
                .setError(e -> "{\"code\":401,\"message\":\"未登录\"}");
    }
}
