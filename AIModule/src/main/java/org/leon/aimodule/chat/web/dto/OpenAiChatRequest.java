package org.leon.aimodule.chat.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * OpenAI 协议的对话请求体（桌宠这条链用，见 OpenAiCompatController）。
 *
 * 只声明这条链真正会读的字段，其余（temperature、top_p、response_format…）忽略不报错——
 * 上游参数由服务端的 effort 档位决定，不接受请求侧覆盖，这是既有口径。
 * content 用 JsonNode 而不是 String：多模态请求里它是数组，必须分辨出「带图片」并明确拒绝，
 * 不能静默丢掉一个用户以为发出去了的附件。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiChatRequest(String model, List<Message> messages, Boolean stream, Integer max_tokens, Integer n) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, JsonNode content) {
    }
}
