package org.leon.aimodule.chat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.stream.ChatEvent;
import org.leon.aimodule.chat.stream.ChatStreamService;
import org.leon.aimodule.chat.stream.SseChannel;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatStreamControllerTest {

    private ChatStreamService service;
    private ScheduledExecutorService heartbeat;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ChatStreamService.class);
        ChatProperties props = new ChatProperties();
        heartbeat = Executors.newSingleThreadScheduledExecutor();
        mvc = MockMvcBuilders.standaloneSetup(new ChatStreamController(service, new ObjectMapper(), props, heartbeat))
                .setControllerAdvice(new ChatExceptionHandler(new ObjectMapper()))
                .build();
    }

    @AfterEach
    void tearDown() {
        heartbeat.shutdownNow();
    }

    private static final String STREAM = "/ai/chat/completions/stream";

    @Test
    @DisplayName("建流前被拒：返回 application/json 的 Result，绝不返回 SSE")
    void rejectIsPlainJson() throws Exception {
        when(service.prepare(any(), anyString())).thenThrow(new ChatRejectException("上一条回答仍在生成"));
        mvc.perform(post(STREAM).header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":7,\"content\":\"hi\",\"clientMsgId\":\"c1\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("上一条回答仍在生成"));
    }

    @Test
    @DisplayName("只带 Accept: text/event-stream 的客户端也要拿到 JSON 拒绝，不是 500 空体")
    void rejectIsPlainJsonEvenForSseOnlyAccept() throws Exception {
        when(service.prepare(any(), anyString())).thenThrow(new ChatRejectException("会话不存在"));
        mvc.perform(post(STREAM).header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"conversationId\":7,\"content\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("会话不存在"));
    }

    @Test
    @DisplayName("缺 X-User-Name：不建流")
    void missingHeader() throws Exception {
        mvc.perform(post(STREAM).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":7,\"content\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("缺少请求头: X-User-Name"));
        verify(service, never()).prepare(any(), anyString());
    }

    @Test
    @DisplayName("content 与 regenerate 都没有：参数校验拒绝，不碰编排层")
    void blankContentRejected() throws Exception {
        mvc.perform(post(STREAM).header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("content 不能为空"));
        verify(service, never()).prepare(any(), anyString());
    }

    @Test
    @DisplayName("坏 JSON 也不返回 SSE：Content-Type 仍是 JSON，客户端按 content-type 分流")
    void malformedBodyIsPlainJson() throws Exception {
        mvc.perform(post(STREAM).header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("请求体格式错误"));
        verify(service, never()).prepare(any(), anyString());
    }

    @Test
    @DisplayName("路径参数类型不对：说清是哪个参数，别报成服务内部错误")
    void badPathVariableNamesTheParameter() throws Exception {
        mvc.perform(post("/ai/chat/messages/notANumber/stop").header("X-User-Name", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("参数格式错误: id"));
        verify(service, never()).stop(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    @DisplayName("成功：进入异步、Content-Type 是 text/event-stream、不缓冲响应头齐了")
    void happyPathStartsAsync() throws Exception {
        when(service.prepare(any(), eq("alice"))).thenReturn(TestTurns.prepared());
        when(service.subscribeAsync(any(), any())).thenAnswer(inv -> {
            SseChannel ch = inv.getArgument(1);
            ch.emit(new ChatEvent.Meta(7L, 42L, "deepseek-chat", "High", false));
            ch.emit(new ChatEvent.Done("done"));
            return TestTurns.activeStream();
        });
        mvc.perform(post(STREAM).header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":7,\"content\":\"hi\",\"clientMsgId\":\"c1\"}"))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Cache-Control", "no-cache, no-transform"))
                .andExpect(header().string("X-Accel-Buffering", "no"));
        verify(service).subscribeAsync(any(), any());
    }

    @Test
    @DisplayName("异步派发后响应体真的是 SSE 帧，Content-Type 是 text/event-stream")
    void framesReachTheResponse() throws Exception {
        when(service.prepare(any(), eq("alice"))).thenReturn(TestTurns.prepared());
        when(service.subscribeAsync(any(), any())).thenAnswer(inv -> {
            SseChannel ch = inv.getArgument(1);
            ch.emit(new ChatEvent.Delta("你好"));
            ch.emit(new ChatEvent.Done("done"));
            ch.close();
            return TestTurns.activeStream();
        });
        var result = mvc.perform(post(STREAM).header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"conversationId\":7,\"content\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:delta")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));
    }

    @Test
    @DisplayName("stop 转发给编排层并返回 success")
    void stopForwards() throws Exception {
        mvc.perform(post("/ai/chat/messages/42/stop").header("X-User-Name", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        verify(service).stop(42L, "alice");
    }

    @Test
    @DisplayName("stop 别人的消息：统一文案")
    void stopRejectsOthers() throws Exception {
        doThrow(new ChatRejectException("会话不存在")).when(service).stop(42L, "bob");
        mvc.perform(post("/ai/chat/messages/42/stop").header("X-User-Name", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("会话不存在"));
    }
}
