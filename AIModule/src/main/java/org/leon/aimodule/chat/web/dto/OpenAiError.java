package org.leon.aimodule.chat.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAI 的错误体。这条链不能用仓库既有的 {@code Result(code=0)}：
 * 桌宠那侧是官方 SDK，只认 {@code {"error":{...}}}，且建流前的失败必须带 4xx 状态码才会被 SDK 当成异常。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiError(ErrorBody error) {

    /** type 固定 invalid_request_error：本层的所有拒绝都是请求侧问题，不是服务端故障。 */
    public static OpenAiError of(String message, String code) {
        return new OpenAiError(new ErrorBody(message, "invalid_request_error", code));
    }

    public record ErrorBody(String message, String type, String code) {
    }
}
