package org.leon.aimodule.chat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.service.OpenAiCompatAuthenticator;
import org.leon.aimodule.chat.service.OpenAiCompatConversations;
import org.leon.aimodule.chat.stream.ChatStreamService;
import org.leon.aimodule.chat.stream.StreamCommand;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 桌宠这条链的入口行为。全部走真实 advice（ChatExceptionHandler）装配：
 * 要证明的不是「能返回错误」，而是「错误不会被那条一律回 HTTP 200 + Result 的 advice 接管」。
 */
class OpenAiCompatControllerTest {

    private static final String URL = "/ai/v1/chat/completions";
    private static final String KEY = "sk-pet-123";

    private ChatProperties props;
    private OpenAiCompatConversations conversations;
    private ChatStreamService service;
    private ScheduledExecutorService heartbeat;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        props = new ChatProperties();
        ChatProperties.Model model = new ChatProperties.Model();
        model.setKey("deepseek-chat");
        model.setName("deepseek-chat");
        props.getModels().add(model);
        props.getOpenaiCompat().setApiKey(KEY);
        props.getOpenaiCompat().setUserName("tester");
        props.getOpenaiCompat().setEffort("High");

        conversations = mock(OpenAiCompatConversations.class);
        service = mock(ChatStreamService.class);
        when(conversations.ensure(anyString(), anyString())).thenReturn(7L);
        when(service.prepare(any(), anyString())).thenReturn(TestTurns.prepared());
        heartbeat = Executors.newSingleThreadScheduledExecutor();
        mvc = MockMvcBuilders.standaloneSetup(new OpenAiCompatController(service, new OpenAiCompatAuthenticator(props),
                        conversations, props, new ObjectMapper(), heartbeat))
                .setControllerAdvice(new ChatExceptionHandler(new ObjectMapper()))
                .build();
    }

    @AfterEach
    void tearDown() {
        heartbeat.shutdownNow();
    }

    private static String body(String messages) {
        return "{\"model\":\"deepseek-chat\",\"stream\":true,\"max_tokens\":1024,\"n\":1,\"messages\":" + messages + "}";
    }

    private org.springframework.test.web.servlet.ResultActions send(String json) throws Exception {
        return mvc.perform(post(URL).header("Authorization", "Bearer " + KEY)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private StreamCommand captured() {
        ArgumentCaptor<StreamCommand> captor = ArgumentCaptor.forClass(StreamCommand.class);
        verify(service).prepare(captor.capture(), eq("tester"));
        return captor.getValue();
    }

    @Test
    @DisplayName("只取末条 user 落库，自带历史丢掉，人设走 systemPromptOverride，RAG 开着")
    void mapsLastUserOnly() throws Exception {
        send(body("""
                [{"role":"system","content":"你是女仆小管家"},
                 {"role":"user","content":"第一个问题"},
                 {"role":"assistant","content":"第一个回答"},
                 {"role":"user","content":"今天错了多少题"}]"""))
                .andExpect(status().isOk());

        StreamCommand cmd = captured();
        assertEquals("今天错了多少题", cmd.content());
        assertEquals("你是女仆小管家", cmd.systemPromptOverride());
        assertEquals("deepseek-chat", cmd.modelKey());
        assertEquals("High", cmd.effort());
        assertEquals(7L, cmd.conversationId());
        assertTrue(cmd.useRag());
        assertNull(cmd.clientMsgId());
    }

    @Test
    @DisplayName("多部件 content：纯文本部件拼起来照样能用")
    void joinsTextParts() throws Exception {
        send(body("""
                [{"role":"user","content":[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}]}]"""))
                .andExpect(status().isOk());
        assertEquals("第一段\n第二段", captured().content());
    }

    @Test
    @DisplayName("图片部件明确拒绝，不静默丢掉用户以为发出去的东西")
    void rejectsImagePart() throws Exception {
        send(body("""
                [{"role":"user","content":[{"type":"image_url","image_url":{"url":"http://x/y.png"}}]}]"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_content_part"));
        verify(service, never()).prepare(any(), anyString());
    }

    @Test
    @DisplayName("非流式不支持：这条链只发 chunk")
    void rejectsNonStream() throws Exception {
        send("{\"model\":\"deepseek-chat\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("stream_required"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @DisplayName("模型不在目录里 → 400 model_not_found")
    void rejectsUnknownModel() throws Exception {
        send(body("[{\"role\":\"user\",\"content\":\"hi\"}]").replace("deepseek-chat", "gpt-9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("model_not_found"));
    }

    @Test
    @DisplayName("没有 user 消息 → 400，而不是建一条空轮次")
    void requiresUserMessage() throws Exception {
        send(body("[{\"role\":\"system\",\"content\":\"只有人设\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("missing_user_message"));
    }

    @Test
    @DisplayName("键不对 → 401；不带键 → 401；两种都不许建流")
    void rejectsBadKey() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("[{\"role\":\"user\",\"content\":\"hi\"}]")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(URL).header("Authorization", "Bearer nope").contentType(MediaType.APPLICATION_JSON)
                        .content(body("[{\"role\":\"user\",\"content\":\"hi\"}]")))
                .andExpect(status().isUnauthorized());
        verify(service, never()).prepare(any(), anyString());
    }

    @Test
    @DisplayName("服务端没配键 = 路由关闭（503），不是放行")
    void disabledWithoutConfiguredKey() throws Exception {
        props.getOpenaiCompat().setApiKey("");
        mvc.perform(post(URL).header("Authorization", "Bearer " + KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(body("[{\"role\":\"user\",\"content\":\"hi\"}]")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("route_disabled"));
    }

    @Test
    @DisplayName("业务拒绝（会话忙）翻成 400 的 OpenAI 错误体，绝不变成本包那套 HTTP 200 + Result")
    void chatRejectKeepsOpenAiShape() throws Exception {
        when(service.prepare(any(), anyString())).thenThrow(new ChatRejectException("上一条回答仍在生成"));
        send(body("[{\"role\":\"user\",\"content\":\"hi\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("上一条回答仍在生成"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @DisplayName("请求体不是合法 JSON → 400，不是 advice 的 200 + Result")
    void malformedBody() throws Exception {
        mvc.perform(post(URL).header("Authorization", "Bearer " + KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
