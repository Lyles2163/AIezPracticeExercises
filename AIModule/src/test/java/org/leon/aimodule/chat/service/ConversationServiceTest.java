package org.leon.aimodule.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiConversation;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.mapper.AiConversationMapper;
import org.leon.aimodule.chat.mapper.AiMessageMapper;
import org.leon.aimodule.chat.support.ChatTime;
import org.leon.aimodule.chat.web.dto.ConversationPageVo;
import org.leon.aimodule.chat.web.dto.ConversationVo;
import org.leon.aimodule.chat.web.dto.CreateConversationRequest;
import org.leon.aimodule.chat.web.dto.ModelCatalogVo;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    AiConversationMapper conversationMapper;
    @Mock
    AiMessageMapper messageMapper;

    private ChatProperties props;
    private ConversationService service;

    private ConversationService newService() {
        props = new ChatProperties();
        ChatProperties.Model m = new ChatProperties.Model();
        m.setKey("deepseek-chat");
        m.setName("deepseek-chat");
        m.setTag("Fast");
        ChatProperties.Model r = new ChatProperties.Model();
        r.setKey("deepseek-reasoner");
        r.setName("deepseek-reasoner");
        r.setSupportsThinking(true);
        props.setModels(new ArrayList<>(List.of(m, r)));
        List<ChatProperties.Effort> efforts = new ArrayList<>();
        for (String k : List.of("Low", "Medium", "High", "Extra", "Max")) {
            ChatProperties.Effort e = new ChatProperties.Effort();
            e.setKey(k);
            e.setTemperature(1.0);
            e.setMaxTokens(4096);
            efforts.add(e);
        }
        props.setEfforts(efforts);
        service = new ConversationService(props, new EffortResolver(props), conversationMapper, messageMapper);
        return service;
    }

    @Test
    @DisplayName("#1 目录：efforts 是字符串数组，model 项带 tag 与 supportsThinking")
    void catalogMatchesPromptBarProps() {
        newService();
        ModelCatalogVo vo = service.catalog();
        assertEquals("deepseek-chat", vo.defaultModel());
        assertEquals("Medium", vo.defaultEffort());
        assertEquals(List.of("Low", "Medium", "High", "Extra", "Max"), vo.efforts());
        assertEquals(2, vo.models().size());
        assertEquals("Fast", vo.models().get(0).tag());
        assertTrue(vo.models().get(1).supportsThinking());
        assertTrue(!vo.models().get(0).supportsThinking());
    }

    @Test
    @DisplayName("创建会话：标题缺省存“新对话”，模型与档位缺省落配置默认值")
    void createAppliesDefaults() {
        newService();
        when(conversationMapper.insert(any(AiConversation.class))).thenAnswer(inv -> {
            inv.getArgument(0, AiConversation.class).setId(18L);
            return 1;
        });
        LocalDateTime persistedTime = LocalDateTime.of(2026, 10, 23, 9, 0, 0, 123_000_000);
        when(conversationMapper.selectById(18L)).thenReturn(persisted(18L, "alice", "新对话",
                "deepseek-chat", "Medium", persistedTime));
        ConversationVo vo = service.create("alice", new CreateConversationRequest(null, null, null));
        ArgumentCaptor<AiConversation> cap = ArgumentCaptor.forClass(AiConversation.class);
        verify(conversationMapper).insert(cap.capture());
        assertEquals("alice", cap.getValue().getUserName());
        assertEquals("新对话", cap.getValue().getTitle());
        assertEquals("deepseek-chat", cap.getValue().getModelKey());
        assertEquals("Medium", cap.getValue().getEffort());
        // 先 insert 再按主键回读；VO 的时间/计数只能来自回读行，新实体上这两列是 null
        InOrder order = inOrder(conversationMapper);
        order.verify(conversationMapper).insert(any(AiConversation.class));
        order.verify(conversationMapper).selectById(18L);
        assertEquals(18L, vo.id());
        assertEquals("新对话", vo.title());
        assertEquals(ChatTime.millis(persistedTime), vo.updateTime());
        assertEquals(0L, vo.messageCount());
        assertNull(vo.lastPreview());
    }

    @Test
    @DisplayName("创建会话：未知模型或未知档位被拒，且不落库")
    void createRejectsUnknownKeys() {
        newService();
        assertThrows(ChatRejectException.class,
                () -> service.create("alice", new CreateConversationRequest("t", "gpt-6", "Medium")));
        assertThrows(ChatRejectException.class,
                () -> service.create("alice", new CreateConversationRequest("t", "deepseek-chat", "Ultra")));
        verify(conversationMapper, never()).insert(any(AiConversation.class));
    }

    @Test
    @DisplayName("标题超 120 字符直接截断而不是报错")
    void createTruncatesLongTitle() {
        newService();
        when(conversationMapper.insert(any(AiConversation.class))).thenAnswer(inv -> {
            inv.getArgument(0, AiConversation.class).setId(19L);
            return 1;
        });
        String truncated = "长".repeat(119) + "…";
        LocalDateTime persistedTime = LocalDateTime.of(2026, 10, 23, 9, 0, 0, 123_000_000);
        when(conversationMapper.selectById(19L)).thenReturn(persisted(19L, "alice", truncated,
                "deepseek-chat", "Medium", persistedTime));
        ConversationVo vo = service.create("alice",
                new CreateConversationRequest("长".repeat(200), null, null));
        verify(conversationMapper).selectById(19L);
        assertEquals(120, vo.title().length());
        assertEquals(truncated, vo.title());
        assertEquals(ChatTime.millis(persistedTime), vo.updateTime());
    }

    @Test
    @DisplayName("归属校验：非本人或已软删都返回“会话不存在”同一文案")
    void ownershipMessagesAreIdentical() {
        newService();
        AiConversation other = conversation(1L, "bob", 1);
        when(conversationMapper.selectById(1L)).thenReturn(other);
        ChatRejectException e1 = assertThrows(ChatRejectException.class,
                () -> service.requireOwned("alice", 1L));
        assertEquals("会话不存在", e1.getMessage());

        when(conversationMapper.selectById(2L)).thenReturn(conversation(2L, "alice", 0));
        ChatRejectException e2 = assertThrows(ChatRejectException.class,
                () -> service.requireOwned("alice", 2L));
        assertEquals("会话不存在", e2.getMessage());

        when(conversationMapper.selectById(3L)).thenReturn(null);
        assertEquals("会话不存在",
                assertThrows(ChatRejectException.class, () -> service.requireOwned("alice", 3L)).getMessage());
    }

    @Test
    @DisplayName("软删只置 status=0，不物理删除")
    void softDeleteUpdatesStatus() {
        newService();
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, "alice", 1));
        service.softDelete("alice", 1L);
        ArgumentCaptor<AiConversation> cap = ArgumentCaptor.forClass(AiConversation.class);
        verify(conversationMapper).updateById(cap.capture());
        assertEquals(0, cap.getValue().getStatus());
        assertEquals(1L, cap.getValue().getId());
    }

    @Test
    @DisplayName("limit 越界被夹住，max 生效")
    void clampsLimit() {
        newService();
        assertEquals(1, service.clampLimit(0, 50));
        assertEquals(20, service.clampLimit(20, 50));
        assertEquals(50, service.clampLimit(9999, 50));
    }

    @Test
    @DisplayName("列表多取一条判断是否有下一页，并给出 nextCursor")
    void listDetectsNextPage() {
        newService();
        List<AiConversation> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(conversation((long) (10 - i), "alice", 1));
        }
        when(conversationMapper.selectList(any())).thenReturn(rows);
        ConversationPageVo page = service.list("alice", null, 2);
        assertEquals(2, page.items().size());
        assertTrue(page.nextCursor() != null && !page.nextCursor().isBlank());
    }

    @Test
    @DisplayName("列表不足一页时 nextCursor 为 null")
    void listLastPageHasNoCursor() {
        newService();
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation(9L, "alice", 1)));
        ConversationPageVo page = service.list("alice", null, 20);
        assertEquals(1, page.items().size());
        assertNull(page.nextCursor());
    }

    @Test
    @DisplayName("游标毫秒超出 MySQL DATETIME 范围时按 cursor 无效拒绝，不查库")
    void listRejectsOutOfRangeCursor() {
        newService();
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((Long.MAX_VALUE + ":1").getBytes(StandardCharsets.UTF_8));
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> service.list("alice", cursor, 20));
        assertEquals("cursor 无效", e.getMessage());
        verify(conversationMapper, never()).selectList(any());
    }

    /** 模拟 insert 后从库里按主键读回的整行：DB 默认列（时间/计数）都已填充。 */
    private static AiConversation persisted(Long id, String user, String title, String modelKey,
                                            String effort, LocalDateTime updateTime) {
        AiConversation c = new AiConversation();
        c.setId(id);
        c.setUserName(user);
        c.setTitle(title);
        c.setModelKey(modelKey);
        c.setEffort(effort);
        c.setMessageCount(0L);
        c.setLastPreview(null);
        c.setStatus(1);
        c.setCreateTime(updateTime);
        c.setUpdateTime(updateTime);
        return c;
    }

    private static AiConversation conversation(Long id, String user, int status) {
        AiConversation c = new AiConversation();
        c.setId(id);
        c.setUserName(user);
        c.setTitle("t" + id);
        c.setModelKey("deepseek-chat");
        c.setEffort("Medium");
        c.setMessageCount(2L);
        c.setLastPreview("预览");
        c.setStatus(status);
        c.setUpdateTime(LocalDateTime.of(2026, 10, 23, 9, 0, 0, (int) (id * 1_000_000)));
        return c;
    }
}
