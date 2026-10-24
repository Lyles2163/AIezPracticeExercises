package org.leon.aimodule.chat.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpringAiChatModelGatewayTest {

    @Test
    @DisplayName("普通正文 chunk 只带 text")
    void textChunk() {
        ChatResponse resp = new ChatResponse(List.of(new Generation(new AssistantMessage("你好"))));
        ChatModelGateway.ChatChunk chunk = SpringAiChatModelGateway.toChunk(resp);
        assertEquals("你好", chunk.text());
        assertNull(chunk.finishReason());
        assertNull(chunk.promptTokens());
    }

    @Test
    @DisplayName("空正文的 role-only chunk 整帧丢弃，不产生空 delta")
    void emptyTextBecomesNull() {
        ChatResponse resp = new ChatResponse(List.of(new Generation(new AssistantMessage(""))));
        assertNull(SpringAiChatModelGateway.toChunk(resp), "什么都没有的 chunk 应当整帧丢弃");
    }

    @Test
    @DisplayName("最后一个 chunk 只有 choices 空数组 + usage，必须留住 usage")
    void usageOnlyChunk() {
        ChatResponse resp = new ChatResponse(List.of(),
                ChatResponseMetadata.builder().usage(new DefaultUsage(812, 1640)).build());
        ChatModelGateway.ChatChunk chunk = SpringAiChatModelGateway.toChunk(resp);
        assertNull(chunk.text());
        assertEquals(812, chunk.promptTokens());
        assertEquals(1640, chunk.completionTokens());
    }

    @Test
    @DisplayName("finishReason 与 text 同帧到达时两者都保留")
    void keepsFinishReason() {
        Generation g = new Generation(new AssistantMessage("结束"),
                ChatGenerationMetadata.builder().finishReason("stop").build());
        ChatModelGateway.ChatChunk chunk = SpringAiChatModelGateway.toChunk(new ChatResponse(List.of(g)));
        assertEquals("结束", chunk.text());
        assertEquals("stop", chunk.finishReason());
    }

    @Test
    @DisplayName("真实上游的尾帧形状：空正文 + 大写 LENGTH，必须归一成小写 length")
    void realLengthFrameIsLowercased() {
        // 实测 Spring AI 1.0.0-M7 把 DeepSeek 的 "length" 大写化：
        // DefaultChatGenerationMetadata[finishReason='LENGTH']，正文长度为 0。
        // 不归一的话编排层的 switch("length") 落 default，被截断的回答会记成 done。
        Generation g = new Generation(new AssistantMessage(""),
                ChatGenerationMetadata.builder().finishReason("LENGTH").build());
        ChatModelGateway.ChatChunk chunk = SpringAiChatModelGateway.toChunk(new ChatResponse(List.of(g)));
        org.junit.jupiter.api.Assertions.assertNotNull(chunk, "带 finishReason 的帧不能整帧丢弃");
        assertEquals("length", chunk.finishReason());
        assertNull(chunk.text());
    }

    @Test
    @DisplayName("空白 finishReason 归一为 null，不误报流结束")
    void blankFinishReasonBecomesNull() {
        Generation g = new Generation(new AssistantMessage("正文"),
                ChatGenerationMetadata.builder().finishReason("").build());
        ChatModelGateway.ChatChunk chunk = SpringAiChatModelGateway.toChunk(new ChatResponse(List.of(g)));
        assertEquals("正文", chunk.text());
        assertNull(chunk.finishReason());
    }

    @Test
    @DisplayName("0/0 的 usage 按“这帧没有 usage”处理（ChatResponse 兜底 metadata 就是 EmptyUsage）")
    void zeroUsageIsNotUsage() {
        ChatResponse resp = new ChatResponse(List.of(),
                ChatResponseMetadata.builder().usage(new DefaultUsage(0, 0)).build());
        assertNull(SpringAiChatModelGateway.toChunk(resp));

        ChatResponse withText = new ChatResponse(List.of(new Generation(new AssistantMessage("半截"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(0, 0)).build());
        ChatModelGateway.ChatChunk chunk = SpringAiChatModelGateway.toChunk(withText);
        assertEquals("半截", chunk.text());
        assertNull(chunk.promptTokens());
        assertNull(chunk.completionTokens());
    }

    @Test
    @DisplayName("null 响应与空 result 都不崩")
    void nullSafe() {
        assertNull(SpringAiChatModelGateway.toChunk(null));
        assertNull(SpringAiChatModelGateway.toChunk(new ChatResponse(List.of())));
    }
}
