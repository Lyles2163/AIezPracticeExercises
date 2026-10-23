package org.leon.aimodule.rag.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.search.DebugSearchResponse;
import org.leon.aimodule.rag.search.KnowledgeSearchResponse;
import org.leon.aimodule.rag.search.RetrievalService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KbSearchControllerTest {

    private RetrievalService retrievalService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        retrievalService = mock(RetrievalService.class);
        RagProperties props = new RagProperties();
        props.getSearch().setTopKDefault(6);
        props.getSearch().setTopKMax(20);
        props.setAdminNames(List.of("admin"));
        KbSearchController controller = new KbSearchController(
                retrievalService, new RagAdminGuard(props), props);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AiExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("正常检索返回 code=1")
    void searchOk() throws Exception {
        when(retrievalService.searchKnowledge(eq("快排"), any()))
                .thenReturn(new KnowledgeSearchResponse(12, false, List.of()));

        mvc.perform(post("/ai/kb/search")
                        .header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"快排\",\"subjectId\":12,\"scope\":[\"official\",\"personal\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.took").value(12));
    }

    @Test
    @DisplayName("空 query 被校验拦截，返回 code=0")
    void blankQueryRejected() throws Exception {
        mvc.perform(post("/ai/kb/search")
                        .header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("query")));
    }

    @Test
    @DisplayName("非管理员访问 debug 返回 code=0，且不调用检索")
    void debugRequiresAdmin() throws Exception {
        when(retrievalService.debugKnowledge(any(), any()))
                .thenReturn(new DebugSearchResponse(5, List.of(), List.of()));

        mvc.perform(post("/ai/retrieval/debug")
                        .header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"快排\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("仅管理员可访问"));

        verify(retrievalService, never()).debugKnowledge(any(), any());
    }

    @Test
    @DisplayName("管理员可以访问 debug")
    void debugOkForAdmin() throws Exception {
        when(retrievalService.debugKnowledge(eq("快排"), any()))
                .thenReturn(new DebugSearchResponse(5, List.of(), List.of()));

        mvc.perform(post("/ai/retrieval/debug")
                        .header("X-User-Name", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"快排\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    @DisplayName("缺 X-User-Name 返回 code=0")
    void missingUserHeader() throws Exception {
        mvc.perform(post("/ai/kb/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"快排\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("X-User-Name")));
    }
}
