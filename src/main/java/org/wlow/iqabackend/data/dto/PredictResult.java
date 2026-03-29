package org.wlow.iqabackend.data.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Python服务返回的模型预测结果.
 * 将snake_case JSON字段映射为camelCase Java字段.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PredictResult {
    /**
     * 上传的原始文件名.
     */
    @JsonProperty("file_name")
    private String fileName;

    /**
     * 模型预测的质量分数.
     */
    @JsonProperty("quality_score")
    private float qualityScore;

    /**
     * 模型预测的场景分类编码.
     */
    private Integer scene;

    /**
     * 模型返回的失真类型概率列表.
     * 每一项包含失真编码index和对应概率prob.
     */
    private List<DistortionProb> distortions;
}
