package org.wlow.iqabackend.data.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Python /models接口返回的模型列表响应.
 * defaultModelId和models都会直接透传给前端, 作为模型选择面板的数据来源.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelsResponse {
    /**
     * 默认模型ID.
     */
    @JsonProperty("default_model_id")
    private String defaultModelId;

    /**
     * 当前可用模型列表.
     */
    private List<ModelInfo> models;
}
