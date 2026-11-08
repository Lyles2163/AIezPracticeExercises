package org.leon.aimodule.imagegen.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 生图请求。modelKey 是模型目录里的选择器 key（u:{providerId}:{modelId}），
 * 归属由服务端按 X-User-Name 再校验一次，前端传什么都不构成授权。
 */
public record ImageGenRequest(
        @NotBlank @Size(max = 64) String modelKey,
        @NotBlank @Size(max = 2000) String prompt,
        @Size(max = 32) String size,
        Integer n) {
}
