package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.entity.AiMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiSseChannelTest {

    private final List<String> names = new CopyOnWriteArrayList<>();
    private final List<String> frames = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    private final SseChannel.Sink sink = new SseChannel.Sink() {
        @Override
        public void send(String event, String json) {
            names.add(event);
            frames.add(json);
        }

        @Override
        public void complete() {
            frames.add("<complete>");
        }
    };

    private OpenAiSseChannel channel() {
        return new OpenAiSseChannel(sink, mapper, "chatcmpl-42", "deepseek-chat");
    }

    @Test
    @DisplayName("Meta 开角色帧、Delta 续正文、Done 收尾，全部是无名帧")
    void roleThenContentThenFinish() throws Exception {
        OpenAiSseChannel c = channel();
        c.emit(new ChatEvent.Meta(7L, 42L, "deepseek-chat", "Medium", false));
        c.emit(new ChatEvent.Delta("你好"));
        c.emit(new ChatEvent.Delta("主人"));
        c.emit(new ChatEvent.Done(AiMessage.STOP_DONE));

        assertEquals(java.util.Arrays.asList(null, null, null, null), names);
        assertEquals("assistant", mapper.readTree(frames.get(0)).path("choices").get(0)
                .path("delta").path("role").asText());
        assertEquals("你好", mapper.readTree(frames.get(1)).path("choices").get(0)
                .path("delta").path("content").asText());
        assertEquals("stop", mapper.readTree(frames.get(3)).path("choices").get(0)
                .path("finish_reason").asText());
        assertEquals("chatcmpl-42", mapper.readTree(frames.get(0)).path("id").asText());
        assertEquals("chat.completion.chunk", mapper.readTree(frames.get(0)).path("object").asText());
        assertEquals("deepseek-chat", mapper.readTree(frames.get(0)).path("model").asText());
    }

    @Test
    @DisplayName("没有 Meta 也不能漏角色帧：正文第一帧之前先补一帧 role，且只补一次")
    void roleSentOnceWithoutMeta() throws Exception {
        OpenAiSseChannel c = channel();
        c.emit(new ChatEvent.Delta("嗨"));
        c.emit(new ChatEvent.Delta("呀"));

        assertEquals(3, frames.size());
        assertEquals("assistant", mapper.readTree(frames.get(0)).path("choices").get(0)
                .path("delta").path("role").asText());
        assertEquals("嗨", mapper.readTree(frames.get(1)).path("choices").get(0)
                .path("delta").path("content").asText());
        assertTrue(!frames.get(2).contains("\"role\""));
        assertEquals("呀", mapper.readTree(frames.get(2)).path("choices").get(0)
                .path("delta").path("content").asText());
    }

    @Test
    @DisplayName("思考、用量、引用一律不发：OpenAI 的 chunk 里没有承载位，发出去就是乱码")
    void dropsEventsWithoutSlot() {
        OpenAiSseChannel c = channel();
        c.emit(new ChatEvent.ThoughtStep("先算乘法"));
        c.emit(new ChatEvent.ThoughtEnd(8.4));
        c.emit(new ChatEvent.TokenUsage(10, 20, 5));
        c.emit(new ChatEvent.Sources(List.of()));
        assertEquals(0, frames.size());
    }

    @Test
    @DisplayName("上游失败当正文发出让主人看得见，收尾仍报 stop（协议外的取值会让 SDK 解析失败）")
    void errorBecomesContentAndFinishStaysStop() throws Exception {
        OpenAiSseChannel c = channel();
        c.emit(new ChatEvent.Meta(7L, 42L, "deepseek-chat", "Medium", false));
        c.emit(new ChatEvent.ErrorEvt("UPSTREAM_MODEL_ERROR", "模型服务暂时不可用", true));
        c.emit(new ChatEvent.Done(AiMessage.STOP_UPSTREAM));

        assertEquals("模型服务暂时不可用", mapper.readTree(frames.get(1)).path("choices").get(0)
                .path("delta").path("content").asText());
        assertEquals("stop", mapper.readTree(frames.get(2)).path("choices").get(0)
                .path("finish_reason").asText());
    }

    @Test
    @DisplayName("长度截断报 length，其余收尾报 stop")
    void mapsLength() throws Exception {
        OpenAiSseChannel c = channel();
        c.emit(new ChatEvent.Done(AiMessage.STOP_LENGTH));
        c.emit(new ChatEvent.Done(AiMessage.STOP_STOPPED));
        assertEquals("length", mapper.readTree(frames.get(0)).path("choices").get(0)
                .path("finish_reason").asText());
        assertEquals("stop", mapper.readTree(frames.get(1)).path("choices").get(0)
                .path("finish_reason").asText());
    }

    @Test
    @DisplayName("心跳是 choices 为空的 keepalive 帧，SDK 会跳过")
    void pingIsKeepalive() throws Exception {
        channel().ping();
        assertEquals(JsonNodeType.ARRAY, mapper.readTree(frames.get(0)).path("choices").getNodeType());
        assertEquals(0, mapper.readTree(frames.get(0)).path("choices").size());
    }
}
