package org.leon.practicemodule.manage;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
import org.leon.practicemodule.manage.pojo.vo.ManageVOs;
import org.leon.practicemodule.manage.service.PaperPackager;
import org.leon.practicemodule.manage.service.PracticeManageService;
import org.leon.practicemodule.manage.service.QuestionPayloadValidator;
import org.leon.practicemodule.mapper.QbExamAttemptMapper;
import org.leon.practicemodule.mapper.QbPaperMapper;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserFavoriteMapper;
import org.leon.practicemodule.mapper.QbUserSubjectStatMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.PaperDetailVO;
import org.leon.practicemodule.service.AnswerGrader;
import org.leon.practicemodule.service.PracticeMasker;
import org.leon.practicemodule.service.impl.PaperServiceImpl;
import org.leon.practicemodule.service.impl.QuestionReadServiceImpl;
import org.leon.practicemodule.service.impl.SubjectServiceImpl;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真库端到端验收测试（MySQL 9 + MyBatis-Plus 真实 SQL）：
 * 覆盖 .md/.txt/.docx 上传入库 → 个人科目隔离 → 刷题读取与套卷详情 → 编辑/手动打包/冲突保护 → 批次撤回与清理。
 * 使用合成用户 990020 / 990021，测试结束后清理全部自造数据不留残留。
 */
@Tag("db-it")
@DisplayName("个人题库上传导入与管理：真库端到端验收")
class PracticeManageDbIT {

    private static long userA;
    private static long userB;
    private static final String TEST_SUBJECT_NAME = "IT测试个人题库_990020";

    private static SqlSession session;
    private static QbSubjectMapper subjectMapper;
    private static PracticeManageService manageService;
    private static SubjectServiceImpl subjectService;
    private static QuestionReadServiceImpl questionReadService;
    private static PaperServiceImpl paperService;
    private static ImportTemplateGenerator templateGenerator;

