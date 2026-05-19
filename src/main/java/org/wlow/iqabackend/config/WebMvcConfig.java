package org.wlow.iqabackend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

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
    @Value("${file-storage.root-dir}")
    private String fileStorageRootDir;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/**",
                        "/error",
                        "/resource/image/**",
                        "/actuator/health",
                        "/actuator/info"
                );

        // 管理员拦截器必须注册在JWT拦截器之后, 这样它才能读取到AuthInterceptor写入的CurrentUser.role.
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 图片资源改成公开静态映射后, 前端拿到/resource/image/{uuid}.{ext} 就可以直接访问.
        // 这里把该URL前缀映射到本地upload/image目录, 避免再经过数据库查fileEntryId和业务Controller转发文件流.
        Path imageDirectory = Paths.get(fileStorageRootDir).toAbsolutePath().normalize().resolve("image");
        registry.addResourceHandler("/resource/image/**")
                .addResourceLocations(imageDirectory.toUri().toString());
    }
}

