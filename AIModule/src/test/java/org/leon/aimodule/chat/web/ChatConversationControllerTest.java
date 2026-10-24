package org.leon.aimodule.chat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiConversation;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.mapper.AiConversationMapper;
import org.leon.aimodule.chat.mapper.AiMessageMapper;
import org.leon.aimodule.chat.service.ConversationService;
import org.leon.aimodule.chat.service.EffortResolver;
import org.leon.aimodule.chat.service.MessageQueryService;
import org.leon.aimodule.chat.web.dto.CreateConversationRequest;
import org.mockito.ArgumentMatchers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatConversationControllerTest {

    private final ObjectMapper json = new ObjectMapper();
    private AiConversationMapper conversationMapper;
    private AiMessageMapper messageMapper;
    private ConversationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ChatProperties props = new ChatProperties();
        ChatProperties.Model m = new ChatProperties.Model();
        m.setKey("deepseek-chat");
        m.setName("deepseek-chat");
        m.setTag("Fast");
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
        conversationMapper = mock(AiConversationMapper.class);
        messageMapper = mock(AiMessageMapper.class);
        service = new ConversationService(props, new EffortResolver(props), conversationMapper, messageMapper);
        mvc = MockMvcBuilders.standaloneSetup(
                        new ChatConversationController(service, new MessageQueryService(messageMapper, service)))
                .setControllerAdvice(new ChatExceptionHandler(new ObjectMapper()))
                .build();
    }

    @Test
    @DisplayName("#1 models 接口返回 efforts 字符串数组与带 tag 的 models")
    void modelsShape() throws Exception {
        mvc.perform(get("/ai/chat/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.defaultModel").value("deepseek-chat"))
                .andExpect(jsonPath("$.data.efforts", org.hamcrest.Matchers.contains(
                        "Low", "Medium", "High", "Extra", "Max")))
                .andExpect(jsonPath("$.data.models[0].key").value("deepseek-chat"))
                .andExpect(jsonPath("$.data.models[0].tag").value("Fast"))
                .andExpect(jsonPath("$.data.models[0].supportsThinking").value(false));
    }

    @Test
    @DisplayName("缺 X-User-Name 返回 200 + code=0，不建会话")
    void missingHeaderIsRejected() throws Exception {
        mvc.perform(post("/ai/chat/conversations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("缺少请求头: X-User-Name"));
    }

    @Test
    @DisplayName("创建成功返回 data.id 与 data.title，时间是 epoch 毫秒数字")
    void createOk() throws Exception {
        when(conversationMapper.insert(ArgumentMatchers.any(AiConversation.class))).thenAnswer(inv -> {
            inv.getArgument(0, AiConversation.class).setId(18L);
            return 1;
        });
        AiConversation persisted = new AiConversation();
        persisted.setId(18L);
        persisted.setTitle("帮我改简历");
        persisted.setModelKey("deepseek-chat");
        persisted.setEffort("High");
        persisted.setMessageCount(0L);
        persisted.setStatus(1);
        persisted.setUpdateTime(LocalDateTime.of(2026, 10, 23, 9, 0, 0, 123_000_000));
        when(conversationMapper.selectById(18L)).thenReturn(persisted);
        mvc.perform(post("/ai/chat/conversations").header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new CreateConversationRequest(
                                        "帮我改简历", "deepseek-chat", "High"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.id").value(18))
                .andExpect(jsonPath("$.data.title").value("帮我改简历"))
                .andExpect(jsonPath("$.data.effort").value("High"))
                .andExpect(jsonPath("$.data.updateTime", Matchers.notNullValue()))
                .andExpect(jsonPath("$.data.updateTime", org.hamcrest.Matchers.instanceOf(Number.class)));
    }

    @Test
    @DisplayName("未知档位：code=0 且 msg 含档位名")
    void unknownEffort() throws Exception {
        mvc.perform(post("/ai/chat/conversations").header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effort\":\"Ultra\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("Ultra")));
    }

    @Test
    @DisplayName("重命名标题为空：走参数校验分支")
    void renameBlankTitle() throws Exception {
        mvc.perform(patch("/ai/chat/conversations/1").header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("title")));
    }

    @Test
    @DisplayName("删除不存在的会话：统一“会话不存在”")
    void deleteOthers() throws Exception {
        when(conversationMapper.selectById(1L)).thenReturn(null);
        mvc.perform(delete("/ai/chat/conversations/1").header("X-User-Name", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("会话不存在"));
    }

    @Test
    @DisplayName("#6 历史消息：code=1、items 升序、usage 与 epoch 毫秒")
    void messagesShape() throws Exception {
        AiConversation mine = new AiConversation();
        mine.setId(7L);
        mine.setUserName("alice");
        mine.setStatus(1);
        when(conversationMapper.selectById(7L)).thenReturn(mine);
        when(messageMapper.selectList(ArgumentMatchers.any())).thenReturn(
                List.of(message(9L, 10, 20), message(8L, null, null)));
        mvc.perform(get("/ai/chat/conversations/7/messages").header("X-User-Name", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.items[0].id").value(8))
                .andExpect(jsonPath("$.data.items[1].id").value(9))
                .andExpect(jsonPath("$.data.items[1].usage.promptTokens").value(10))
                .andExpect(jsonPath("$.data.items[0].usage").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].createTime", Matchers.instanceOf(Number.class)));
    }

    private static AiMessage message(long id, Integer promptTokens, Integer completionTokens) {
        AiMessage m = new AiMessage();
        m.setId(id);
        m.setConversationId(7L);
        m.setRole(AiMessage.ROLE_ASSISTANT);
        m.setContent("c" + id);
        m.setStatus(AiMessage.ST_DONE);
        m.setModelKey("deepseek-chat");
        m.setEffort("High");
        m.setPromptTokens(promptTokens);
        m.setCompletionTokens(completionTokens);
        m.setCreateTime(LocalDateTime.of(2026, 10, 23, 9, 0, 0).plusSeconds(id));
        return m;
    }
}
