package org.leon.aimodule.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiConversation;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.mapper.AiConversationMapper;
import org.leon.aimodule.chat.mapper.AiMessageMapper;
import org.leon.aimodule.chat.web.dto.MessagePageVo;
import org.leon.aimodule.chat.web.dto.MessageVo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageQueryServiceTest {

    private AiMessageMapper messageMapper;
    private ConversationService conversations;
    private MessageQueryService service;

    @BeforeEach
    void setUp() {
        messageMapper = mock(AiMessageMapper.class);
        AiConversationMapper conversationMapper = mock(AiConversationMapper.class);
        ChatProperties props = new ChatProperties();
        conversations = new ConversationService(props, new EffortResolver(props), conversationMapper, messageMapper);
        AiConversation mine = new AiConversation();
        mine.setId(7L);
        mine.setUserName("alice");
        mine.setStatus(1);
        mine.setUpdateTime(LocalDateTime.now());
        when(conversationMapper.selectById(7L)).thenReturn(mine);
        service = new MessageQueryService(messageMapper, conversations);
    }

    private static AiMessage msg(long id, String role, String status, Integer pt, Integer ct) {
        AiMessage m = new AiMessage();
        m.setId(id);
        m.setConversationId(7L);
        m.setRole(role);
        m.setContent("c" + id);
        m.setStatus(status);
        m.setModelKey(AiMessage.ROLE_ASSISTANT.equals(role) ? "deepseek-chat" : null);
        m.setEffort(AiMessage.ROLE_ASSISTANT.equals(role) ? "High" : null);
        m.setPromptTokens(pt);
        m.setCompletionTokens(ct);
        m.setCreateTime(LocalDateTime.of(2026, 10, 23, 9, 0, 0).plusSeconds(id));
        return m;
    }

    @Test
    @DisplayName("Mapper 按 id 降序取，返回给前端时翻成升序")
    void returnsAscending() {
        when(messageMapper.selectList(any())).thenReturn(
                List.of(msg(3, AiMessage.ROLE_USER, AiMessage.ST_DONE, null, null),
                        msg(2, AiMessage.ROLE_ASSISTANT, AiMessage.ST_DONE, 10, 20),
                        msg(1, AiMessage.ROLE_USER, AiMessage.ST_DONE, null, null)));
        MessagePageVo page = service.page("alice", 7L, null, 30);
        assertEquals(List.of(1L, 2L, 3L), page.items().stream().map(MessageVo::id).toList());
        assertFalse(page.hasMore());
    }

    @Test
    @DisplayName("多取一条用于判 hasMore，丢弃的那条不进结果")
    void detectsMorePages() {
        when(messageMapper.selectList(any())).thenReturn(
                List.of(msg(5, "user", "done", null, null), msg(4, "assistant", "done", 1, 1),
                        msg(3, "user", "done", null, null)));
        MessagePageVo page = service.page("alice", 7L, null, 2);
        assertTrue(page.hasMore());
        assertEquals(2, page.items().size());
        assertEquals(List.of(4L, 5L), page.items().stream().map(MessageVo::id).toList());
    }

    @Test
    @DisplayName("usage 只在两个 token 都缺时返回 null，避免前端渲染空对象")
    void usageOnlyWhenPresent() {
        when(messageMapper.selectList(any())).thenReturn(
                List.of(msg(2, "assistant", "done", null, null), msg(1, "assistant", "done", 812, 1640)));
        MessagePageVo page = service.page("alice", 7L, null, 30);
        assertEquals(812, page.items().get(0).usage().promptTokens());
        assertEquals(1640, page.items().get(0).usage().completionTokens());
        assertNull(page.items().get(1).usage());
    }

    @Test
    @DisplayName("createTime 是 epoch 毫秒数字，不是 ISO 字符串")
    void timeIsEpochMillis() {
        when(messageMapper.selectList(any())).thenReturn(List.of(msg(1, "user", "done", null, null)));
        MessagePageVo page = service.page("alice", 7L, null, 30);
        assertTrue(page.items().get(0).createTime() > 1_700_000_000_000L);
    }

    @Test
    @DisplayName("查他人会话：与不存在同一文案，不泄露存在性")
    void rejectsOtherUsers() {
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> service.page("bob", 7L, null, 30));
        assertEquals("会话不存在", e.getMessage());
        assertThrows(ChatRejectException.class, () -> service.page("alice", 999L, null, 30));
    }

    @Test
    @DisplayName("limit 被夹到 1..100")
    void clampsLimit() {
        List<AiMessage> overflow = IntStream.rangeClosed(1, 101)
                .mapToObj(i -> msg(i, AiMessage.ROLE_USER, AiMessage.ST_DONE, null, null))
                .toList();
        when(messageMapper.selectList(any()))
                .thenReturn(overflow)
                .thenReturn(List.of(msg(2, AiMessage.ROLE_USER, AiMessage.ST_DONE, null, null),
                        msg(1, AiMessage.ROLE_USER, AiMessage.ST_DONE, null, null)));

        MessagePageVo maxPage = service.page("alice", 7L, null, 9999);
        assertEquals(100, maxPage.items().size());
        assertTrue(maxPage.hasMore());

        MessagePageVo minPage = service.page("alice", 7L, null, 0);
        assertEquals(1, minPage.items().size());
        assertTrue(minPage.hasMore());
    }
}