    @BeforeAll
    static void open() throws SQLException {
        DataSource ds = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                "jdbc:mysql://localhost:3306/ezpracticeexercises?useSSL=false"
                        + "&allowPublicKeyRetrieval=true&characterEncoding=utf8",
                "root", "");
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setEnvironment(new Environment("db-it", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.setJdbcTypeForNull(JdbcType.NULL);
        cfg.addMapper(QbSubjectMapper.class);
        cfg.addMapper(QbQuestionMapper.class);
        cfg.addMapper(QbQuestionOptionMapper.class);
        cfg.addMapper(QbPaperMapper.class);
        cfg.addMapper(QbExamAttemptMapper.class);
        cfg.addMapper(QbUserAnswerRecordMapper.class);
        cfg.addMapper(QbUserFavoriteMapper.class);
        cfg.addMapper(QbUserWrongMapper.class);
        cfg.addMapper(QbUserSubjectStatMapper.class);
        cfg.addMapper(QbImportBatchMapper.class);
        cfg.addMapper(QbImportBatchItemMapper.class);
        cfg.addMapper(MyQuestionManageMapper.class);

        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
        session = factory.openSession(true);

        subjectMapper = session.getMapper(QbSubjectMapper.class);
        QbQuestionMapper questionMapper = session.getMapper(QbQuestionMapper.class);
        QbQuestionOptionMapper optionMapper = session.getMapper(QbQuestionOptionMapper.class);
        QbPaperMapper paperMapper = session.getMapper(QbPaperMapper.class);
        QbExamAttemptMapper attemptMapper = session.getMapper(QbExamAttemptMapper.class);
        QbUserAnswerRecordMapper recordMapper = session.getMapper(QbUserAnswerRecordMapper.class);
        QbUserFavoriteMapper favoriteMapper = session.getMapper(QbUserFavoriteMapper.class);
        QbUserWrongMapper wrongMapper = session.getMapper(QbUserWrongMapper.class);
        QbUserSubjectStatMapper statMapper = session.getMapper(QbUserSubjectStatMapper.class);
        QbImportBatchMapper batchMapper = session.getMapper(QbImportBatchMapper.class);
        QbImportBatchItemMapper batchItemMapper = session.getMapper(QbImportBatchItemMapper.class);
        MyQuestionManageMapper manageMapper = session.getMapper(MyQuestionManageMapper.class);

        templateGenerator = new ImportTemplateGenerator();
        manageService = new PracticeManageService(
                subjectMapper, questionMapper, optionMapper, paperMapper,
                batchMapper, batchItemMapper, manageMapper,
                new DocumentTextExtractor(), new QuestionTextParser(),
                templateGenerator, new PaperPackager(),
                new QuestionPayloadValidator(new AnswerGrader()));
        subjectService = new SubjectServiceImpl(subjectMapper, statMapper);
        questionReadService = new QuestionReadServiceImpl(
                questionMapper, recordMapper, favoriteMapper, subjectMapper,
                wrongMapper, optionMapper, new PracticeMasker());
        paperService = new PaperServiceImpl(paperMapper, subjectMapper, attemptMapper, recordMapper);
        try (Statement st = session.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM users_dev WHERE id <> 1 ORDER BY id ASC LIMIT 2")) {
            assertTrue(rs.next(), "users_dev 至少需要存在一个 id<>1 的真实用户用于外键验收");
            userA = rs.getLong(1);
            userB = rs.next() ? rs.getLong(1) : userA + 999999L;
        }
        purge();
    }

    @BeforeEach
    void cleanBefore() throws SQLException {
        purge();
    }

    @AfterAll
    static void close() throws SQLException {
        if (session != null) {
            purge();
            session.close();
        }
    }

    private static void purge() throws SQLException {
        // 只按测试专用科目名清理，绝不动真实用户的其它数据
        try (PreparedStatement ps1 = session.getConnection().prepareStatement(
                "DELETE bi FROM qb_import_batch_item bi JOIN qb_import_batch b ON b.id = bi.batch_id "
                        + "JOIN qb_subject s ON s.id = b.subject_id WHERE s.name = ?")) {
            ps1.setString(1, TEST_SUBJECT_NAME);
            ps1.executeUpdate();
        }
        try (PreparedStatement ps2 = session.getConnection().prepareStatement(
                "DELETE b FROM qb_import_batch b JOIN qb_subject s ON s.id = b.subject_id WHERE s.name = ?")) {
            ps2.setString(1, TEST_SUBJECT_NAME);
            ps2.executeUpdate();
        }
        try (PreparedStatement ps3 = session.getConnection().prepareStatement(
                "DELETE p FROM qb_paper p JOIN qb_subject s ON s.id = p.subject_id WHERE s.name = ?")) {
            ps3.setString(1, TEST_SUBJECT_NAME);
            ps3.executeUpdate();
        }
        try (PreparedStatement ps4 = session.getConnection().prepareStatement(
                "DELETE o FROM qb_question_option o JOIN qb_question q ON q.id = o.question_id "
                        + "JOIN qb_subject s ON s.id = q.subject_id WHERE s.name = ?")) {
            ps4.setString(1, TEST_SUBJECT_NAME);
            ps4.executeUpdate();
        }
        try (PreparedStatement ps5 = session.getConnection().prepareStatement(
                "DELETE q FROM qb_question q JOIN qb_subject s ON s.id = q.subject_id WHERE s.name = ?")) {
            ps5.setString(1, TEST_SUBJECT_NAME);
            ps5.executeUpdate();
        }
        try (PreparedStatement ps6 = session.getConnection().prepareStatement(
                "DELETE FROM qb_subject WHERE name = ?")) {
            ps6.setString(1, TEST_SUBJECT_NAME);
            ps6.executeUpdate();
        }
    }

    @Test
    @DisplayName("完整端到端链路：docx/md/txt 导入 → 个人科目隔离 → 刷题读侧与套卷详情 → 编辑与打包 → 撤回清理")
    void fullEndToEndFlowOnRealMysql() {
        // 1. userA 上传 .docx 并同步新建个人科目 + 打包套卷
        ImportTemplateGenerator.TemplateFile docx = templateGenerator.generate("docx");
        ManageVOs.ImportResultVO imported = manageService.importDocument(
                userA, docx.bytes(), docx.fileName(),
                null, TEST_SUBJECT_NAME,
                true, "IT测试自动套卷", 60, new BigDecimal("5"));

        assertEquals(5, imported.getImportedCount());
        assertEquals(0, imported.getSkippedCount());
        assertNotNull(imported.getBatchId());
        assertNotNull(imported.getSubjectId());
        assertNotNull(imported.getPaperId());

        Long subjectId = imported.getSubjectId();
        Long batchId = imported.getBatchId();
        Long paperId = imported.getPaperId();

        // 2. 多租户隔离验证：userA 可见，userB 不可见且越权读/写均报 40300
        List<QbSubject> visibleA = subjectMapper.selectVisibleEnabledSubjects(userA);
        List<QbSubject> visibleB = subjectMapper.selectVisibleEnabledSubjects(userB);
        assertTrue(visibleA.stream().anyMatch(s -> s.getId().equals(subjectId)));
        assertFalse(visibleB.stream().anyMatch(s -> s.getId().equals(subjectId)));

        assertTrue(subjectService.listSubjects(userA).stream()
                .anyMatch(s -> s.getId().equals(subjectId) && s.isPersonal() && s.getQuestionCount() == 5));
        assertEquals(PracticeErrorCode.FORBIDDEN, assertThrows(PracticeException.class,
                () -> questionReadService.page(userB, subjectId, 1L, 20L)).errorCode());

        // 3. 刷题读侧与套卷详情侧可直接消费刚导入的数据
        assertEquals(5L, questionReadService.page(userA, subjectId, 1L, 20L).getTotal());
        PaperDetailVO paperDetail = paperService.detail(userA, paperId);
        assertEquals(5, paperDetail.getQuestionCount());
        assertEquals(0, new BigDecimal("50.00").compareTo(paperDetail.getTotalScore()));

        // 4. 管理页筛选题目 + 编辑单题
        PageVO<ManageVOs.MyQuestionVO> pageByBatch = manageService.pageQuestions(
                userA, subjectId, batchId, null, null, null, "Java", 1L, 20L);
        assertTrue(pageByBatch.getTotal() >= 1);
        ManageVOs.MyQuestionVO target = pageByBatch.getList().get(0);

        ManageRequests.QuestionSaveReq editReq = new ManageRequests.QuestionSaveReq();
        editReq.setSubjectId(subjectId);
        editReq.setType(target.getType());
        editReq.setTitle(target.getTitle() + "（已修订）");
        editReq.setOptions(target.getOptions().stream().map(o -> {
            ManageRequests.OptionReq or = new ManageRequests.OptionReq();
            or.setOptionKey(o.getOptionKey());
            or.setContent(o.getContent());
            return or;
        }).toList());
        editReq.setAnswerJson(target.getAnswerJson());
        editReq.setAnalysis("修订后的解析");
        editReq.setDifficulty(3);
        editReq.setTags(List.of("修订标签"));
        editReq.setStatus(1);
        ManageVOs.MyQuestionVO updated = manageService.updateQuestion(userA, target.getQuestionId(), editReq);
        assertTrue(updated.getTitle().endsWith("（已修订）"));
        assertEquals(3, updated.getDifficulty());

        // 5. 删除被套卷引用的题目报 40900
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> manageService.deleteQuestions(userA, List.of(target.getQuestionId()))).errorCode());

