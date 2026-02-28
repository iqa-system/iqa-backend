package org.wlow.iqabackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型API配置属性, 绑定自application.yaml.
 * 统一管理以避免硬编码端点信息.
 */
@Data
@ConfigurationProperties(prefix = "model-api")
public class ModelApiProperties {
    /**
     * Python模型服务的基础URL.
     */
    private String baseUrl;
}
