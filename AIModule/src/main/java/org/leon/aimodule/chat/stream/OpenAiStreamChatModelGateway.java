package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

/**
 * DeepSeek 的 OpenAI 兼容端点。之所以自己读上游：Spring AI 1.0.0-M7 的
 * ChatCompletionMessage 没有 reasoning 字段，delta.reasoning_content 在 JSON
 * 绑定阶段就被丢掉（1.0.9 GA 同样没有，只有 1.1.x 有，而那要求整项目升框架）。
 *
 * 本任务只有静态解析部分，所以还没 implements ChatModelGateway —— Task 17 加 stream() 时一起接上。
 */
public class OpenAiStreamChatModelGateway {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static ChatModelGateway.ChatChunk toChunk(String dataPayload) {
        if (dataPayload == null || dataPayload.isBlank() || "[DONE]".equals(dataPayload.trim())) {
            return null;
        }
        UpstreamChunk chunk;
        try {
            chunk = MAPPER.readValue(dataPayload, UpstreamChunk.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("上游帧不是合法 JSON: " + e.getMessage(), e);
        }
        String text = null;
        String reasoning = null;
        String finish = null;
        List<UpstreamChunk.Choice> choices = chunk.choices();
        if (choices != null && !choices.isEmpty()) {
            UpstreamChunk.Choice choice = choices.get(0);
            UpstreamChunk.Delta delta = choice.delta();
            if (delta != null) {
                text = nonBlank(delta.content());
                reasoning = nonBlank(delta.reasoningContent());
            }
            finish = lower(choice.finishReason());
        }
        Integer pt = null;
        Integer ct = null;
        Integer rt = null;
        UpstreamChunk.Usage u = chunk.usage();
        if (u != null) {
            pt = positive(u.promptTokens());
            ct = positive(u.completionTokens());
            rt = u.completionTokenDetails() == null ? null
                    : positive(u.completionTokenDetails().reasoningTokens());
        }
        if (text == null && reasoning == null && finish == null && pt == null && ct == null && rt == null) {
            return null;
        }
        return new ChatModelGateway.ChatChunk(text, reasoning, finish, pt, ct, rt);
    }

    private static String nonBlank(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    private static String lower(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static Integer positive(Integer v) {
        return v == null || v <= 0 ? null : v;
    }
}
