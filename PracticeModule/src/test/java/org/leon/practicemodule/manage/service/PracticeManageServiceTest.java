package org.leon.practicemodule.manage.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.manage.mapper.MyQuestionManageMapper;
import org.leon.practicemodule.manage.mapper.QbImportBatchItemMapper;
import org.leon.practicemodule.manage.mapper.QbImportBatchMapper;
import org.leon.practicemodule.manage.parse.DocumentTextExtractor;
import org.leon.practicemodule.manage.parse.ImportTemplateGenerator;
import org.leon.practicemodule.manage.parse.QuestionTextParser;
import org.leon.practicemodule.manage.pojo.ManageRequests;
import org.leon.practicemodule.manage.pojo.QbImportBatch;
import org.leon.practicemodule.manage.pojo.QbImportBatchItem;
import org.leon.practicemodule.manage.pojo.vo.ManageVOs;
import org.leon.practicemodule.mapper.QbPaperMapper;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbQuestionOption;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.service.AnswerGrader;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("PracticeManageService：文档上传落库+打包套卷、权限守卫、题目删除引用冲突与批次撤回")
class PracticeManageServiceTest {

    private static final long USER = 20L;

    private final QbSubjectMapper subjectMapper = mock(QbSubjectMapper.class);
    private final QbQuestionMapper questionMapper = mock(QbQuestionMapper.class);
    private final QbQuestionOptionMapper optionMapper = mock(QbQuestionOptionMapper.class);
    private final QbPaperMapper paperMapper = mock(QbPaperMapper.class);
    private final QbImportBatchMapper batchMapper = mock(QbImportBatchMapper.class);
    private final QbImportBatchItemMapper batchItemMapper = mock(QbImportBatchItemMapper.class);
    private final MyQuestionManageMapper manageMapper = mock(MyQuestionManageMapper.class);

    private final ImportTemplateGenerator templateGenerator = new ImportTemplateGenerator();
    private PracticeManageService service;

    @BeforeEach
    void setUp() {
        service = new PracticeManageService(
                subjectMapper, questionMapper, optionMapper, paperMapper,
                batchMapper, batchItemMapper, manageMapper,
                new DocumentTextExtractor(), new QuestionTextParser(),
                templateGenerator, new PaperPackager(),
                new QuestionPayloadValidator(new AnswerGrader()));

        AtomicLong idGen = new AtomicLong(100);
        lenient().when(subjectMapper.insert(any(QbSubject.class))).thenAnswer(inv -> {
            ((QbSubject) inv.getArgument(0)).setId(idGen.incrementAndGet());
            return 1;
        });
        lenient().when(questionMapper.insert(any(QbQuestion.class))).thenAnswer(inv -> {
            ((QbQuestion) inv.getArgument(0)).setId(idGen.incrementAndGet());
            return 1;
        });
        lenient().when(paperMapper.insert(any(QbPaper.class))).thenAnswer(inv -> {
            ((QbPaper) inv.getArgument(0)).setId(idGen.incrementAndGet());
            return 1;
        });
        lenient().when(batchMapper.insert(any(QbImportBatch.class))).thenAnswer(inv -> {
            ((QbImportBatch) inv.getArgument(0)).setId(idGen.incrementAndGet());
            return 1;
        });
    }

    @Test
    @DisplayName("上传标准 Word 模板并同步新建个人科目 + 一键打包套卷")
    void importDocxCreatesSubjectQuestionsPaperAndBatch() {
        ImportTemplateGenerator.TemplateFile docx = templateGenerator.generate("docx");
        when(manageMapper.selectMaxSortOrder(any())).thenReturn(0);

        ManageVOs.ImportResultVO vo = service.importDocument(
                USER, docx.bytes(), docx.fileName(), null, "Java 面试题库",
                true, "Java 综合套卷", 45, new BigDecimal("5"));

        assertEquals("Java 面试题库", vo.getSubjectName());
        assertEquals("docx", vo.getFileType());
        assertEquals(5, vo.getImportedCount());
        assertEquals(0, vo.getSkippedCount());
        assertEquals("SUCCESS", vo.getStatus());
        assertNotNull(vo.getPaperId());
        assertEquals("Java 综合套卷", vo.getPaperTitle());

        verify(subjectMapper, times(1)).insert(any(QbSubject.class));
        verify(questionMapper, times(5)).insert(any(QbQuestion.class));
        verify(optionMapper, times(8)).insert(any(QbQuestionOption.class)); // 单选 4 + 多选 4
        verify(paperMapper, times(1)).insert(any(QbPaper.class));
        verify(batchMapper, times(1)).insert(any(QbImportBatch.class));
        verify(batchItemMapper, times(5)).insert(any(QbImportBatchItem.class));
    }

