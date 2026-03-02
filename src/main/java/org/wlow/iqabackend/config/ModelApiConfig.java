package org.wlow.iqabackend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 模型API客户端配置.
 * 构建基于配置baseUrl的RestClient.
 */
@Configuration
@EnableConfigurationProperties(ModelApiProperties.class)
public class ModelApiConfig {
    /**
     * AssessService调用Python端点使用的RestClient Bean.
     */
    @Bean
    public RestClient modelApiRestClient(ModelApiProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }
}
