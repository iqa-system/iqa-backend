package org.wlow.iqabackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.wlow.iqabackend.data.CurrentUser;
import org.wlow.iqabackend.data.dto.BatchPredictResponse;
import org.wlow.iqabackend.data.dto.ModelsResponse;
import org.wlow.iqabackend.data.dto.PredictResult;
import org.wlow.iqabackend.data.entity.AssessRecord;
import org.wlow.iqabackend.data.entity.FileEntry;
import org.wlow.iqabackend.exception.AssessRecordAccessDeniedException;
import org.wlow.iqabackend.exception.AssessRecordNotFoundException;
import org.wlow.iqabackend.mapper.AssessRecordMapper;
import org.wlow.iqabackend.util.Common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 将Spring Boot请求桥接到Python模型API.
 * 处理multipart编码, 模型调用以及评估历史落库.
 *
 * 这里和旧版本最大的区别是:
 * 评估完成后不再把图片压缩成Base64写进assess_record,
 * 而是改为委托FileService把图片落盘, 然后只在历史记录里保存fileEntryId.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssessService {
    private static final ExecutorService BATCH_SSE_EXECUTOR = Executors.newCachedThreadPool();
    private static final ObjectMapper MODEL_API_ERROR_OBJECT_MAPPER = new ObjectMapper();
    private static final BigDecimal MIN_EXPERT_SCORE = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    private static final BigDecimal MAX_EXPERT_SCORE = new BigDecimal("5.00");
    private final RestClient modelApiRestClient;
    private final AssessRecordMapper assessRecordMapper;
    private final FileService fileService;

    /**
     * 查询当前Python模型服务支持的模型列表.
     * Java后端只做透明代理, 不在本地维护独立模型注册表.
     */
    public ModelsResponse listModels() {
        return executeModelApiCall(() -> modelApiRestClient.get()
                .uri("/models")
                .retrieve()
                .body(ModelsResponse.class));
    }

    /**
     * 调用/predict处理单个上传文件.
     * 模型预测成功后, 再把原图保存到本地并写入历史记录.
     */
    public PredictResult assess(MultipartFile file, String expertScoreRaw) {
        return assess(file, expertScoreRaw, null);
    }

    /**
     * 调用/predict处理单个上传文件.
     * 如果调用方传了modelId, 这里会把它透传给Python的model_id字段.
     */
    public PredictResult assess(MultipartFile file, String expertScoreRaw, String modelIdRaw) {
        validateFile(file);
        BigDecimal expertScore = normalizeOptionalSubmissionExpertScore(expertScoreRaw, "expertScore");
        String modelId = normalizeOptionalModelId(modelIdRaw);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", toFilePart(file));
        if (modelId != null) {
            body.add("model_id", modelId);
        }
        PredictResult result = executePredict(body);

        // 单图评估成功后, 立即落一条历史记录, 保证查询接口可追溯用户操作.
        saveAssessRecord(file, result, expertScore);
        return result;
    }

    /**
     * 批量评估SSE流式接口.
     * 事件顺序: start -> progress(模型处理中) -> result/progress(逐图) -> completed.
     * SSE线程不能继续依赖请求生命周期内的MultipartFile, 所以会先做字节快照.
     */
    public SseEmitter assessBatchStream(List<MultipartFile> files, List<String> expertScoreRaws) {
        return assessBatchStream(files, expertScoreRaws, null);
    }

    /**
     * 批量评估SSE流式接口.
     * 整批图片共用一个modelId. Java只做非空标准化, 不维护本地模型白名单.
     */
    public SseEmitter assessBatchStream(List<MultipartFile> files, List<String> expertScoreRaws, String modelIdRaw) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("files为空");
        }
        Integer userId = CurrentUser.getId();
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }

        String modelId = normalizeOptionalModelId(modelIdRaw);
        List<BigDecimal> expertScores = normalizeBatchExpertScores(expertScoreRaws, files.size());
        List<UploadFileData> uploadFiles = snapshotFiles(files);
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onTimeout(() -> log.warn("批量评估SSE超时, userId={}", userId));
        emitter.onCompletion(() -> log.info("批量评估SSE结束, userId={}", userId));

        CompletableFuture.runAsync(() -> streamBatchAssess(uploadFiles, expertScores, modelId, userId, emitter), BATCH_SSE_EXECUTOR);
        return emitter;
    }

    /**
     * 查询当前登录用户的评估历史记录.
     * 查询主表后, 会额外批量查一次file_entry并把新的静态imageURL动态回填进去.
     */
    public Page<AssessRecord> history(int page, int pageSize) {
        Integer userId = requireCurrentUserId();
        Page<AssessRecord> pageRequest = new Page<>(page, pageSize);
        QueryWrapper<AssessRecord> wrapper = new QueryWrapper<AssessRecord>()
                .eq("user_id", userId)
                .orderByDesc("assess_time");

        Page<AssessRecord> result = assessRecordMapper.selectPage(pageRequest, wrapper);
        fillHistoryImageURLs(result.getRecords());
        return result;
    }

    /**
     * 批量删除当前用户选中的评估历史记录.
     * 删除链路要求严格原子语义:
     * 1. 请求中的所有ID都必须存在
     * 2. 所有记录都必须属于当前用户
     * 3. 任一步失败都会抛异常, 触发数据库事务回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteHistory(List<Integer> ids) {
        Integer userId = requireCurrentUserId();
        List<Integer> historyIds = Common.normalizeIds(ids);
        List<AssessRecord> records = Optional.ofNullable(assessRecordMapper.selectByIdList(historyIds))
                .orElse(List.of());

        // 先校验是否存在缺失记录. 只有全部都存在时, 才继续校验归属关系.
        if (records.size() != historyIds.size()) {
            throw new AssessRecordNotFoundException("存在不存在的评估历史记录");
        }

        boolean containsForeignRecord = records.stream()
                .anyMatch(record -> !userId.equals(record.getUserId()));
        if (containsForeignRecord) {
            throw new AssessRecordAccessDeniedException("无权删除其他用户的评估历史记录");
        }

        List<Integer> fileEntryIds = records.stream()
                .filter(Objects::nonNull)
                .map(AssessRecord::getFileEntryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        int deletedRecords = assessRecordMapper.deleteByUserIdAndIds(userId, historyIds);
        if (deletedRecords != historyIds.size()) {
            throw new IllegalStateException("删除评估历史记录失败");
        }

        for (Integer fileEntryId : fileEntryIds) {
            fileService.deleteOwnedFile(fileEntryId, userId);
        }
        return deletedRecords;
    }

    /**
     * 为当前专家用户补录或修改自己的主观评分.
     * 只允许EXPERT角色操作, 且只允许修改自己名下的历史记录.
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateExpertScore(Integer historyId, String expertScoreRaw) {
        requireExpertRole();
        Integer userId = requireCurrentUserId();
        BigDecimal expertScore = normalizeRequiredExpertScore(expertScoreRaw, "expertScore");

        AssessRecord record = Optional.ofNullable(assessRecordMapper.selectById(historyId))
                .orElseThrow(() -> new AssessRecordNotFoundException("评估历史记录不存在"));
        if (!userId.equals(record.getUserId())) {
            throw new AssessRecordAccessDeniedException("无权修改其他用户的评估历史记录");
        }

        record.setExpertScore(expertScore);
        record.setExpertScoreTime(LocalDateTime.now());
        int updated = assessRecordMapper.updateById(record);
        if (updated != 1) {
            throw new IllegalStateException("更新专家主观评分失败");
        }
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
     * 同步接口里使用MultipartFile版本, 这里先把文件读成字节数组, 再复用统一的落盘/落库逻辑.
     */
    private void saveAssessRecord(MultipartFile file, PredictResult result, BigDecimal expertScore) {
        Integer userId = requireCurrentUserId();
        try {
            saveAssessRecord(file.getBytes(), safeName(file), userId, result, expertScore);
        } catch (IOException ex) {
            throw new IllegalArgumentException("读取上传文件失败: " + safeName(file), ex);
        }
    }

    /**
     * 保存单条评估历史记录(字节快照版, 适用于异步SSE线程).
     * 异步线程里已经拿到稳定的字节快照, 不会再触碰请求结束后可能失效的MultipartFile对象.
     */
    private void saveAssessRecord(UploadFileData file, Integer userId, PredictResult result, BigDecimal expertScore) {
        saveAssessRecord(file.bytes(), file.filename(), userId, result, expertScore);
    }

    /**
     * 保存单条评估历史记录(字节数组版).
     * 共享单图与SSE批量落库逻辑, 并负责失败补偿.
     *
     * 固定顺序:
     * 1. FileService.upload 写磁盘并插入file_entry
     * 2. 组装AssessRecord并插入assess_record
     * 3. 如果第2步失败, 反向删除第1步产生的文件和元数据
     */
    void saveAssessRecord(byte[] fileBytes, String originalFilename, Integer userId, PredictResult result, BigDecimal expertScore) {
        FileEntry fileEntry = null;
        try {
            fileEntry = fileService.upload(fileBytes, originalFilename, userId);
            AssessRecord record = buildRecord(originalFilename, userId, fileEntry, result, expertScore);
            assessRecordMapper.insert(record);
        } catch (RuntimeException ex) {
            rollbackFileEntry(fileEntry, ex);
            throw ex;
        }
    }

    /**
     * 构建待入库历史记录.
     * AssessRecord里保存的是前端展示需要的原始文件名, 不是磁盘上的UUID文件名.
     */
    private AssessRecord buildRecord(String originalFilename,
                                     Integer userId,
                                     FileEntry fileEntry,
                                     PredictResult result,
                                     BigDecimal expertScore) {
        AssessRecord record = new AssessRecord();
        record.setUserId(userId);
        record.setFilename(originalFilename);
        record.setFileEntryId(fileEntry.getId());
        record.setImageURL(fileEntry.getResourceURL());
        record.setAssessTime(LocalDateTime.now());

        if (result != null) {
            record.setQualityScore((double) result.getQualityScore());
            record.setModelId(result.getModelId());
            record.setScene(result.getScene());
            record.setDistortions(result.getDistortions());
        }
        if (expertScore != null) {
            record.setExpertScore(expertScore);
            record.setExpertScoreTime(LocalDateTime.now());
        }

        return record;
    }

    /**
     * assess_record插入失败时的补偿逻辑.
     * 由于FileService已经先创建了file_entry和物理文件, 这里必须显式回滚, 防止残留孤儿资源.
     */
    private void rollbackFileEntry(FileEntry fileEntry, RuntimeException cause) {
        if (fileEntry == null || fileEntry.getId() == null) {
            return;
        }
        try {
            fileService.deleteFileEntry(fileEntry);
        } catch (RuntimeException cleanupEx) {
            log.error("回滚文件元数据失败, fileEntryId={}", fileEntry.getId(), cleanupEx);
            cause.addSuppressed(cleanupEx);
        }
    }

    /**
     * 批量回填历史记录里的imageURL.
     * 这里按fileEntryId去重后一次查库, 避免records.size()条历史触发同等数量的文件元数据查询.
     * 图片已经改成公开静态资源, 因此这里不再按当前用户过滤file_entry, 只按主键批量查元数据并拼URL.
     */
    private void fillHistoryImageURLs(List<AssessRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Integer> fileEntryIds = records.stream()
                .map(AssessRecord::getFileEntryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (fileEntryIds.isEmpty()) {
            return;
        }
        Map<Integer, FileEntry> fileEntryMap = fileService.mapFileEntriesByIds(fileEntryIds);
        for (AssessRecord record : records) {
            FileEntry entry = fileEntryMap.get(record.getFileEntryId());
            if (entry != null) {
                record.setImageURL(entry.getResourceURL());
            }
        }
    }

    /**
     * 统一读取当前登录用户ID.
     * 这样单图, 历史查询和后续其它逻辑都不用重复写相同的空值判断.
     */
    private Integer requireCurrentUserId() {
        Integer userId = CurrentUser.getId();
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }
        return userId;
    }

    /**
     * 要求当前用户具备图像评估专家身份.
     * 只有EXPERT才允许提交或修改主观评分.
     */
    private void requireExpertRole() {
        if (!org.wlow.iqabackend.data.UserRole.isExpert(CurrentUser.getRole())) {
            throw new org.wlow.iqabackend.exception.ExpertAccessDeniedException("只有EXPERT角色可以提交或修改主观评分");
        }
    }

    /**
     * 构建multipart部分, 保留文件名与内容类型.
     * 这里的resource只用于发给Python模型服务, 和本地文件系统存储无关.
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
     * SSE异步分支会走这个版本, 直接用快照字节重新包装成multipart part.
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
        return executeModelApiCall(() -> modelApiRestClient.post()
                .uri("/predict")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(PredictResult.class));
    }

    /**
     * 执行批量预测请求并映射响应.
     */
    private BatchPredictResponse executeBatchPredict(MultiValueMap<String, Object> body) {
        return executeModelApiCall(() -> modelApiRestClient.post()
                .uri("/predict/batch")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(BatchPredictResponse.class));
    }

    /**
     * 统一执行远程模型服务调用.
     * 约定如下:
     * 1. Python返回400时, 转为本地400并尽量透传detail.
     * 2. Python返回5xx或网络层异常时, 继续按502处理.
     */
    private <T> T executeModelApiCall(Supplier<T> remoteCall) {
        try {
            return remoteCall.get();
        } catch (RestClientResponseException ex) {
            throw mapModelApiResponseException(ex);
        }
    }

    /**
     * 把远程HTTP异常转换成当前后端的异常语义.
     * Python 400通常代表未知模型或请求体非法, 应当反馈给调用方作为参数错误.
     */
    private RuntimeException mapModelApiResponseException(RestClientResponseException ex) {
        int statusCode = ex.getStatusCode().value();
        String remoteDetail = extractModelApiErrorDetail(ex);
        if (statusCode == 400) {
            return new IllegalArgumentException(remoteDetail);
        }
        return new RestClientException("远程模型服务调用失败: " + remoteDetail, ex);
    }

    /**
     * 尽量从Python错误响应体中提取detail字段.
     * 如果响应体不是标准JSON, 就回退到状态文本或原始响应体.
     */
    private String extractModelApiErrorDetail(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (hasText(body)) {
            try {
                JsonNode root = MODEL_API_ERROR_OBJECT_MAPPER.readTree(body);
                JsonNode detailNode = root.get("detail");
                if (detailNode != null && !detailNode.isNull() && hasText(detailNode.asText())) {
                    return detailNode.asText();
                }
            } catch (Exception ignore) {
                // 如果错误响应不是JSON, 就继续回退到原始文本.
            }
            return body.trim();
        }
        if (hasText(ex.getStatusText())) {
            return ex.getStatusText();
        }
        return "HTTP " + ex.getStatusCode().value();
    }

    /**
     * 将MultipartFile转为内存快照, 防止请求结束后文件对象失效.
     * 这里只保留后续真正需要的三个字段: 原始文件名, 内容类型, 字节数组.
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
     * 每张图片的结果写回顺序是:
     * 模型结果事件 -> 文件落盘与历史入库 -> 已保存事件.
     */
    private void streamBatchAssess(List<UploadFileData> files,
                                   List<BigDecimal> expertScores,
                                   String modelId,
                                   Integer userId,
                                   SseEmitter emitter) {
        int total = files.size();
        List<Map<String, Object>> resultItems = new ArrayList<>(total);
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
            if (modelId != null) {
                body.add("model_id", modelId);
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
                BigDecimal expertScore = expertScores.get(i);
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
                resultData.put("expertScore", expertScore);
                sendEvent(emitter, "result", resultData);

                saveAssessRecord(currentFile, userId, predictResult, expertScore);

                Map<String, Object> resultItem = new HashMap<>();
                resultItem.put("predictResult", predictResult);
                resultItem.put("expertScore", expertScore);
                resultItems.add(resultItem);

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
            completedData.put("resultItems", resultItems);
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
        String originalFilename = Optional.ofNullable(file.getOriginalFilename())
                .map(String::trim)
                .orElse("")
                .replace("\\", "/");
        // 只保留客户端传来的最终文件名部分, 不让本地路径片段进入业务字段.
        int lastSlash = originalFilename.lastIndexOf('/');
        String filename = lastSlash >= 0 ? originalFilename.substring(lastSlash + 1) : originalFilename;
        return filename.isBlank() ? "file" : filename;
    }

    private String safeContentType(MultipartFile file) {
        return Optional.ofNullable(file.getContentType()).orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    /**
     * 处理单图评估里的可选主观分字段.
     * 只有真正提交了非空分值时才触发EXPERT权限校验, 空串视为这次不填写.
     */
    private BigDecimal normalizeOptionalSubmissionExpertScore(String rawScore, String fieldName) {
        if (!hasText(rawScore)) {
            return null;
        }
        requireExpertRole();
        return normalizeExpertScore(rawScore, fieldName);
    }

    /**
     * 处理批量评估里的主观分数组.
     * 只要提交了expertScores数组, 无论其中是否包含空串, 都要求当前用户必须是EXPERT.
     * 空串保留为null, 表示该图片这次暂不填写主观分.
     */
    private List<BigDecimal> normalizeBatchExpertScores(List<String> rawScores, int fileCount) {
        if (rawScores == null) {
            return java.util.Collections.nCopies(fileCount, null);
        }
        requireExpertRole();
        if (rawScores.size() != fileCount) {
            throw new IllegalArgumentException("expertScores数量必须和files数量一致");
        }

        List<BigDecimal> normalizedScores = new ArrayList<>(fileCount);
        for (int i = 0; i < rawScores.size(); i++) {
            normalizedScores.add(normalizeOptionalExpertScoreValue(rawScores.get(i), "expertScores[" + i + "]"));
        }
        return normalizedScores;
    }

    /**
     * 处理补录接口里的必填主观分.
     * PATCH接口不允许传空值, 也不支持把分数清回null.
     */
    private BigDecimal normalizeRequiredExpertScore(String rawScore, String fieldName) {
        if (!hasText(rawScore)) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return normalizeExpertScore(rawScore, fieldName);
    }

    /**
     * 处理可选主观分的空串语义.
     * 空串代表本次暂不填写主观分, 不是非法值.
     */
    private BigDecimal normalizeOptionalExpertScoreValue(String rawScore, String fieldName) {
        return hasText(rawScore) ? normalizeExpertScore(rawScore, fieldName) : null;
    }

    /**
     * 标准化可选modelId参数.
     * 空串和全空白字符串都视为未传, 避免把无效空值透传给Python.
     */
    private String normalizeOptionalModelId(String rawModelId) {
        return hasText(rawModelId) ? rawModelId.trim() : null;
    }

    /**
     * 统一校验并标准化主观评分.
     * 规则固定为0.00-5.00, 最多2位小数, 最终按2位小数落库.
     */
    private BigDecimal normalizeExpertScore(String rawScore, String fieldName) {
        BigDecimal score;
        try {
            score = new BigDecimal(rawScore.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(fieldName + "必须是0.00到5.00之间的数字");
        }

        if (score.scale() > 2) {
            throw new IllegalArgumentException(fieldName + "最多保留2位小数");
        }
        if (score.compareTo(MIN_EXPERT_SCORE) < 0 || score.compareTo(MAX_EXPERT_SCORE) > 0) {
            throw new IllegalArgumentException(fieldName + "必须在0.00到5.00之间");
        }
        return score.setScale(2, RoundingMode.UNNECESSARY);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 上传文件快照.
     * filename是原始文件名, 不是UUID文件名; UUID命名在FileService里统一处理.
     */
    private record UploadFileData(String filename, String contentType, byte[] bytes) {
    }
}
