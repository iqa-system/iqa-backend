package org.wlow.iqabackend.controller;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.wlow.iqabackend.data.CurrentUser;
import org.wlow.iqabackend.service.FileService;

/**
 * 文件资源只读接口.
 * 不直接暴露upload目录为静态目录, 而是统一走受鉴权保护的业务接口.
 */
@RestController
@RequestMapping("/resource")
@RequiredArgsConstructor
@Validated
public class ResourceController {
    private final FileService fileService;

    /**
     * 读取当前登录用户自己的图片资源.
     * 这里不直接做权限判断, 而是交给FileService统一校验:
     * 1. fileEntry是否存在
     * 2. 是否属于当前登录用户
     * 3. 对应磁盘文件是否仍然存在
     */
    @GetMapping("/image/{fileEntryId}")
    public ResponseEntity<Resource> getImage(@PathVariable @Positive(message = "fileEntryId必须大于0") Integer fileEntryId) {
        FileService.StoredResource storedResource = fileService.loadOwnedImage(fileEntryId, CurrentUser.getId());
        return ResponseEntity.ok()
                .contentType(storedResource.contentType())
                .contentLength(storedResource.size())
                .body(storedResource.resource());
    }
}
