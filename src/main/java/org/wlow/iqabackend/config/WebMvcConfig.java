package org.wlow.iqabackend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC配置.
 * <p>
 * 当前系统使用两层拦截器:
 * 1. AuthInterceptor: 负责所有受保护接口的JWT校验与CurrentUser写入
 * 2. AdminInterceptor: 仅负责/admin/** 路由的管理员权限校验
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;
    private final AdminInterceptor adminInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/**", "/error");

        // 管理员拦截器必须注册在JWT拦截器之后, 这样它才能读取到AuthInterceptor写入的CurrentUser.role.
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin/**");
    }
}

