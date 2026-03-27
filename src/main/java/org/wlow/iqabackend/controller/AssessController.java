package org.wlow.iqabackend.controller;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.wlow.iqabackend.data.dto.PredictResult;
import org.wlow.iqabackend.data.dto.Response;
import org.wlow.iqabackend.data.entity.AssessRecord;
import org.wlow.iqabackend.service.AssessService;

import java.util.List;

@RestController
@RequestMapping("/assess")
@RequiredArgsConstructor
@Validated
public class AssessController {
    private final AssessService assessService;

    /**
     * 单图像质量评估接口.
     */
    @PostMapping(value = "", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<PredictResult> assess(@RequestPart("file") @NotNull MultipartFile file) {
        PredictResult result = assessService.assess(file);
        return Response.success(result);
    }

    /**
     * 多图像质量评估SSE接口.
     * 返回text/event-stream, 按事件推送实时进度与逐图结果.
     */
    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assessBatch(
            @RequestPart("files") @NotEmpty List<MultipartFile> files) {
        return assessService.assessBatchStream(files);
    }

    /**
     * 查询当前用户评估历史记录.
     * page从1开始计数, pageSize表示每页返回条数.
     */
    @GetMapping("/history")
    public Response<Page<AssessRecord>> history(
            @RequestParam(defaultValue = "1") @Positive(message = "page必须大于0") Integer page,
            @RequestParam(defaultValue = "10") @Positive(message = "pageSize必须大于0") Integer pageSize) {
        Page<AssessRecord> result = assessService.history(page, pageSize);
        return Response.success(result);
    }
}

