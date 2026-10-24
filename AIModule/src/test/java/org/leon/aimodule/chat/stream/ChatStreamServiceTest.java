package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiConversation;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.repo.ChatTurnRepository;
import org.leon.aimodule.chat.service.ContextAssembler;
import org.leon.aimodule.chat.service.ConversationService;
import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.EffortResolver;
import org.leon.aimodule.rag.chunk.TokenCounter;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatStreamServiceTest {

    private ChatTurnRepository repo;
    private ConversationService conversations;
    private ChatModelGateway gateway;
    private ChatStreamRegistry registry;
    private ChatProperties props;
    private ChatStreamService service;
    private final List<ChatEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        props = new ChatProperties();
        ChatProperties.Model m = new ChatProperties.Model();
        m.setKey("deepseek-chat");
        m.setName("deepseek-chat");
        props.setModels(new ArrayList<>(List.of(m)));
        props.setEfforts(efforts(1.0));

        repo = mock(ChatTurnRepository.class);
        gateway = mock(ChatModelGateway.class);
        registry = new ChatStreamRegistry();
        conversations = mock(ConversationService.class);
        AiConversation c = new AiConversation();
        c.setId(7L);
        c.setUserName("alice");
        c.setStatus(1);
        c.setTitle("新对话");
        c.setModelKey("deepseek-chat");
        c.setEffort("Medium");
        when(conversations.requireOwned(anyString(), anyLong())).thenReturn(c);
        when(repo.insertUser(anyLong(), anyString(), any())).thenReturn(41L);
        when(repo.insertAssistant(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(42L);
        when(repo.activeHistory(anyLong(), anyLong())).thenReturn(List.of());

        service = newService(Runnable::run);
    }

    private List<ChatProperties.Effort> efforts(double temperature) {
        List<ChatProperties.Effort> list = new ArrayList<>();
        for (String k : List.of("Low", "Medium", "High", "Extra", "Max")) {
            ChatProperties.Effort e = new ChatProperties.Effort();
            e.setKey(k);
            e.setTemperature(temperature);
            e.setMaxTokens(4096);
            list.add(e);
        }
        return list;
    }

    private ChatStreamService newService(java.util.concurrent.Executor executor) {
        return new ChatStreamService(repo, conversations,
                new ContextAssembler(new TokenCounter(), props), new EffortResolver(props),
                gateway, registry, props, executor);
    }

    private static ChatModelGateway.ChatChunk text(String t) {
        return new ChatModelGateway.ChatChunk(t, null, null, null);
    }

    private static ChatModelGateway.ChatChunk finish() {
        return new ChatModelGateway.ChatChunk(null, "stop", 812, 1640);
    }

    private PreparedTurn prepareNormal() {
        return service.prepare(new StreamCommand(7L, "把这段改成 STAR", "deepseek-chat", "High", "cnd1", false),
                "alice");
    }

    @Test
    @DisplayName("T1 写序：插用户消息、插占位、touch 两次、自动改名")
    void prepareWritesBeforeStream() {
        PreparedTurn prepared = prepareNormal();
        assertEquals(41L, prepared.userMessageId());
        assertEquals(42L, prepared.assistantMessageId());
        verify(repo).insertUser(7L, "把这段改成 STAR", "cnd1");
        verify(repo).insertAssistant(eq(7L), eq(41L), eq("deepseek-chat"), eq("High"), eq("cnd1:a"));
        verify(repo).touch(eq(7L), eq(41L), anyString(), eq(1));
        verify(repo).touch(eq(7L), eq(42L), anyString(), eq(1));
        verify(repo).renameIfDefault(7L, "把这段改成 STAR");
        assertEquals(List.of("user"), prepared.messages().stream().map(CtxMessage::role).toList());
        // 落库的档位与发给上游的参数必须来自同一条回落链
        assertEquals("High", prepared.effortName());
        assertEquals(1.0, prepared.params().temperature());
    }

    @Test
    @DisplayName("档位只有一个真相源：命令没带就取会话列，落库与参数一起跟着它")
    void effortHasOneSourceOfTruth() {
        props.setEfforts(List.of(effort("Low", 0.3), effort("Medium", 1.0)));
        AiConversation c = new AiConversation();
        c.setId(7L);
        c.setUserName("alice");
        c.setStatus(1);
        c.setModelKey("deepseek-chat");
        c.setEffort("Low");
        when(conversations.requireOwned("alice", 7L)).thenReturn(c);

        PreparedTurn prepared = service.prepare(new StreamCommand(7L, "问题", null, null, null, false), "alice");
        assertEquals("Low", prepared.effortName());
        assertEquals(0.3, prepared.params().temperature());
        verify(repo).insertAssistant(eq(7L), eq(41L), eq("deepseek-chat"), eq("Low"), any());
    }

    private static ChatProperties.Effort effort(String key, double temperature) {
        ChatProperties.Effort e = new ChatProperties.Effort();
        e.setKey(key);
        e.setTemperature(temperature);
        e.setMaxTokens(4096);
        return e;
    }

    @Test
    @DisplayName("建流前先回收超时流，再判并发")
    void reapsThenRejectsConcurrent() {
        when(repo.countLive(7L, 5)).thenReturn(1);
        ChatRejectException e = assertThrows(ChatRejectException.class, this::prepareNormal);
        assertEquals("上一条回答仍在生成", e.getMessage());
        verify(repo).reapStale(7L, 5);
        verify(repo, never()).insertUser(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("会话不存在时不写任何行")
    void ownershipCheckedFirst() {
        doThrow(new ChatRejectException("会话不存在")).when(conversations).requireOwned("bob", 7L);
        assertThrows(ChatRejectException.class,
                () -> service.prepare(new StreamCommand(7L, "hi", null, null, "x", false), "bob"));
        verify(repo, never()).insertUser(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("空白 content 直接拒，不落库也不建流")
    void blankContentRejectedBeforeWrite() {
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> service.prepare(new StreamCommand(7L, "   ", null, null, "x1", false), "alice"));
        assertEquals("content 不能为空", e.getMessage());
        verify(repo, never()).insertUser(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("重试命中同一 clientMsgId：不新建用户消息，旧的标 superseded")
    void retryBecomesRegenerate() {
        AiMessage existing = new AiMessage();
        existing.setId(41L);
        existing.setConversationId(7L);
        existing.setRole(AiMessage.ROLE_USER);
        existing.setContent("原问题");
        existing.setStatus(AiMessage.ST_DONE);
        when(repo.findByClientMsgId(7L, "cnd1")).thenReturn(existing);

        PreparedTurn prepared = service.prepare(
                new StreamCommand(7L, "重复提交的旧文本", "deepseek-chat", "High", "cnd1", false), "alice");

        verify(repo, never()).insertUser(anyLong(), anyString(), any());
        assertEquals(41L, prepared.userMessageId());
        assertEquals("原问题", prepared.messages().get(prepared.messages().size() - 1).content());
        verify(repo).supersede(41L);
        // 重新生成的助手行不能带派生客户端键：同一 key 第二次重新生成会撞 uk_conv_client
        verify(repo).insertAssistant(eq(7L), eq(41L), eq("deepseek-chat"), eq("High"), isNull());
        verify(repo).touch(eq(7L), eq(42L), anyString(), eq(0));
        verify(repo, never()).renameIfDefault(anyLong(), anyString());
    }

    @Test
    @DisplayName("regenerate 不带 content，取会话最后一条用户消息")
    void regenerateUsesLastUserMessage() {
        AiMessage last = new AiMessage();
        last.setId(41L);
        last.setRole(AiMessage.ROLE_USER);
        last.setContent("重跑这个问题");
        last.setStatus(AiMessage.ST_DONE);
        when(repo.activeHistory(7L, null)).thenReturn(List.of(last));

        PreparedTurn prepared = service.prepare(new StreamCommand(7L, null, null, null, null, true), "alice");
        assertEquals(41L, prepared.userMessageId());
        verify(repo).supersede(41L);
        verify(repo, never()).insertUser(anyLong(), anyString(), any());
        verify(repo, never()).renameIfDefault(anyLong(), anyString());
    }

    @Test
    @DisplayName("regenerate 但会话里还没有用户消息：拒绝")
    void regenerateWithoutHistoryRejected() {
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> service.prepare(new StreamCommand(7L, null, null, null, null, true), "alice"));
        assertEquals("没有可重新生成的问题", e.getMessage());
        verify(repo, never()).insertAssistant(anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("事件序列 meta → delta* → usage → done")
    void emitsHappyPath() {
        when(gateway.stream(any(), any())).thenReturn(Flux.just(text("## "), text("经验"), finish()));
        PreparedTurn prepared = prepareNormal();
        service.subscribeAsync(prepared, recorderChannel());
        assertEquals(List.of("meta", "delta", "delta", "usage", "done"), kinds());
        assertEquals("## 经验", ((ChatEvent.Delta) events.get(1)).text()
                + ((ChatEvent.Delta) events.get(2)).text());
        ChatEvent.Meta meta = (ChatEvent.Meta) events.get(0);
        assertEquals(7L, meta.conversationId());
        assertEquals(42L, meta.messageId());
        assertEquals("High", meta.effort());
        assertEquals("done", ((ChatEvent.Done) events.get(4)).finishReason());
        verify(repo).finalizeAssistant(42L, "## 经验", AiMessage.ST_DONE, AiMessage.STOP_DONE, 812, 1640);
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("上游报错：已产出内容与错误原因只写一次 UPDATE")
    void upstreamFailureKeepsPartialContent() {
        when(gateway.stream(any(), any())).thenReturn(
                Flux.concat(Flux.just(text("前半"), text("后半")), Flux.error(new IllegalStateException("429"))));
        service.subscribeAsync(prepareNormal(), recorderChannel());
        assertEquals(List.of("Meta", "Delta", "Delta", "ErrorEvt", "Done"),
                events.stream().map(e -> e.getClass().getSimpleName()).toList());
        ChatEvent.ErrorEvt err = (ChatEvent.ErrorEvt) events.get(3);
        assertEquals("UPSTREAM_MODEL_ERROR", err.errorCode());
        assertTrue(err.retryable());
        assertEquals("upstream", ((ChatEvent.Done) events.get(4)).finishReason());
        verify(repo).finalizeFailed(eq(42L), eq("前半后半"), anyString());
        verify(repo, never()).finalizeAssistant(anyLong(), anyString(), anyString(), anyString(), any(), any());
        verify(repo, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("finishReason=length 时状态仍是 done，收尾原因可区分")
    void lengthFinishReason() {
        when(gateway.stream(any(), any())).thenReturn(Flux.just(
                text("正文"), new ChatModelGateway.ChatChunk(null, "length", 10, 20)));
        service.subscribeAsync(prepareNormal(), recorderChannel());
        verify(repo).finalizeAssistant(42L, "正文", AiMessage.ST_DONE, AiMessage.STOP_LENGTH, 10, 20);
        assertEquals("length", ((ChatEvent.Done) events.get(events.size() - 1)).finishReason());
    }

    @Test
    @DisplayName("客户端断连：落 truncated 并从注册表摘除")
    void clientGoneTruncates() {
        when(gateway.stream(any(), any())).thenReturn(Flux.just(text("已产出")));
        SseChannel broken = new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) {
                throw new IllegalStateException("断开的管道");
            }

            @Override
            public void complete() {
            }
        }, new ObjectMapper());
        PreparedTurn prepared = prepareNormal();
        // 异常不能穿透到调用方：真跑时 run() 在池内线程里，controller 早就返回了
        service.subscribeAsync(prepared, broken);
        verify(repo).finalizeAssistant(eq(42L), anyString(), eq(AiMessage.ST_TRUNCATED),
                eq(AiMessage.STOP_CLIENT_DISCONNECT), any(), any());
        assertEquals(0, registry.size(), "收尾必须从注册表摘除");
        assertTrue(broken.isClosed());
    }

    @Test
    @DisplayName("断连发生在中途：终态只写一次，不能被随后的 done 覆盖")
    void midStreamDisconnectWritesOnce() {
        // 同步 Flux + 第二次写失败：dispose 还来不及生效，onComplete 会紧接着再来一次终态写入
        AtomicIntegerLike flag = new AtomicIntegerLike();
        SseChannel diesOnSecond = new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) {
                if ("delta".equals(event) && flag.bump() >= 2) {
                    throw new IllegalStateException("断开的管道");
                }
            }

            @Override
            public void complete() {
            }
        }, new ObjectMapper());
        when(gateway.stream(any(), any())).thenReturn(Flux.just(text("一"), text("二"), text("三"), finish()));
        service.subscribeAsync(prepareNormal(), diesOnSecond);

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(repo, times(1)).finalizeAssistant(eq(42L), anyString(), status.capture(), anyString(),
                any(), any());
        assertEquals(AiMessage.ST_TRUNCATED, status.getValue(), "断连终态不能被 done 覆盖");
        assertEquals(0, registry.size());
    }

    private static final class AtomicIntegerLike {
        private int n;

        int bump() {
            return ++n;
        }
    }

    @Test
    @DisplayName("stop：取消上游并把已累积内容落 truncated/stopped")
    void stopKeepsPartialContent() {
        // 永不结束的流：stop 是唯一能让它收尾的动作
        when(gateway.stream(any(), any())).thenReturn(Flux.never());
        PreparedTurn prepared = prepareNormal();
        ActiveStream stream = service.subscribeAsync(prepared, silentChannel());
        assertEquals(1, registry.size());
        service.stop(stream.messageId(), "alice");
        verify(repo).finalizeAssistant(eq(42L), anyString(), eq(AiMessage.ST_TRUNCATED),
                eq(AiMessage.STOP_STOPPED), any(), any());
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("stop 一个已经结束的回答：什么都不写")
    void stopAfterFinishIsNoop() {
        when(gateway.stream(any(), any())).thenReturn(Flux.just(text("全文"), finish()));
        PreparedTurn prepared = prepareNormal();
        ActiveStream stream = service.subscribeAsync(prepared, silentChannel());
        assertEquals(0, registry.size(), "同步流跑完就该摘除");
        AiMessage done = new AiMessage();
        done.setId(42L);
        done.setConversationId(7L);
        done.setStatus(AiMessage.ST_DONE);
        done.setContent("全文");
        when(repo.findById(42L)).thenReturn(done);
        service.stop(stream.messageId(), "alice");
        verify(repo, times(1)).finalizeAssistant(anyLong(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("stop 不属于自己的消息：拒绝")
    void stopChecksOwnership() {
        when(gateway.stream(any(), any())).thenReturn(Flux.never());
        service.subscribeAsync(prepareNormal(), silentChannel());
        assertEquals(1, registry.size());
        ChatRejectException e = assertThrows(ChatRejectException.class, () -> service.stop(42L, "bob"));
        assertEquals("会话不存在", e.getMessage());
        assertEquals(1, registry.size(), "被拒的 stop 不能顺手停掉别人的流");
    }

    @Test
    @DisplayName("stop 一个不在注册表的消息：按 DB 状态收敛，不抛异常")
    void stopOrphanIsIdempotent() {
        AiMessage row = new AiMessage();
        row.setId(99L);
        row.setConversationId(7L);
        row.setRole(AiMessage.ROLE_ASSISTANT);
        row.setStatus(AiMessage.ST_STREAMING);
        row.setContent("库里已有的部分");
        when(repo.findById(99L)).thenReturn(row);
        service.stop(99L, "alice");
        verify(repo).finalizeAssistant(99L, "库里已有的部分", AiMessage.ST_TRUNCATED,
                AiMessage.STOP_STOPPED, null, null);
    }

    @Test
    @DisplayName("stop 一个已 done 的消息：什么都不写")
    void stopDoneMessageNoop() {
        AiMessage row = new AiMessage();
        row.setId(98L);
        row.setConversationId(7L);
        row.setStatus(AiMessage.ST_DONE);
        when(repo.findById(98L)).thenReturn(row);
        service.stop(98L, "alice");
        verify(repo, never()).finalizeAssistant(anyLong(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("stop 一个查不到的消息：拒绝，不写库")
    void stopUnknownMessage() {
        when(repo.findById(97L)).thenReturn(null);
        ChatRejectException e = assertThrows(ChatRejectException.class, () -> service.stop(97L, "alice"));
        assertEquals("会话不存在", e.getMessage());
    }

    @Test
    @DisplayName("正常收尾后 emitter 回调不得把 done 改成 truncated")
    void callbacksAfterDoneAreNoop() {
        when(gateway.stream(any(), any())).thenReturn(Flux.just(text("正文"), finish()));
        service.subscribeAsync(prepareNormal(), silentChannel());
        service.clientGone(42L);
        service.timeout(42L);
        verify(repo, times(1)).finalizeAssistant(eq(42L), anyString(), eq(AiMessage.ST_DONE),
                eq(AiMessage.STOP_DONE), any(), any());
        verify(repo, never()).finalizeAssistant(eq(42L), anyString(), eq(AiMessage.ST_TRUNCATED),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("超时收尾用独立 stop_reason，与客户端断连可区分")
    void timeoutWritesTimeoutReason() {
        when(gateway.stream(any(), any())).thenReturn(Flux.never());
        ActiveStream stream = service.subscribeAsync(prepareNormal(), silentChannel());
        service.timeout(stream.messageId());
        verify(repo).finalizeAssistant(eq(42L), anyString(), eq(AiMessage.ST_TRUNCATED),
                eq(AiMessage.STOP_TIMEOUT), any(), any());
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("线程池满：不建流、摘掉登记、把占位标 failed")
    void executorRejectionMarksFailed() {
        PreparedTurn prepared = prepareNormal();
        ChatStreamService rejecting = newService(command -> {
            throw new RejectedExecutionException("full");
        });
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> rejecting.subscribeAsync(prepared, silentChannel()));
        assertEquals("服务繁忙，请稍后重试", e.getMessage());
        verify(repo).markFailed(eq(42L), anyString());
        verify(repo, never()).finalizeAssistant(anyLong(), anyString(), anyString(), anyString(), any(), any());
        assertEquals(0, registry.size());
    }

    private List<String> kinds() {
        return events.stream().map(e -> switch (e) {
            case ChatEvent.Meta ignored -> "meta";
            case ChatEvent.Delta ignored -> "delta";
            case ChatEvent.TokenUsage ignored -> "usage";
            case ChatEvent.Done ignored -> "done";
            case ChatEvent.ErrorEvt ignored -> "error";
        }).toList();
    }

    private SseChannel silentChannel() {
        return new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) {
            }

            @Override
            public void complete() {
            }
        }, new ObjectMapper());
    }

    /** 把事件塞进 events 的 channel：SseChannel 的对外契约就是"发帧"，所以从 JSON 反解。 */
    private SseChannel recorderChannel() {
        ObjectMapper mapper = new ObjectMapper();
        return new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) {
                try {
                    switch (event) {
                        case "meta" -> events.add(new ChatEvent.Meta(
                                mapper.readTree(json).get("conversationId").asLong(),
                                mapper.readTree(json).get("messageId").asLong(),
                                mapper.readTree(json).get("modelKey").asText(),
                                mapper.readTree(json).get("effort").asText(),
                                mapper.readTree(json).get("supportsThinking").asBoolean()));
                        case "delta" -> events.add(new ChatEvent.Delta(
                                mapper.readTree(json).get("text").asText()));
                        case "usage" -> events.add(new ChatEvent.TokenUsage(
                                mapper.readTree(json).get("promptTokens").asInt(),
                                mapper.readTree(json).get("completionTokens").asInt()));
                        case "done" -> events.add(new ChatEvent.Done(
                                mapper.readTree(json).get("finishReason").asText()));
                        case "error" -> events.add(new ChatEvent.ErrorEvt(
                                mapper.readTree(json).get("errorCode").asText(),
                                mapper.readTree(json).get("msg").asText(),
                                mapper.readTree(json).get("retryable").asBoolean()));
                        default -> {
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void complete() {
            }
        }, mapper);
    }
}