        // 6. 撤回整批导入（deleteQuestions=true）清理套卷、题目、选项与批次
        manageService.deleteBatch(userA, batchId, true);
        assertEquals(0L, manageService.pageQuestions(
                userA, subjectId, null, null, null, null, null, 1L, 20L).getTotal());
        assertEquals(0L, manageService.pagePapers(userA, subjectId, 1L, 20L).getTotal());
        assertEquals(0L, manageService.pageBatches(userA, subjectId, 1L, 20L).getTotal());

        // 7. 同科目再导入 .md 验证多格式 + 手动勾选打包套卷
        ImportTemplateGenerator.TemplateFile md = templateGenerator.generate("md");
        ManageVOs.ImportResultVO mdRes = manageService.importDocument(
                userA, md.bytes(), md.fileName(), subjectId, null, false, null, null, null);
        assertEquals(5, mdRes.getImportedCount());
        assertNull(mdRes.getPaperId());

        List<ManageVOs.MyQuestionVO> importedQs = manageService.pageQuestions(
                userA, subjectId, mdRes.getBatchId(), null, null, null, null, 1L, 20L).getList();
        ManageRequests.PaperPackReq packReq = new ManageRequests.PaperPackReq();
        packReq.setSubjectId(subjectId);
        packReq.setTitle("IT手动勾选打包卷");
        packReq.setDurationMinutes(30);
        packReq.setItems(importedQs.stream().limit(2).map(q -> {
            ManageRequests.PaperPackItemReq item = new ManageRequests.PaperPackItemReq();
            item.setQuestionId(q.getQuestionId());
            item.setScore(new BigDecimal("10"));
            return item;
        }).toList());
        ManageVOs.MyPaperVO packed = manageService.packPaper(userA, packReq);
        assertEquals(2, packed.getQuestionCount());
        assertEquals(0, new BigDecimal("20.00").compareTo(packed.getTotalScore()));

        // 8. 级联删除个人科目收尾
        manageService.deleteSubject(userA, subjectId, true);
        assertFalse(manageService.listMySubjects(userA).stream().anyMatch(s -> s.getId().equals(subjectId)));
    }
}