    @Test
    @DisplayName("公共科目（owner_id=1）和他人科目均拒绝修改与删除（40300）")
    void rejectsNonOwnerSubjectMutations() {
        QbSubject pub = new QbSubject();
        pub.setId(1L);
        pub.setOwnerId(1L);
        pub.setStatus(1);
        when(subjectMapper.selectById(1L)).thenReturn(pub);

        ManageRequests.SubjectSaveReq req = new ManageRequests.SubjectSaveReq();
        req.setName("篡改");
        assertEquals(PracticeErrorCode.FORBIDDEN, assertThrows(PracticeException.class,
                () -> service.updateSubject(USER, 1L, req)).errorCode());

        assertEquals(PracticeErrorCode.FORBIDDEN, assertThrows(PracticeException.class,
                () -> service.deleteSubject(USER, 1L, true)).errorCode());
    }

    @Test
    @DisplayName("删除被个人套卷引用的题目报 40900，防止破坏套卷结构")
    void deletingQuestionReferencedByPaperIsBlocked() {
        QbSubject mySubject = new QbSubject();
        mySubject.setId(9L);
        mySubject.setOwnerId(USER);
        mySubject.setStatus(1);
        when(subjectMapper.selectById(9L)).thenReturn(mySubject);

        QbQuestion q = new QbQuestion();
        q.setId(501L);
        q.setSubjectId(9L);
        when(questionMapper.selectById(501L)).thenReturn(q);

        QbPaper paper = new QbPaper();
        paper.setId(77L);
        paper.setOwnerId(USER);
        paper.setSubjectId(9L);
        paper.setTitle("期末卷");
        paper.setStructureJson("{\"parts\":[{\"roman\":\"I\",\"name\":\"单选\",\"cardLabel\":\"单选\","
                + "\"score\":5,\"minutes\":10,\"sections\":[{\"label\":\"A\",\"subTitle\":\"单选\","
                + "\"questions\":[{\"index\":1,\"questionId\":501,\"score\":5}]}]}]}");
        when(manageMapper.selectMyPapersBySubject(USER, 9L)).thenReturn(List.of(paper));

        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> service.deleteQuestions(USER, List.of(501L))).errorCode());
        verify(manageMapper, never()).deleteQuestionsByIds(any());
    }

    @Test
    @DisplayName("撤回导入批次（deleteQuestions=true）会先删批次关联套卷再清理题目与选项")
    void deleteBatchWithQuestionsCleansLinkedPaperAndQuestions() {
        QbImportBatch batch = new QbImportBatch();
        batch.setId(88L);
        batch.setOwnerId(USER);
        batch.setSubjectId(9L);
        batch.setPaperId(77L);
        when(batchMapper.selectById(88L)).thenReturn(batch);
        when(batchItemMapper.selectQuestionIdsByBatch(88L)).thenReturn(List.of(501L, 502L));

        QbPaper linked = new QbPaper();
        linked.setId(77L);
        linked.setOwnerId(USER);
        when(paperMapper.selectById(77L)).thenReturn(linked);
        when(manageMapper.selectMyPapersBySubject(USER, 9L)).thenReturn(List.of());

        service.deleteBatch(USER, 88L, true);

        verify(paperMapper).deleteById(77L);
        verify(manageMapper).deleteOptionsByQuestionIds(List.of(501L, 502L));
        verify(manageMapper).deleteQuestionsByIds(List.of(501L, 502L));
        verify(batchMapper).deleteById(88L);
    }
}
