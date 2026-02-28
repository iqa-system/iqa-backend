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
import org.wlow.iqabackend.data.dto.BatchPredictResponse;
import org.wlow.iqabackend.data.dto.PredictResult;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * 将Spring Boot请求桥接到Python模型API.
 * 处理multipart编码与响应映射.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssessService {
    private final RestClient modelApiRestClient;

    /**
     * 调用/predict处理单个上传文件.
     */
    public PredictResult assess(MultipartFile file) {
        validateFile(file);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", toFilePart(file));
        return executePredict(body);
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
        return executeBatchPredict(body);
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

    private String safeName(MultipartFile file) {
        return Optional.ofNullable(file.getOriginalFilename()).orElse("file");
    }

    private String safeContentType(MultipartFile file) {
        return Optional.ofNullable(file.getContentType()).orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }
}
