package org.leon.aimodule.chat.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UpstreamChunkParseTest {

    @Test
    @DisplayName("[DONE] 与空白载荷丢弃")
    void doneSentinel() {
        assertNull(OpenAiStreamChatModelGateway.toChunk("[DONE]"));
        assertNull(OpenAiStreamChatModelGateway.toChunk(""));
        assertNull(OpenAiStreamChatModelGateway.toChunk(null));
    }

    @Test
    @DisplayName("真实 reasoning 帧：只有 reasoning_content")
    void reasoningFrame() {
        String wire = "{\"id\":\"7220f3cf\",\"object\":\"chat.completion.chunk\",\"created\":1792813205,"
                + "\"model\":\"deepseek-flash\",\"system_fingerprint\":\"e72d3c7f\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null,"
                + "\"reasoning_content\":\"We\"},\"logprobs\":null,\"finish_reason\":null}]}";
        ChatModelGateway.ChatChunk c = OpenAiStreamChatModelGateway.toChunk(wire);
        assertEquals("We", c.reasoning());
        assertNull(c.text());
        assertNull(c.finishReason());
    }

    @Test
    @DisplayName("真实正文帧：只有 content")
    void contentFrame() {
        String wire = "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"391\"},"
                + "\"finish_reason\":null}]}";
        ChatModelGateway.ChatChunk c = OpenAiStreamChatModelGateway.toChunk(wire);
        assertEquals("391", c.text());
        assertNull(c.reasoning());
    }

    @Test
    @DisplayName("真实尾帧：content 空串 + finish_reason + usage 明细，三者都要留住")
    void finishFrameCarriesUsage() {
        String wire = "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\",\"reasoning_content\":null},"
                + "\"finish_reason\":\"length\"}],"
                + "\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":90,\"total_tokens\":132,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":0},"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":63},"
                + "\"prompt_cache_hit_tokens\":0,\"prompt_cache_miss_tokens\":42}}";
        ChatModelGateway.ChatChunk c = OpenAiStreamChatModelGateway.toChunk(wire);
        assertEquals("length", c.finishReason());
        assertEquals(42, c.promptTokens());
        assertEquals(90, c.completionTokens());
        assertEquals(63, c.reasoningTokens());
        assertNull(c.text(), "空串不能变成一帧空 delta");
    }

    @Test
    @DisplayName("未知字段不炸（上游已经在发我们没建模的字段）")
    void unknownFieldsIgnored() {
        String wire = "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},\"finish_reason\":null}],"
                + "\"brand_new_field\":{\"nested\":[1,2,3]},\"also_new\":\"v\"}";
        assertEquals("x", OpenAiStreamChatModelGateway.toChunk(wire).text());
    }

    @Test
    @DisplayName("finish_reason 大写也归一小写（R17 的教训留在适配器）")
    void finishReasonLowerCased() {
        String wire = "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"LENGTH\"}]}";
        assertEquals("length", OpenAiStreamChatModelGateway.toChunk(wire).finishReason());
    }

    @Test
    @DisplayName("role-only 首帧（content/reasoning 都是 null）整帧丢弃，不产生空 delta")
    void roleOnlyFrameDropped() {
        String wire = "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null,"
                + "\"reasoning_content\":null},\"finish_reason\":null}]}";
        assertNull(OpenAiStreamChatModelGateway.toChunk(wire));
    }

    @Test
    @DisplayName("usage 全 0 的兜底帧按\"这帧没有 usage\"处理（R11）")
    void zeroUsageDropped() {
        String wire = "{\"choices\":[],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}";
        assertNull(OpenAiStreamChatModelGateway.toChunk(wire));
    }
}
