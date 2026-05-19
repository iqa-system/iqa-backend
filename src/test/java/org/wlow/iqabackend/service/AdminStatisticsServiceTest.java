package org.wlow.iqabackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminStatisticsServiceTest {

    @AfterEach
    void tearDown() {
        CurrentUser.clearAll();
    }

    @Test
    void statisticsNormalizesScoresAndCalculatesExpertComparison() {
        UserMapper userMapper = mock(UserMapper.class);
        AssessRecordMapper assessRecordMapper = mock(AssessRecordMapper.class);
        AssessService assessService = mock(AssessService.class);
        AdminStatisticsService statisticsService = new AdminStatisticsService(userMapper, assessRecordMapper, assessService);

        CurrentUser.setAll(1, "admin", UserRole.ADMIN.name());
        when(assessRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                assessRecord(101, 2, "liqe_default", 50D, "3.00", LocalDateTime.of(2026, 5, 1, 10, 0)),
                assessRecord(102, 2, "topiq_nr_koniq", 0.8D, "4.50", LocalDateTime.of(2026, 5, 1, 11, 0)),
                assessRecord(103, 3, "topiq_nr_koniq", 0.2D, null, LocalDateTime.of(2026, 5, 2, 9, 0))
        ));
        when(userMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                user(2, "alice"),
                user(3, "bob")
        ));
        when(assessService.listModels()).thenReturn(new ModelsResponse(
                "liqe_default",
                List.of(
                        model("liqe_default", "LIQE", 0D, 100D),
                        model("topiq_nr_koniq", "TOPIQ-NR", 0D, 1D)
                )
        ));

        AdminStatisticsResponse result = statisticsService.statistics(null, null);

        assertEquals(3, result.getSummary().getTotalAssessments());
        assertEquals(2, result.getSummary().getActiveUserCount());
        assertEquals(2, result.getSummary().getExpertSampleCount());
        assertEquals(2, result.getSummary().getModelCount());
        assertEquals(2, result.getDailyAssessments().get(0).getCount());
        assertEquals("2026-05-01", result.getDailyAssessments().get(0).getDate());
        assertEquals("topiq_nr_koniq", result.getModelUsage().get(0).getModelId());
        assertEquals(2, result.getModelUsage().get(0).getCount());
        assertEquals(0.6667D, result.getModelUsage().get(0).getRatio());
        assertEquals(1, result.getQualityBuckets().stream()
                .filter(bucket -> bucket.getBucket().equals("1-2"))
                .findFirst()
                .orElseThrow()
                .getCount());
        assertEquals(1, result.getQualityBuckets().stream()
                .filter(bucket -> bucket.getBucket().equals("2-3"))
                .findFirst()
                .orElseThrow()
                .getCount());
        assertEquals(1, result.getQualityBuckets().stream()
                .filter(bucket -> bucket.getBucket().equals("4-5"))
                .findFirst()
                .orElseThrow()
                .getCount());
        assertEquals("alice", result.getActiveUsers().get(0).getUsername());
        assertEquals(2, result.getActiveUsers().get(0).getCount());
        assertEquals(2, result.getExpertComparison().getSampleCount());
        assertEquals(0.5D, result.getExpertComparison().getMeanAbsoluteError());
        assertEquals(-0.5D, result.getExpertComparison().getMeanSignedError());
        assertEquals(0.5D, result.getExpertComparison().getRootMeanSquareError());
        assertEquals(1D, result.getExpertComparison().getPearsonCorrelation());
        assertEquals(2, result.getExpertComparison().getModelConsistency().size());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    void statisticsKeepsBasicDataWhenModelCatalogIsUnavailable() {
        UserMapper userMapper = mock(UserMapper.class);
        AssessRecordMapper assessRecordMapper = mock(AssessRecordMapper.class);
        AssessService assessService = mock(AssessService.class);
        AdminStatisticsService statisticsService = new AdminStatisticsService(userMapper, assessRecordMapper, assessService);

        CurrentUser.setAll(1, "admin", UserRole.ADMIN.name());
        when(assessRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                assessRecord(101, 2, "liqe_default", 50D, "3.00", LocalDateTime.of(2026, 5, 1, 10, 0))
        ));
        when(userMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(user(2, "alice")));
        when(assessService.listModels()).thenThrow(new RuntimeException("Python模型服务不可用"));

        AdminStatisticsResponse result = statisticsService.statistics(null, null);

        assertEquals(1, result.getSummary().getTotalAssessments());
        assertEquals(1, result.getModelUsage().size());
        assertEquals(0, result.getQualityBuckets().stream().mapToLong(AdminStatisticsResponse.QualityBucket::getCount).sum());
        assertEquals(0, result.getExpertComparison().getSampleCount());
        assertEquals(1, result.getExpertComparison().getSkippedCount());
        assertNull(result.getExpertComparison().getMeanAbsoluteError());
        assertTrue(result.getWarnings().stream().anyMatch(warning -> warning.contains("模型目录获取失败")));
        assertTrue(result.getWarnings().stream().anyMatch(warning -> warning.contains("模型目录不可用")));
    }

    @Test
    void statisticsRejectsNonAdminUser() {
        UserMapper userMapper = mock(UserMapper.class);
        AssessRecordMapper assessRecordMapper = mock(AssessRecordMapper.class);
        AssessService assessService = mock(AssessService.class);
        AdminStatisticsService statisticsService = new AdminStatisticsService(userMapper, assessRecordMapper, assessService);

        CurrentUser.setAll(2, "alice", UserRole.USER.name());

        assertThrows(AdminAccessDeniedException.class, () -> statisticsService.statistics(null, null));
        verify(assessRecordMapper, never()).selectList(any(QueryWrapper.class));
    }

    private AssessRecord assessRecord(Integer id,
                                      Integer userId,
                                      String modelId,
                                      Double qualityScore,
                                      String expertScore,
                                      LocalDateTime assessTime) {
        AssessRecord record = new AssessRecord();
        record.setId(id);
        record.setUserId(userId);
        record.setModelId(modelId);
        record.setQualityScore(qualityScore);
        record.setAssessTime(assessTime);
        if (expertScore != null) {
            record.setExpertScore(new BigDecimal(expertScore));
        }
        return record;
    }

    private User user(Integer id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private ModelInfo model(String modelId, String displayName, Double minScore, Double maxScore) {
        return new ModelInfo(
                modelId,
                displayName,
                displayName,
                List.of("quality_score"),
                new ScoreRange(minScore, maxScore),
                false
        );
    }
}
