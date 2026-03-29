package org.wlow.iqabackend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.wlow.iqabackend.data.CurrentUser;
import org.wlow.iqabackend.data.dto.BatchPredictResponse;
import org.wlow.iqabackend.data.dto.PredictResult;
import org.wlow.iqabackend.data.entity.AssessRecord;
import org.wlow.iqabackend.mapper.AssessRecordMapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 将Spring Boot请求桥接到Python模型API.
 * 处理multipart编码与响应映射.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssessService {
    private static final ExecutorService BATCH_SSE_EXECUTOR = Executors.newCachedThreadPool();
    private final RestClient modelApiRestClient;
    private final AssessRecordMapper assessRecordMapper;
    private final ImageCompressService imageCompressService;

    /**
     * 调用/predict处理单个上传文件.
     */
    public PredictResult assess(MultipartFile file) {
        validateFile(file);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", toFilePart(file));
        PredictResult result = executePredict(body);

        // 单图评估成功后, 立即落一条历史记录, 保证查询接口可追溯用户操作.
        saveAssessRecord(file, result);
        return result;
    }

    /**
     * 调用/predict/batch处理多个上传文件.
     */
    public BatchPredictResponse assessBatch(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("files为空");
        }
        log.info("准备请求Python服务: 端点=/predict/batch, 文件数={}个", files.size());
        for (MultipartFile file : files) {
            log.info("批量文件: 文件名={}, 类型={}, 大小={}字节",
                    safeName(file), safeContentType(file), file.getSize());
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        for (MultipartFile file : files) {
            validateFile(file);
            body.add("files", toFilePart(file));
        }
        BatchPredictResponse response = executeBatchPredict(body);

        // 批量评估成功后, 为每张图分别写入历史记录, 便于分页回看.
        saveAssessRecords(files, response.getResults());
        return response;
    }

    /**
     * 批量评估SSE流式接口.
     * 事件顺序: start -> progress(模型处理中) -> result/progress(逐图) -> completed.
     */
    public SseEmitter assessBatchStream(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("files为空");
        }
        Integer userId = CurrentUser.getId();
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }

        List<UploadFileData> uploadFiles = snapshotFiles(files);
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onTimeout(() -> log.warn("批量评估SSE超时, userId={}", userId));
        emitter.onCompletion(() -> log.info("批量评估SSE结束, userId={}", userId));

        CompletableFuture.runAsync(() -> streamBatchAssess(uploadFiles, userId, emitter), BATCH_SSE_EXECUTOR);
        return emitter;
    }

    /**
     * 查询当前登录用户的评估历史记录.
     */
    public Page<AssessRecord> history(int page, int pageSize) {
        Integer userId = CurrentUser.getId();
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }

        Page<AssessRecord> pageRequest = new Page<>(page, pageSize);
        QueryWrapper<AssessRecord> wrapper = new QueryWrapper<AssessRecord>()
                .eq("user_id", userId)
                .orderByDesc("assess_time");

        return assessRecordMapper.selectPage(pageRequest, wrapper);
    }

    /**
     * 校验文件存在且非空.
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file为空");
        }
    }

    /**
     * 保存单条评估历史记录.
     */
    private void saveAssessRecord(MultipartFile file, PredictResult result) {
        Integer userId = CurrentUser.getId();
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }
        AssessRecord record = buildRecord(file, userId, result);
        assessRecordMapper.insert(record);
    }

    /**
     * 保存单条评估历史记录(字节快照版, 适用于异步SSE线程).
     */
    private void saveAssessRecord(UploadFileData file, Integer userId, PredictResult result) {
        AssessRecord record = buildRecord(file, userId, result);
        assessRecordMapper.insert(record);
    }

    /**
     * 批量保存评估历史记录.
     */
    private void saveAssessRecords(List<MultipartFile> files, List<PredictResult> results) {
        Integer userId = CurrentUser.getId();
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }

        List<AssessRecord> records = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            PredictResult result = (results != null && i < results.size()) ? results.get(i) : null;
            records.add(buildRecord(files.get(i), userId, result));
        }
        for (AssessRecord record : records) {
            assessRecordMapper.insert(record);
        }
    }

    /**
     * 构建待入库历史记录.
     * 图片会先压缩到15KB目标以内, 再转为Base64字符串.
     */
    private AssessRecord buildRecord(MultipartFile file, Integer userId, PredictResult result) {
        AssessRecord record = new AssessRecord();
        record.setUserId(userId);
        record.setFilename(safeName(file));
        record.setBase64(imageCompressService.compressToBase64(file));
        record.setAssessTime(LocalDateTime.now());
        
        if (result != null) {
            record.setQualityScore((double) result.getQualityScore());
            record.setScene(result.getScene());
            record.setDistortions(result.getDistortions());
        }
        
        return record;
    }

    /**
     * 构建待入库历史记录(字节快照版).
     */
    private AssessRecord buildRecord(UploadFileData file, Integer userId, PredictResult result) {
        AssessRecord record = new AssessRecord();
        record.setUserId(userId);
        record.setFilename(file.filename());
        record.setBase64(imageCompressService.compressToBase64(file.bytes()));
        record.setAssessTime(LocalDateTime.now());
        
        if (result != null) {
            record.setQualityScore((double) result.getQualityScore());
            record.setScene(result.getScene());
            record.setDistortions(result.getDistortions());
        }
        
        return record;
    }

    /**
     * 构建multipart部分, 保留文件名与内容类型.
     */
    private HttpEntity<Resource> toFilePart(MultipartFile file) {
        try {
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return Optional.ofNullable(file.getOriginalFilename()).orElse("file");
                }
            };
            HttpHeaders headers = new HttpHeaders();
            String contentType = Optional.ofNullable(file.getContentType())
                    .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            headers.setContentType(MediaType.parseMediaType(contentType));
            return new HttpEntity<>(resource, headers);
        } catch (IOException ex) {
            throw new RestClientException("读取multipart文件失败", ex);
        }
    }

    /**
     * 构建multipart部分(字节快照版), 避免异步线程访问请求生命周期内对象.
     */
    private HttpEntity<Resource> toFilePart(UploadFileData file) {
        ByteArrayResource resource = new ByteArrayResource(file.bytes()) {
            @Override
            public String getFilename() {
                return file.filename();
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType()));
        return new HttpEntity<>(resource, headers);
    }

    /**
     * 执行单次预测请求并映射响应.
     */
    private PredictResult executePredict(MultiValueMap<String, Object> body) {
        return modelApiRestClient.post()
                .uri("/predict")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(PredictResult.class);
    }

    /**
     * 执行批量预测请求并映射响应.
     */
    private BatchPredictResponse executeBatchPredict(MultiValueMap<String, Object> body) {
        return modelApiRestClient.post()
                .uri("/predict/batch")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(BatchPredictResponse.class);
    }

    /**
     * 将MultipartFile转为内存快照, 防止请求结束后文件对象失效.
     */
    private List<UploadFileData> snapshotFiles(List<MultipartFile> files) {
        List<UploadFileData> snapshots = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            validateFile(file);
            try {
                snapshots.add(new UploadFileData(safeName(file), safeContentType(file), file.getBytes()));
            } catch (IOException ex) {
                throw new IllegalArgumentException("读取上传文件失败: " + safeName(file));
            }
        }
        return snapshots;
    }

    /**
     * 组织批量评估SSE推送逻辑.
     */
    private void streamBatchAssess(List<UploadFileData> files, Integer userId, SseEmitter emitter) {
        int total = files.size();
        try {
            Map<String, Object> startData = new HashMap<>();
            startData.put("message", "批量评估任务开始");
            startData.put("progress", 0);
            startData.put("total", total);
            sendEvent(emitter, "start", startData);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            for (UploadFileData file : files) {
                body.add("files", toFilePart(file));
            }

            Map<String, Object> modelProgressData = new HashMap<>();
            modelProgressData.put("message", "模型批量评估中");
            modelProgressData.put("progress", 20);
            modelProgressData.put("total", total);
            sendEvent(emitter, "progress", modelProgressData);

            BatchPredictResponse response = executeBatchPredict(body);
            List<PredictResult> results = Optional.ofNullable(response)
                    .map(BatchPredictResponse::getResults)
                    .orElseThrow(() -> new IllegalStateException("模型服务返回空结果"));
            if (results.size() != total) {
                throw new IllegalStateException("模型返回结果数量与上传文件数量不一致");
            }

            for (int i = 0; i < total; i++) {
                UploadFileData currentFile = files.get(i);
                PredictResult predictResult = results.get(i);
                if (predictResult.getFileName() == null || predictResult.getFileName().isBlank()) {
                    predictResult.setFileName(currentFile.filename());
                }

                Map<String, Object> resultData = new HashMap<>();
                resultData.put("message", "已完成单张评估");
                resultData.put("currentFileName", currentFile.filename());
                resultData.put("predictResult", predictResult);
                resultData.put("progress", 20 + (int) Math.round(((i + 1) * 40.0) / total));
                resultData.put("total", total);
                resultData.put("completed", i + 1);
                sendEvent(emitter, "result", resultData);

                saveAssessRecord(currentFile, userId, predictResult);

                Map<String, Object> saveProgressData = new HashMap<>();
                saveProgressData.put("message", "已保存历史记录");
                saveProgressData.put("currentFileName", currentFile.filename());
                saveProgressData.put("progress", 60 + (int) Math.round(((i + 1) * 40.0) / total));
                saveProgressData.put("total", total);
                saveProgressData.put("completed", i + 1);
                sendEvent(emitter, "progress", saveProgressData);
            }

            Map<String, Object> completedData = new HashMap<>();
            completedData.put("message", "批量评估完成");
            completedData.put("progress", 100);
            completedData.put("total", total);
            completedData.put("completed", total);
            completedData.put("results", results);
            sendEvent(emitter, "completed", completedData);
            emitter.complete();
        } catch (Exception ex) {
            log.error("批量评估SSE失败: {}", ex.getMessage(), ex);
            try {
                Map<String, Object> errorData = new HashMap<>();
                errorData.put("message", "批量评估失败: " + ex.getMessage());
                sendEvent(emitter, "error", errorData);
            } catch (Exception ignore) {
                log.warn("发送SSE错误事件失败: {}", ignore.getMessage());
            }
            emitter.completeWithError(ex);
        }
    }

    /**
     * 发送SSE事件.
     */
    private void sendEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    private String safeName(MultipartFile file) {
        return Optional.ofNullable(file.getOriginalFilename()).orElse("file");
    }

    private String safeContentType(MultipartFile file) {
        return Optional.ofNullable(file.getContentType()).orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    /**
     * 上传文件快照.
     */
    private record UploadFileData(String filename, String contentType, byte[] bytes) {
    }
}
