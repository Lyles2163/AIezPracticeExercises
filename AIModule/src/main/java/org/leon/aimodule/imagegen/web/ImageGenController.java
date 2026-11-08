package org.leon.aimodule.imagegen.web;

import jakarta.validation.Valid;
import org.leon.aimodule.imagegen.minio.ImageObjectStore;
import org.leon.aimodule.imagegen.service.ImageGenService;
import org.leon.aimodule.imagegen.web.dto.ImageGenRequest;
import org.leon.aimodule.imagegen.web.dto.ImageGenVo;
import org.leon.aimodule.imagegen.web.dto.ImageModelCatalogVo;
import org.leon.commonjwt.pojo.Result;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * AI 生图接口。归属同样取网关注入的 X-User-Name，请求体里没有任何身份字段。
 * 图片本体在 MinIO，对外只暴露 objectKey 与一个后端代理端点。
 */
@RestController
@RequestMapping("/ai/imagegen")
@Validated
public class ImageGenController {

    private final ImageGenService service;

    public ImageGenController(ImageGenService service) {
        this.service = service;
    }

    @GetMapping("/models")
    public Result<ImageModelCatalogVo> models(@RequestHeader("X-User-Name") String userName) {
        return Result.success(service.models(userName));
    }

    @PostMapping("/generations")
    public Result<ImageGenVo> generate(@RequestHeader("X-User-Name") String userName,
                                       @RequestBody @Valid ImageGenRequest request) {
        return Result.success(service.generate(userName, request));
    }

    /**
     * 取图：{*objectKey} 会把前导斜杠一起带进来，先剥掉。
     * 前端必须带 Authorization 来 fetch（网关只认这个头），&lt;img src&gt; 直连会 401。
     */
    @GetMapping("/files/{*objectKey}")
    public ResponseEntity<Resource> file(@RequestHeader("X-User-Name") String userName,
                                         @PathVariable String objectKey) {
        String key = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
        ImageObjectStore.StoredImage stored = service.file(userName, key);
        MediaType mediaType = stored.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(stored.contentType());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(stored.size())
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .body(new InputStreamResource(stored.stream()));
    }
}
