package org.wlow.iqabackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 模型API客户端配置.
 * 构建基于配置baseUrl的RestClient.
 */
@Configuration
public class ModelApiConfig {
    @Value("${model-api.base-url}")
    private String baseUrl;
    /**
     * AssessService调用Python端点使用的RestClient Bean.
     */
    @Bean
    public RestClient modelApiRestClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }
}
