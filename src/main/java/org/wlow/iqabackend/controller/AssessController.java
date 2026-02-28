package org.wlow.iqabackend.controller;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.wlow.iqabackend.data.dto.BatchPredictResponse;
import org.wlow.iqabackend.data.dto.PredictResult;
import org.wlow.iqabackend.data.dto.Response;
import org.wlow.iqabackend.service.AssessService;

import java.util.List;

@RestController
@RequestMapping("/assess")
@RequiredArgsConstructor
@Validated
public class AssessController {
    private final AssessService assessService;

    @PostMapping(value = "", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<PredictResult> assess(@RequestPart("file") @NotNull MultipartFile file) {
        PredictResult result = assessService.assess(file);
        return Response.success(result);
    }

    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<BatchPredictResponse> assessBatch(
            @RequestPart("files") @NotEmpty List<MultipartFile> files) {
        BatchPredictResponse result = assessService.assessBatch(files);
        return Response.success(result);
    }
}

