package org.wlow.iqabackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.wlow.iqabackend.data.CurrentUser;
import org.wlow.iqabackend.data.UserRole;
import org.wlow.iqabackend.data.dto.AdminStatisticsResponse;
import org.wlow.iqabackend.data.dto.ModelInfo;
import org.wlow.iqabackend.data.dto.ModelsResponse;
import org.wlow.iqabackend.data.dto.ScoreRange;
import org.wlow.iqabackend.data.entity.AssessRecord;
import org.wlow.iqabackend.data.entity.User;
import org.wlow.iqabackend.exception.AdminAccessDeniedException;
import org.wlow.iqabackend.mapper.AssessRecordMapper;
import org.wlow.iqabackend.mapper.UserMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理员统计看板服务.
 * 统计结果直接面向前端图表, 不新增持久化表, 每次请求都基于当前历史记录实时聚合.
 */
@Service
@RequiredArgsConstructor
public class AdminStatisticsService {
    private static final double NORMALIZED_SCORE_MAX = 5D;
    private static final List<BucketRange> QUALITY_BUCKETS = List.of(
            new BucketRange("0-1", 0D, 1D),
            new BucketRange("1-2", 1D, 2D),
            new BucketRange("2-3", 2D, 3D),
            new BucketRange("3-4", 3D, 4D),
            new BucketRange("4-5", 4D, 5D)
    );
    private static final List<BucketRange> DEVIATION_BUCKETS = List.of(
            new BucketRange("<-2", Double.NEGATIVE_INFINITY, -2D),
            new BucketRange("-2~-1", -2D, -1D),
            new BucketRange("-1~0", -1D, 0D),
            new BucketRange("0~1", 0D, 1D),
            new BucketRange("1~2", 1D, 2D),
            new BucketRange(">2", 2D, Double.POSITIVE_INFINITY)
    );

    private final UserMapper userMapper;
    private final AssessRecordMapper assessRecordMapper;
    private final AssessService assessService;

    public AdminStatisticsResponse statistics(LocalDateTime startTime, LocalDateTime endTime) {
        requireAdminRole();
        validateTimeRange(startTime, endTime);

        List<AssessRecord> records = loadAssessRecords(startTime, endTime);
        List<String> warnings = new ArrayList<>();
        ModelCatalog modelCatalog = loadModelCatalog(warnings);
        Map<Integer, String> usernameMap = buildUsernameMap(records);

        AdminStatisticsResponse.Summary summary = buildSummary(records);
        List<AdminStatisticsResponse.DailyAssessment> dailyAssessments = buildDailyAssessments(records);
        List<AdminStatisticsResponse.ModelUsage> modelUsage = buildModelUsage(records, modelCatalog);
        NormalizedScores normalizedScores = buildNormalizedScores(records, modelCatalog, warnings);
        List<AdminStatisticsResponse.QualityBucket> qualityBuckets =
                buildQualityBuckets(normalizedScores.normalizedItems());
        List<AdminStatisticsResponse.ActiveUser> activeUsers = buildActiveUsers(records, usernameMap);
        AdminStatisticsResponse.ExpertComparison expertComparison =
                buildExpertComparison(normalizedScores.normalizedItems(), normalizedScores.skippedExpertCount(), modelCatalog);

        return new AdminStatisticsResponse(
                summary,
                dailyAssessments,
                modelUsage,
                qualityBuckets,
                activeUsers,
                expertComparison,
                warnings
        );
    }

    private List<AssessRecord> loadAssessRecords(LocalDateTime startTime, LocalDateTime endTime) {
        QueryWrapper<AssessRecord> wrapper = new QueryWrapper<AssessRecord>()
                .ge(startTime != null, "assess_time", startTime)
                .le(endTime != null, "assess_time", endTime)
                .orderByAsc("assess_time");
        return Optional.ofNullable(assessRecordMapper.selectList(wrapper)).orElse(List.of());
    }

