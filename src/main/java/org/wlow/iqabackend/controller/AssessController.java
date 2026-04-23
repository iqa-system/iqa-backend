package org.wlow.iqabackend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.wlow.iqabackend.data.dto.ModelsResponse;
import org.wlow.iqabackend.data.dto.PredictResult;
import org.wlow.iqabackend.data.dto.Response;
import org.wlow.iqabackend.data.entity.AssessRecord;
import org.wlow.iqabackend.service.AssessService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/assess")
@RequiredArgsConstructor
@Validated
public class AssessController {
    private final AssessService assessService;

    /**
     * 获取当前Python模型服务支持的模型列表.
     * Java后端不缓存也不硬编码模型信息, 直接代理Python /models结果.
     */
    @GetMapping("/models")
    public Response<ModelsResponse> models() {
        return Response.success(assessService.listModels());
    }

    /**
     * 单图像质量评估接口.
     */
    @PostMapping(value = "", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<PredictResult> assess(@RequestPart("file") @NotNull MultipartFile file,
                                          @RequestParam(value = "expertScore", required = false) String expertScore,
                                          @RequestParam(value = "modelId", required = false) String modelId) {
        PredictResult result = assessService.assess(file, expertScore, modelId);
        return Response.success(result);
    }

    /**
     * 多图像质量评估SSE接口.
     * 返回text/event-stream, 按事件推送实时进度与逐图结果.
     */
    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assessBatch(
            @RequestPart("files") @NotEmpty List<MultipartFile> files,
            @RequestParam(value = "modelId", required = false) String modelId,
            HttpServletRequest request) {
        return assessService.assessBatchStream(files, extractExpertScores(request), modelId);
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

    /**
     * 批量删除当前用户选中的评估历史记录.
     * 请求体使用JSON传递id数组, 方便前端多选后一次性提交删除.
     */
    @DeleteMapping("/history")
    public Response<Integer> deleteHistory(@RequestBody Map<String, List<Integer>> request) {
        // 这里直接从请求体中读取ids数组, 避免为单一字段再单独维护一个DTO类.
        List<Integer> ids = request == null ? null : request.get("ids");
        int deletedCount = assessService.deleteHistory(ids);
        return Response.success(deletedCount);
    }

    /**
     * 为当前用户自己的某条历史记录补录或修改专家主观评分.
     * 请求体只接收expertScore一个字段, 保持接口简单直接.
     */
    @PatchMapping("/history/{id}/expert-score")
    public Response<String> updateExpertScore(@PathVariable @Positive(message = "id必须大于0") Integer id,
                                              @RequestBody Map<String, Object> request) {
        Object rawExpertScore = request == null ? null : request.get("expertScore");
        assessService.updateExpertScore(id, rawExpertScore == null ? null : rawExpertScore.toString());
        return Response.success("专家主观评分更新成功");
    }

    /**
     * 兼容multipart数组字段的两种常见命名方式.
     * 前端既可能使用expertScores, 也可能使用expertScores[]逐项append到FormData中.
     * 这里统一兼容两种命名, 避免前后端因为字段名风格差异而对不上.
     */
    private List<String> extractExpertScores(HttpServletRequest request) {
        String[] rawValues = request.getParameterValues("expertScores");
        if (rawValues == null) {
            rawValues = request.getParameterValues("expertScores[]");
        }
        return rawValues == null ? null : List.of(rawValues);
    }
}
