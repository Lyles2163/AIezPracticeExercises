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
    @DisplayName("choices 空数组但带真 usage 的收尾帧必须留住（旧 usageOnlyChunk）")
    void usageOnlyWithEmptyChoicesSurvives() {
        String wire = "{\"choices\":[],\"usage\":{\"prompt_tokens\":812,\"completion_tokens\":1640,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":512}}}";
        ChatModelGateway.ChatChunk c = OpenAiStreamChatModelGateway.toChunk(wire);
        assertEquals(812, c.promptTokens());
        assertEquals(1640, c.completionTokens());
        assertEquals(512, c.reasoningTokens());
        assertNull(c.finishReason(), "这帧没有 finish_reason，编排层不能把它当流结束");
    }

    @Test
    @DisplayName("正文与 finish_reason 同帧到达时两者都要保留（旧 keepsFinishReason）")
    void textAndFinishReasonInOneFrame() {
        String wire = "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"尾部正文\"},"
                + "\"finish_reason\":\"stop\"}]}";
        ChatModelGateway.ChatChunk c = OpenAiStreamChatModelGateway.toChunk(wire);
        assertEquals("尾部正文", c.text());
        assertEquals("stop", c.finishReason());
    }

    @Test
    @DisplayName("空白 finish_reason 归一为 null，不误报流结束（旧 blankFinishReasonBecomesNull）")
    void blankFinishReasonBecomesNull() {
        String wire = "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},\"finish_reason\":\"  \"}]}";
        ChatModelGateway.ChatChunk c = OpenAiStreamChatModelGateway.toChunk(wire);
        assertEquals("x", c.text());
        assertNull(c.finishReason());
    }

    @Test
    @DisplayName("usage 全 0 的兜底帧按\"这帧没有 usage\"处理（R11）")
    void zeroUsageDropped() {
        String wire = "{\"choices\":[],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}";
        assertNull(OpenAiStreamChatModelGateway.toChunk(wire));
    }
}
