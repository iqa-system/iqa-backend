package org.wlow.iqabackend.data.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型原始分数范围.
 * Java后端只负责透传该结构, 不参与评分归一化计算或持久化.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScoreRange {
    /**
     * 模型原始分数最小值.
     */
    @JsonProperty("min_score")
    private Double minScore;

    /**
     * 模型原始分数最大值.
     */
    @JsonProperty("max_score")
    private Double maxScore;
}
