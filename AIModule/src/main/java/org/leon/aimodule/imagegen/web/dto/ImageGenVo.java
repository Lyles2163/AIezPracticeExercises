package org.leon.aimodule.imagegen.web.dto;

import java.util.List;

/**
 * 生图结果。url 是相对网关的取图路径，前端要带着 Authorization 去 fetch 再转 blob 渲染
 * （&lt;img src&gt; 发不出这个头，直连会 401）。
 */
public record ImageGenVo(String modelKey, long latencyMs, List<Image> images) {

    public record Image(String objectKey, String url, long bytes, String contentType) {
    }
}
