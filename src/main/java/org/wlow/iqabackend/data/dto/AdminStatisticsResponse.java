package org.wlow.iqabackend.data.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 管理员统计看板响应.
 * 该DTO面向前端图表展示, 后端已经完成分数归一化和专家评分差异计算.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminStatisticsResponse {
    private Summary summary;
    private List<DailyAssessment> dailyAssessments;
    private List<ModelUsage> modelUsage;
    private List<QualityBucket> qualityBuckets;
    private List<ActiveUser> activeUsers;
    private ExpertComparison expertComparison;
    private List<String> warnings;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private long totalAssessments;
        private long activeUserCount;
        private long expertSampleCount;
        private long modelCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyAssessment {
        private String date;
        private long count;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelUsage {
        private String modelId;
        private String modelName;
        private long count;
        private double ratio;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityBucket {
        private String bucket;
        private double minScore;
        private double maxScore;
        private long count;
        private double ratio;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActiveUser {
        private Integer userId;
        private String username;
        private long count;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExpertComparison {
        private long sampleCount;
        private Long skippedCount;
        private Double meanAbsoluteError;
        private Double meanSignedError;
        private Double rootMeanSquareError;
        private Double pearsonCorrelation;
        private List<DeviationBucket> deviationBuckets;
        private List<ModelExpertConsistency> modelConsistency;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviationBucket {
        private String bucket;
        private double minDiff;
        private double maxDiff;
        private long count;
        private double ratio;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelExpertConsistency {
        private String modelId;
        private String modelName;
        private long sampleCount;
        private Double meanAbsoluteError;
        private Double meanSignedError;
        private Double rootMeanSquareError;
        private Double pearsonCorrelation;
    }
}
