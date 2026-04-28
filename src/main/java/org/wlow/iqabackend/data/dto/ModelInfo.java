package org.wlow.iqabackend.data.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个模型的元数据信息.
 * 该结构直接对齐Python /models响应, Java后端不再维护独立模型注册表.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelInfo {
    /**
     * 模型唯一ID.
     */
    @JsonProperty("model_id")
    private String modelId;

    /**
     * 前端展示名称.
     */
    @JsonProperty("display_name")
    private String displayName;

    /**
     * 模型描述信息.
     */
    private String description;

    /**
     * 模型支持的能力字段列表.
     */
    private List<String> capabilities;

    /**
     * 模型原始分数范围.
     * 前端会基于该范围把不同模型的原始分映射到统一的0-5量纲.
     */
    @JsonProperty("score_range")
    private ScoreRange scoreRange;

    /**
     * 当前模型是否为默认模型.
     */
    @JsonProperty("is_default")
    private Boolean isDefault;
}
