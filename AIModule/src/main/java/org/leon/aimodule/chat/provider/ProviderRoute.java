package org.leon.aimodule.chat.provider;

/**
 * 本轮请求实际要打的上游：用户自己的 baseUrl + 解密后的 key + 上游模型标识。
 * 只在 prepare 阶段构造、活在一轮流里，绝不进日志/事件/响应体，toString 因此隐去 key。
 *
 * supportsTools 固定 false：个人供应商是否支持 OpenAI 工具协议无法预判，
 * 贸然带 tools 会让不支持的端点整轮报错，所以第一期个人模型一律不带工具。
 */
public record ProviderRoute(Long providerId, Long modelId, String providerName, String displayName,
                            String baseUrl, String apiKey, String upstreamModel, boolean supportsThinking) {

    public boolean supportsTools() {
        return false;
    }

    @Override
    public String toString() {
        return "ProviderRoute[providerId=" + providerId + ", modelId=" + modelId
                + ", baseUrl=" + baseUrl + ", upstreamModel=" + upstreamModel + "]";
    }
}