    private AdminStatisticsResponse.Summary buildSummary(List<AssessRecord> records) {
        long activeUserCount = records.stream()
                .map(AssessRecord::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        long expertSampleCount = records.stream()
                .filter(record -> record.getExpertScore() != null)
                .count();
        long modelCount = records.stream()
                .map(AssessRecord::getModelId)
                .filter(this::hasText)
                .distinct()
                .count();
        return new AdminStatisticsResponse.Summary(records.size(), activeUserCount, expertSampleCount, modelCount);
    }

    private List<AdminStatisticsResponse.DailyAssessment> buildDailyAssessments(List<AssessRecord> records) {
        Map<LocalDate, Long> counts = records.stream()
                .filter(record -> record.getAssessTime() != null)
                .collect(Collectors.groupingBy(
                        record -> record.getAssessTime().toLocalDate(),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new AdminStatisticsResponse.DailyAssessment(entry.getKey().toString(), entry.getValue()))
                .toList();
    }

    private List<AdminStatisticsResponse.ModelUsage> buildModelUsage(List<AssessRecord> records, ModelCatalog modelCatalog) {
        Map<String, Long> counts = records.stream()
                .collect(Collectors.groupingBy(
                        record -> hasText(record.getModelId()) ? record.getModelId().trim() : "UNKNOWN",
                        LinkedHashMap::new,
                        Collectors.counting()
                ));
        long total = records.size();
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> new AdminStatisticsResponse.ModelUsage(
                        entry.getKey(),
                        modelCatalog.modelName(entry.getKey()),
                        entry.getValue(),
                        ratio(entry.getValue(), total)
                ))
                .toList();
    }

    private NormalizedScores buildNormalizedScores(List<AssessRecord> records,
                                                   ModelCatalog modelCatalog,
                                                   List<String> warnings) {
        if (!modelCatalog.available()) {
            warnings.add("模型目录不可用, 已跳过质量区间和专家评分差异中的归一化统计.");
            return new NormalizedScores(List.of(), countExpertCandidates(records));
        }

        List<NormalizedItem> items = new ArrayList<>();
        long skippedForQuality = 0;
        long skippedForExpert = 0;
        for (AssessRecord record : records) {
            Optional<Double> normalizedScore = normalizeScore(record, modelCatalog);
            if (normalizedScore.isPresent()) {
                items.add(new NormalizedItem(record, normalizedScore.get()));
            } else {
                skippedForQuality++;
                if (record.getExpertScore() != null) {
                    skippedForExpert++;
                }
            }
        }

        if (skippedForQuality > 0) {
            warnings.add("有" + skippedForQuality + "条记录因模型评分范围缺失或分数为空, 未参与归一化质量统计.");
        }
        if (skippedForExpert > 0) {
            warnings.add("有" + skippedForExpert + "条专家评分记录因模型评分范围缺失或分数为空, 未参与差异分析.");
        }
        return new NormalizedScores(items, skippedForExpert);
    }

    private List<AdminStatisticsResponse.QualityBucket> buildQualityBuckets(List<NormalizedItem> normalizedItems) {
        long total = normalizedItems.size();
        return QUALITY_BUCKETS.stream()
                .map(bucket -> {
                    long count = normalizedItems.stream()
                            .filter(item -> bucket.contains(item.normalizedScore()))
                            .count();
                    return new AdminStatisticsResponse.QualityBucket(
                            bucket.label(),
                            bucket.min(),
                            bucket.max(),
                            count,
                            ratio(count, total)
                    );
                })
                .toList();
    }

    private List<AdminStatisticsResponse.ActiveUser> buildActiveUsers(List<AssessRecord> records,
                                                                      Map<Integer, String> usernameMap) {
        Map<Integer, Long> counts = records.stream()
                .map(AssessRecord::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                .limit(10)
                .map(entry -> new AdminStatisticsResponse.ActiveUser(
                        entry.getKey(),
                        usernameMap.getOrDefault(entry.getKey(), "用户" + entry.getKey()),
                        entry.getValue()
                ))
                .toList();
    }

    private AdminStatisticsResponse.ExpertComparison buildExpertComparison(List<NormalizedItem> normalizedItems,
                                                                           long skippedExpertCount,
                                                                           ModelCatalog modelCatalog) {
        List<ExpertDiff> diffs = normalizedItems.stream()
                .filter(item -> item.record().getExpertScore() != null)
                .map(item -> new ExpertDiff(
                        hasText(item.record().getModelId()) ? item.record().getModelId().trim() : "UNKNOWN",
                        item.normalizedScore(),
                        item.record().getExpertScore().doubleValue(),
                        item.normalizedScore() - item.record().getExpertScore().doubleValue()
                ))
                .toList();
        StatisticsValues overall = statisticsValues(diffs);
        return new AdminStatisticsResponse.ExpertComparison(
                diffs.size(),
                skippedExpertCount,
                overall.meanAbsoluteError(),
                overall.meanSignedError(),
                overall.rootMeanSquareError(),
                overall.pearsonCorrelation(),
                buildDeviationBuckets(diffs),
                buildModelConsistency(diffs, modelCatalog)
        );
    }

    private List<AdminStatisticsResponse.DeviationBucket> buildDeviationBuckets(List<ExpertDiff> diffs) {
        long total = diffs.size();
        return DEVIATION_BUCKETS.stream()
                .map(bucket -> {
                    long count = diffs.stream()
                            .filter(diff -> bucket.contains(diff.diff()))
                            .count();
                    return new AdminStatisticsResponse.DeviationBucket(
                            bucket.label(),
                            bucket.min(),
                            bucket.max(),
                            count,
                            ratio(count, total)
                    );
                })
                .toList();
    }

    private List<AdminStatisticsResponse.ModelExpertConsistency> buildModelConsistency(List<ExpertDiff> diffs,
                                                                                       ModelCatalog modelCatalog) {
        return diffs.stream()
                .collect(Collectors.groupingBy(ExpertDiff::modelId, LinkedHashMap::new, Collectors.toList()))
                .entrySet()
                .stream()
                .sorted(Comparator.comparing(entry -> entry.getKey()))
                .map(entry -> {
                    StatisticsValues values = statisticsValues(entry.getValue());
                    return new AdminStatisticsResponse.ModelExpertConsistency(
                            entry.getKey(),
                            modelCatalog.modelName(entry.getKey()),
                            entry.getValue().size(),
                            values.meanAbsoluteError(),
                            values.meanSignedError(),
                            values.rootMeanSquareError(),
                            values.pearsonCorrelation()
                    );
                })
                .toList();
    }

    private StatisticsValues statisticsValues(List<ExpertDiff> diffs) {
        if (diffs.isEmpty()) {
            return new StatisticsValues(null, null, null, null);
        }
        double mae = diffs.stream().mapToDouble(diff -> Math.abs(diff.diff())).average().orElse(0D);
        double meanSigned = diffs.stream().mapToDouble(ExpertDiff::diff).average().orElse(0D);
        double rmse = Math.sqrt(diffs.stream().mapToDouble(diff -> diff.diff() * diff.diff()).average().orElse(0D));
        Double correlation = pearsonCorrelation(diffs);
        return new StatisticsValues(round(mae), round(meanSigned), round(rmse), correlation == null ? null : round(correlation));
    }

    private Double pearsonCorrelation(List<ExpertDiff> diffs) {
        if (diffs.size() < 2) {
            return null;
        }
        double modelMean = diffs.stream().mapToDouble(ExpertDiff::normalizedModelScore).average().orElse(0D);
        double expertMean = diffs.stream().mapToDouble(ExpertDiff::expertScore).average().orElse(0D);
        double numerator = 0D;
        double modelVariance = 0D;
        double expertVariance = 0D;
        for (ExpertDiff diff : diffs) {
            double modelDelta = diff.normalizedModelScore() - modelMean;
            double expertDelta = diff.expertScore() - expertMean;
            numerator += modelDelta * expertDelta;
            modelVariance += modelDelta * modelDelta;
            expertVariance += expertDelta * expertDelta;
        }
        if (modelVariance == 0D || expertVariance == 0D) {
            return null;
        }
        return numerator / Math.sqrt(modelVariance * expertVariance);
    }

    private Optional<Double> normalizeScore(AssessRecord record, ModelCatalog modelCatalog) {
        if (record.getQualityScore() == null || !hasText(record.getModelId())) {
            return Optional.empty();
        }
        ScoreRange range = modelCatalog.scoreRange(record.getModelId().trim());
        if (!isValidRange(range)) {
            return Optional.empty();
        }
        double normalized = (record.getQualityScore() - range.getMinScore())
                / (range.getMaxScore() - range.getMinScore())
                * NORMALIZED_SCORE_MAX;
        if (!Double.isFinite(normalized)) {
            return Optional.empty();
        }
        return Optional.of(Math.min(NORMALIZED_SCORE_MAX, Math.max(0D, normalized)));
    }

    private ModelCatalog loadModelCatalog(List<String> warnings) {
        try {
            ModelsResponse modelsResponse = assessService.listModels();
            List<ModelInfo> models = modelsResponse == null || modelsResponse.getModels() == null
                    ? List.of()
                    : modelsResponse.getModels();
            Map<String, ModelInfo> modelMap = models.stream()
                    .filter(model -> hasText(model.getModelId()))
                    .collect(Collectors.toMap(
                            model -> model.getModelId().trim(),
                            Function.identity(),
                            (left, right) -> left,
                            LinkedHashMap::new
                    ));
            if (modelMap.isEmpty()) {
                warnings.add("模型目录为空, 已跳过依赖评分范围的统计项.");
                return new ModelCatalog(false, Map.of());
            }
            return new ModelCatalog(true, modelMap);
        } catch (RuntimeException ex) {
            warnings.add("模型目录获取失败: " + ex.getMessage());
            return new ModelCatalog(false, Map.of());
        }
    }

    private Map<Integer, String> buildUsernameMap(List<AssessRecord> records) {
        Set<Integer> userIds = records.stream()
                .map(AssessRecord::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return Optional.ofNullable(userMapper.selectList(new QueryWrapper<User>().in("id", userIds)))
                .orElse(List.of())
                .stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (left, right) -> left, LinkedHashMap::new));
    }

    private long countExpertCandidates(List<AssessRecord> records) {
        return records.stream().filter(record -> record.getExpertScore() != null).count();
    }

    private boolean isValidRange(ScoreRange range) {
        return range != null
                && range.getMinScore() != null
                && range.getMaxScore() != null
                && Double.isFinite(range.getMinScore())
                && Double.isFinite(range.getMaxScore())
                && range.getMaxScore() > range.getMinScore();
    }

    private void requireAdminRole() {
        if (!UserRole.isAdmin(CurrentUser.getRole())) {
            throw new AdminAccessDeniedException("无权访问管理员功能");
        }
    }

    private void validateTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("startTime不能晚于endTime");
        }
    }

    private double ratio(long count, long total) {
        if (total <= 0) {
            return 0D;
        }
        return round((double) count / total);
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record BucketRange(String label, double min, double max) {
        private boolean contains(double value) {
            boolean lowerMatched = Double.isInfinite(min) || value >= min;
            boolean upperMatched = Double.isInfinite(max) || value < max || (max == NORMALIZED_SCORE_MAX && value <= max);
            return lowerMatched && upperMatched;
        }
    }

    private record ModelCatalog(boolean available, Map<String, ModelInfo> models) {
        private ScoreRange scoreRange(String modelId) {
            ModelInfo modelInfo = models.get(modelId);
            return modelInfo == null ? null : modelInfo.getScoreRange();
        }

        private String modelName(String modelId) {
            ModelInfo modelInfo = models.get(modelId);
            if (modelInfo == null || modelInfo.getDisplayName() == null || modelInfo.getDisplayName().isBlank()) {
                return modelId;
            }
            return modelInfo.getDisplayName();
        }
    }

    private record NormalizedScores(List<NormalizedItem> normalizedItems, long skippedExpertCount) {
    }

    private record NormalizedItem(AssessRecord record, double normalizedScore) {
    }

    private record ExpertDiff(String modelId, double normalizedModelScore, double expertScore, double diff) {
    }

    private record StatisticsValues(Double meanAbsoluteError,
                                    Double meanSignedError,
                                    Double rootMeanSquareError,
                                    Double pearsonCorrelation) {
    }
}
