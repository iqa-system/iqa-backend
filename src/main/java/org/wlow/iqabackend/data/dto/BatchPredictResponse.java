package org.wlow.iqabackend.data.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Python服务返回的批量预测响应包装类.
 * 包含每张图片的预测结果列表.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchPredictResponse {
    /**
     * 与上传文件顺序一致的预测结果.
     */
    private List<PredictResult> results;
}
