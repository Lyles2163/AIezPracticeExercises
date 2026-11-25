package org.leon.practicemodule.manage.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeExceptionHandler;
import org.leon.practicemodule.manage.parse.ImportTemplateGenerator;
import org.leon.practicemodule.manage.pojo.vo.ManageVOs;
import org.leon.practicemodule.manage.service.PracticeManageService;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("PracticeManageController：文档上传、模板下载与身份校验")
class PracticeManageControllerTest {

    private PracticeManageService manageService;
    private UserIdentityService identityService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        manageService = mock(PracticeManageService.class);
        identityService = mock(UserIdentityService.class);
        when(identityService.resolveUserId("alice")).thenReturn(20L);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PracticeManageController(manageService, identityService))
                .setControllerAdvice(new PracticeExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /practice/my/import/upload 正常上传并返回导入摘要")
    void uploadReturnsResult() throws Exception {
        ManageVOs.ImportResultVO vo = new ManageVOs.ImportResultVO();
        vo.setBatchId(11L);
        vo.setSubjectId(9L);
        vo.setSubjectName("我的题库");
        vo.setImportedCount(5);
        vo.setStatus("SUCCESS");
        vo.setWarnings(List.of());
        when(manageService.importDocument(eq(20L), any(), eq("demo.md"),
                isNull(), eq("我的题库"), eq(true), eq("测验1"), eq(60), isNull()))
                .thenReturn(vo);

        MockMultipartFile file = new MockMultipartFile(
                "file", "demo.md", "text/markdown", "1. 题干\n答案：对".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/practice/my/import/upload")
                        .file(file)
                        .param("newSubjectName", "我的题库")
                        .param("generatePaper", "true")
                        .param("paperTitle", "测验1")
                        .param("durationMinutes", "60")
                        .header("X-User-Name", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.batchId").value(11))
                .andExpect(jsonPath("$.data.importedCount").value(5));
    }

    @Test
    @DisplayName("GET /practice/my/import/template?format=docx 返回附件下载响应")
    void downloadTemplateWorks() throws Exception {
        ImportTemplateGenerator.TemplateFile tpl = new ImportTemplateGenerator().generate("docx");
        when(manageService.generateTemplate("docx")).thenReturn(tpl);

        mockMvc.perform(get("/practice/my/import/template")
                        .param("format", "docx")
                        .header("X-User-Name", "alice"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("question-import-template.docx")));
    }

    @Test
    @DisplayName("缺少 X-User-Name 时统一返回 40100")
    void missingIdentityReturns40100() throws Exception {
        mockMvc.perform(get("/practice/my/subjects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100));
    }
}
