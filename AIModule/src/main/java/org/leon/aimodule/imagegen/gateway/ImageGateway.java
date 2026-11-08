package org.leon.aimodule.imagegen.gateway;

import java.util.List;

/**
 * 生图上游的唯一出口。协议目前只有一种：OpenAI 兼容 POST {baseUrl}/images/generations。
 * 抽成接口是为了让 ImageGenService 的单测不必真的建 HttpClient。
 */
public interface ImageGateway {

    Response generate(Command command);

    record Command(String baseUrl, String apiKey, String upstreamModel, String prompt,
                   String size, int n, int timeoutSeconds) {
        /** key 绝不进 toString：它会被日志、异常链一路带出去。 */
        @Override
        public String toString() {
            return "Command[baseUrl=" + baseUrl + ", upstreamModel=" + upstreamModel
                    + ", size=" + size + ", n=" + n + "]";
        }
    }

    record Response(List<GeneratedImage> images, long latencyMs) {
    }

    record GeneratedImage(byte[] data, String contentType) {
    }
}
