package org.wlow.iqabackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.addAllowedOriginPattern("*"); // 允许所有请求源
        cors.addAllowedHeader("*"); // 允许所有请求头
        cors.addAllowedMethod("GET"); // 允许发送GET和POST请求
        cors.addAllowedMethod("POST");
        cors.addAllowedMethod("PUT");
        cors.addAllowedMethod("DELETE");
        cors.addAllowedMethod("OPTIONS");
        cors.addAllowedMethod("PATCH"); // 允许PATCH请求
        cors.setAllowCredentials(true); // 允许跨域发送cookie

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors); // 封装
        return new CorsFilter(source);
    }
}
