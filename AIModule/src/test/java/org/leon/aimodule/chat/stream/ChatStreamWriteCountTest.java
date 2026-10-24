package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiConversation;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.repo.ChatTurnRepository;
import org.leon.aimodule.chat.service.ContextAssembler;
import org.leon.aimodule.chat.service.ConversationService;
import org.leon.aimodule.chat.service.EffortResolver;
import org.leon.aimodule.rag.chunk.TokenCounter;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 锁死 spec 5.5 的不变式：一条回答 = 用户消息 1 INSERT + 助手消息 1 INSERT + 各自收尾写，
 * **与 token 数无关**。价值全在最后的 verifyNoMoreInteractions：
 * 任何人往 chunk 回调里加一句"顺手 update 一下进度"，这里立刻红。
 */
class ChatStreamWriteCountTest {

    @Test
    @DisplayName("3 帧与 3000 帧的写库交互序列逐条相同")
    void writeCountDoesNotDependOnTokenCount() {
        assertEquals(interactions(3), interactions(3000), "写库次数随 token 数变化了");
    }

    private List<String> interactions(int chunks) {
        ChatTurnRepository repo = mock(ChatTurnRepository.class);
        ChatModelGateway gateway = mock(ChatModelGateway.class);
        ConversationService conversations = mock(ConversationService.class);

        ChatProperties props = new ChatProperties();
        ChatProperties.Model m = new ChatProperties.Model();
        m.setKey("deepseek-chat");
        m.setName("deepseek-chat");
        props.setModels(new ArrayList<>(List.of(m)));
        List<ChatProperties.Effort> efforts = new ArrayList<>();
        for (String k : List.of("Low", "Medium", "High", "Extra", "Max")) {
            ChatProperties.Effort e = new ChatProperties.Effort();
            e.setKey(k);
            e.setTemperature(1.0);
            e.setMaxTokens(4096);
            efforts.add(e);
        }
        props.setEfforts(efforts);

        AiConversation c = new AiConversation();
        c.setId(7L);
        c.setUserName("alice");
        c.setStatus(1);
        c.setModelKey("deepseek-chat");
        c.setEffort("Medium");
        when(conversations.requireOwned("alice", 7L)).thenReturn(c);
        when(repo.insertUser(anyLong(), anyString(), any())).thenReturn(41L);
        when(repo.insertAssistant(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(42L);
        when(repo.activeHistory(anyLong(), anyLong())).thenReturn(List.of());

        List<ChatModelGateway.ChatChunk> frames = new ArrayList<>(
                IntStream.rangeClosed(1, chunks).mapToObj(i -> chunk("字")).toList());
        frames.add(new ChatModelGateway.ChatChunk(null, "stop", 812, 1640));
        when(gateway.stream(any(), any())).thenReturn(Flux.fromIterable(frames));

        ChatStreamRegistry registry = new ChatStreamRegistry();
        ChatStreamService service = new ChatStreamService(repo, conversations,
                new ContextAssembler(new TokenCounter(), props), new EffortResolver(props),
                gateway, registry, props, Runnable::run);

        PreparedTurn prepared = service.prepare(
                new StreamCommand(7L, "问题", "deepseek-chat", "High", "cm", false), "alice");
        service.subscribeAsync(prepared, silentChannel());

        InOrder order = inOrder(repo);
        order.verify(repo).reapStale(7L, 5);
        order.verify(repo).countLive(7L, 5);
        order.verify(repo).findByClientMsgId(7L, "cm");
        order.verify(repo).insertUser(7L, "问题", "cm");
        order.verify(repo).touch(7L, 41L, "问题", 1);
        order.verify(repo).insertAssistant(7L, 41L, "deepseek-chat", "High", "cm:a");
        order.verify(repo).touch(7L, 42L, "问题", 1);
        order.verify(repo).renameIfDefault(7L, "问题");
        order.verify(repo).activeHistory(7L, 41L);
        order.verify(repo).finalizeAssistant(42L, "字".repeat(chunks), AiMessage.ST_DONE,
                AiMessage.STOP_DONE, 812, 1640);
        // 上面列出的就是全部：3000 帧与 3 帧都只到这里为止，一次都不许多
        verifyNoMoreInteractions(repo);
        assertEquals(0, registry.size(), "收尾后必须从注册表摘除");

        List<String> called = new ArrayList<>();
        for (org.mockito.invocation.InvocationOnMock invocation :
                org.mockito.Mockito.mockingDetails(repo).getInvocations()) {
            called.add(invocation.getMethod().getName());
        }
        // 调用集合本身由 verifyNoMoreInteractions 保证，这里只比对"次数不随 token 数变化"
        return called.stream().sorted().toList();
    }

    private static ChatModelGateway.ChatChunk chunk(String text) {
        return new ChatModelGateway.ChatChunk(text, null, null, null);
    }

    private static SseChannel silentChannel() {
        return new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) {
            }

            @Override
            public void complete() {
            }
        }, new ObjectMapper());
    }
}
