package org.wlow.iqabackend.data.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.List;

/**
 * Python服务返回的模型预测结果.
 * 将snake_case JSON字段映射为camelCase Java字段.
 *
 * Python 2.0中仍然可能返回历史遗留字段distortion.
 * 这里继续通过@JsonIgnoreProperties忽略它, 避免把已经废弃的字段重新带回Java契约.
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
     * 实际执行推理的模型ID.
     * 无论调用方是否显式传入modelId, 都以Python最终返回的model_id为准.
     */
    @JsonProperty("model_id")
    private String modelId;

    /**
     * 模型预测的质量分数.
     */
    @JsonProperty("quality_score")
    private float qualityScore;

    /**
     * 该模型原始分数的取值区间.
     * Java后端即时透传给前端, 方便前端计算统一的归一化分.
     */
    @JsonProperty("score_range")
    private ScoreRange scoreRange;

    /**
     * 模型通用扩展输出.
     * 当前不同模型可能返回空对象, 也可能补充少量附加指标.
     * Java后端先按通用Map透传, 本次不持久化到历史记录.
     */
    @JsonProperty("extra_outputs")
    private Map<String, Object> extraOutputs;

    /**
     * 模型预测的场景分类编码.
     * 只有支持该能力的模型才会返回值, 其它模型允许为null.
     */
    private Integer scene;

    /**
     * 模型返回的失真类型概率列表.
     * 每一项包含失真编码index和对应概率prob.
     * 非LIQE类模型可能不返回该字段, 因此这里允许为null.
     */
    private List<DistortionProb> distortions;
}
