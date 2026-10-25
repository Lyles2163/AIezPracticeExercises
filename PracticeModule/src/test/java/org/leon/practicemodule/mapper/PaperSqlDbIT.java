package org.leon.practicemodule.mapper;

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
import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.vo.PaperDetailVO;
import org.leon.practicemodule.service.PaperStructure;
import org.leon.practicemodule.service.PaperStructureParser;
import org.leon.practicemodule.service.PaperStructureValidator;
import org.leon.practicemodule.service.impl.PaperServiceImpl;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 试卷表与作答表的真库语义（段 4）。
 *
 * <p>这些都是"只有 MySQL 能证"的事：BaseMapper 整体读写 qb_paper 时列名不炸、
 * json 列的写入合法性与回读形态、decimal(6,2) 与 BigDecimal 的 scale 关系、
 * STRICT_TRANS_TABLES 下 NOT NULL 无默认值列会被 1364 拒绝、
 * 以及 uk_attempt_user_paper_seq 真的拦得住并发双击。
 *
 * <p>合成用户 user_id=990004（高于段 2 的 990001 与段 3 的 990002/990003），
 * 每个用例前 purge、@AfterAll 再清一次，跑完库里不留痕。
 * **样例卷（qb_paper 的 4 行验收数据）不属于残留，测试一行都不许动它**——
 * 所以本类自造的任何 qb_paper 行都自己删干净。
 *
 * <p>样板（open/purge）已是第四次重复。抽取的事记在段 3 与本段计划的接缝表里，
 * 阻力是"db-it 的 MySQL 口令从哪来"，本段仍不动。
 */
@Tag("db-it")
@DisplayName("试卷表与作答表的真库语义")
class PaperSqlDbIT {

    private static final long TEST_USER = 990004L;
    /** 自造行都用这个 sort_order 打标，便于探针按 ASCII 条件查残留（题干中文在控制台会乱码） */
    private static final int MARKER_SORT = 998;

    private static SqlSession session;
    private static QbPaperMapper paperMapper;
    private static QbExamAttemptMapper attemptMapper;
    private static QbUserAnswerRecordMapper recordMapper;
    private static QbQuestionMapper questionMapper;
    private static QbSubjectMapper subjectMapper;

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
        cfg.addMapper(QbPaperMapper.class);
        cfg.addMapper(QbExamAttemptMapper.class);
        cfg.addMapper(QbUserAnswerRecordMapper.class);
        cfg.addMapper(QbQuestionMapper.class);
        cfg.addMapper(QbSubjectMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
        session = factory.openSession(true);
        paperMapper = session.getMapper(QbPaperMapper.class);
        attemptMapper = session.getMapper(QbExamAttemptMapper.class);
        recordMapper = session.getMapper(QbUserAnswerRecordMapper.class);
        questionMapper = session.getMapper(QbQuestionMapper.class);
        subjectMapper = session.getMapper(QbSubjectMapper.class);
        purge();
    }

    @BeforeEach
    void cleanBetweenCases() throws SQLException {
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
        for (String table : new String[]{"qb_exam_attempt", "qb_user_answer_record"}) {
            try (PreparedStatement ps = session.getConnection()
                    .prepareStatement("DELETE FROM " + table + " WHERE user_id = ?")) {
                ps.setLong(1, TEST_USER);
                ps.executeUpdate();
            }
        }
        // 本类自造的试卷行（按标记列清，绝不动样例卷）
        try (PreparedStatement ps = session.getConnection()
                .prepareStatement("DELETE FROM qb_paper WHERE sort_order = ?")) {
            ps.setInt(1, MARKER_SORT);
            ps.executeUpdate();
        }
    }

    /** 现造一张最小合法试卷，返回 id。用例结束时由 purge() 或调用方自己清掉。 */
    private static long newTempPaper() {
        QbPaper paper = new QbPaper();
        paper.setSubjectId(1L);
        paper.setOwnerId(1L);
        paper.setTitle("临时试卷");
        paper.setDurationMinutes(30);
        paper.setTotalScore(new BigDecimal("7.00"));
        paper.setQuestionCount(1);
        paper.setStructureJson("{\"parts\":[]}");
        paper.setStatus(1);
        paper.setSortOrder(MARKER_SORT);
        paperMapper.insert(paper);
        return paper.getId();
    }

    @Test
    @DisplayName("qb_paper 能被 BaseMapper 整体读写：json 列回读的是 MySQL 规范化后的形态")
    void paperEntityRoundTrip() throws SQLException {
        QbPaper paper = new QbPaper();
        paper.setSubjectId(1L);
        paper.setOwnerId(1L);
        paper.setTitle("实体读写冒烟卷");
        paper.setExamYear(2020);
        paper.setExamMonth(7);
        paper.setSetNo(1);
        paper.setDurationMinutes(30);
        paper.setTotalScore(new BigDecimal("7.00"));
        paper.setQuestionCount(1);
        paper.setStructureJson("{\"parts\":[]}");
        paper.setStatus(1);
        paper.setSortOrder(MARKER_SORT);
        assertNull(paper.getId(), "插入前 id 必须是 null，AUTO 主键由库分配");
        paperMapper.insert(paper);
        assertNotNull(paper.getId(), "AUTO 主键要回填到实体");

        QbPaper back = paperMapper.selectById(paper.getId());
        // 不断言"原串照抄回来"：json 列按二进制存、回读会规范化（冒号后补空格），
        // 字符串等值断言在真库必红。判据是"列自己认它合法" + 非 JSON 字段逐一对齐。
        try (Statement st = session.getConnection().createStatement(); ResultSet rs = st.executeQuery(
                "SELECT JSON_VALID(structure_json) FROM qb_paper WHERE id = " + back.getId())) {
            rs.next();
            assertEquals(1, rs.getInt(1), "写进 json 列的串必须被 MySQL 认合法，实际回读=" + back.getStructureJson());
        }
        assertEquals("实体读写冒烟卷", back.getTitle());
        assertEquals(2020, back.getExamYear());
        assertEquals(7, back.getExamMonth());
        assertEquals(1, back.getSetNo());
        assertEquals(998, back.getSortOrder());
        assertEquals(1, back.getQuestionCount());
        assertEquals(0, new BigDecimal("7.00").compareTo(back.getTotalScore()),
                "decimal(6,2) 与 BigDecimal 必须 compareTo，不能 equals（scale 不同即不等）");
        assertNotNull(back.getCreatedAt(), "created_at 由 CURRENT_TIMESTAMP 默认值给，实体不写");
        assertNotNull(back.getUpdatedAt());

        paperMapper.deleteById(paper.getId());
        assertNull(paperMapper.selectById(paper.getId()), "deleteById 之后必须查不到");
    }

    @Test
    @DisplayName("attempt 的两个真库约束：漏 started_at 被 1364 拒、重复 (user,paper,seq) 被唯一键拒")
    void attemptColumnsAndUniqueKeyAreEnforced() throws SQLException {
        long paperId = newTempPaper();

        String missingStartedAt = "INSERT INTO qb_exam_attempt"
                + " (user_id, paper_id, subject_id, seq_no, status, deadline_at)"
                + " VALUES (" + TEST_USER + ", " + paperId + ", 1, 1, 1, '2026-12-31 10:00:00')";
        SQLException strict = assertThrows(SQLException.class, () -> {
            try (Statement st = session.getConnection().createStatement()) {
                st.executeUpdate(missingStartedAt);
            }
        }, "sql_mode=STRICT_TRANS_TABLES 下漏 started_at 必须被拒，否则计划的事实 7 站不住");
        assertTrue(strict.getMessage().contains("started_at"),
                "报错必须点出缺的是哪一列，实际：" + strict.getMessage());
        assertEquals(1364, strict.getErrorCode(), "缺值错误的 MySQL 错误码是 1364");

        String ok = "INSERT INTO qb_exam_attempt (user_id, paper_id, subject_id, seq_no, status,"
                + " started_at, deadline_at) VALUES (" + TEST_USER + ", " + paperId
                + ", 1, 1, 1, '2026-10-24 09:00:00', '2026-10-24 10:00:00')";
        try (Statement st = session.getConnection().createStatement()) {
            assertEquals(1, st.executeUpdate(ok), "补齐 NOT NULL 列后必须写得进");
        }

        SQLException dup = assertThrows(SQLException.class, () -> {
            try (Statement st = session.getConnection().createStatement()) {
                st.executeUpdate(ok);
            }
        }, "uk_attempt_user_paper_seq 必须拦住同 (user_id, paper_id, seq_no) 的第二行");
        assertTrue(dup.getMessage().contains("uk_attempt_user_paper_seq"),
                "必须是被那条唯一键拦住，实际：" + dup.getMessage());

        try (Statement st = session.getConnection().createStatement(); ResultSet rs = st.executeQuery(
                "SELECT COUNT(*) FROM qb_exam_attempt WHERE user_id = " + TEST_USER)) {
            rs.next();
            assertEquals(1, rs.getInt(1), "两次插入只活下来一行");
        }
    }

    @Test
    @DisplayName("attempt 的 answered_json 可空、交卷后置 NULL 的形态在真库上成立")
    void draftJsonCanBeCleared() throws SQLException {
        long paperId = newTempPaper();
        long attemptId;
        try (PreparedStatement ps = session.getConnection().prepareStatement(
                "INSERT INTO qb_exam_attempt (user_id, paper_id, subject_id, seq_no, status,"
                        + " answered_json, started_at, deadline_at) VALUES (?,?,?,?,?,?,? ,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, TEST_USER);
            ps.setLong(2, paperId);
            ps.setLong(3, 1L);
            ps.setInt(4, 1);
            ps.setInt(5, 1);
            ps.setString(6, "{\"1\":\"A\"}");
            ps.setString(7, "2026-10-24 09:00:00");
            ps.setString(8, "2026-10-24 10:00:00");
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                attemptId = rs.getLong(1);
            }
        }

        QbExamAttempt draft = attemptMapper.selectById(attemptId);
        assertEquals(1, draft.getStatus());
        assertNotNull(draft.getAnsweredJson(), "草稿期 answered_json 必须有内容");
        assertNull(draft.getSubmitAt());

        try (PreparedStatement ps = session.getConnection().prepareStatement(
                "UPDATE qb_exam_attempt SET status = 2, answered_json = NULL, submit_at = NOW()"
                        + " WHERE id = ? AND user_id = ?")) {
            ps.setLong(1, attemptId);
            ps.setLong(2, TEST_USER);
            assertEquals(1, ps.executeUpdate());
        }

        // 裸 JDBC 的写入不会清 MyBatis 的一级缓存（只有走 MyBatis 的 update/insert 才触发
        // Executor.clearLocalCache），所以下面这次 selectById 必须显式 clearCache，
        // 否则读到的是本会话里上一次 selectById 缓存的草稿行——实测过：status 仍是 1。
        session.clearCache();

        QbExamAttempt done = attemptMapper.selectById(attemptId);
        assertEquals(2, done.getStatus());
        assertNull(done.getAnsweredJson(),
                "交卷后 map 必须置 NULL——map 与展开出的行不能同时是真相（spec 4.2 第三条要点）");
        assertNotNull(done.getSubmitAt());
    }

    @Test
    @DisplayName("4 套样例卷都在库里，且每份的 structure_json 都被 MySQL 认合法")
    void seededPapersArePresent() throws SQLException {
        try (Statement st = session.getConnection().createStatement(); ResultSet rs = st.executeQuery(
                "SELECT COUNT(*) c, SUM(JSON_VALID(structure_json)) v, SUM(status=1) enabled FROM qb_paper")) {
            rs.next();
            assertEquals(4L, rs.getLong("c"),
                    "样例卷应当是 4 套（Task 4.4 的 seed），实际 " + rs.getLong("c")
                            + "——若少于 4，先重跑仓库外 seg4/Seg4Seed.java");
            assertEquals(4L, rs.getLong("v"), "库里每套卷的 structure_json 都必须 JSON_VALID");
            assertEquals(3L, rs.getLong("enabled"), "4 套里 3 启用 1 禁用");
        }
    }

    @Test
    @DisplayName("每套样例卷都过四条校验（裁定四：写入时校验在段 4 的兑现形式）")
    void everySeededPaperPassesValidation() {
        List<QbPaper> papers = paperMapper.selectList(null);
        assertEquals(4, papers.size(), "本用例只读全表，靠的就是库里恰好是那 4 套样例卷");
        for (QbPaper paper : papers) {
            PaperStructure structure = PaperStructureParser.parse(paper.getStructureJson());
            List<Long> ids = structure.questionsInOrder().stream()
                    .map(PaperStructure.QuestionRef::questionId).distinct().toList();
            Map<Long, PaperStructureValidator.QuestionBrief> briefs = new HashMap<>();
            for (QbQuestion q : questionMapper.selectBatchIds(ids)) {
                briefs.put(q.getId(), new PaperStructureValidator.QuestionBrief(
                        q.getId(), q.getSubjectId(), q.getStatus()));
            }
            // 下架卷（P4）的结构也必须合法，所以这里不加 status 过滤
            PaperStructureValidator.validate(structure, paper.getQuestionCount(),
                    paper.getTotalScore(), paper.getDurationMinutes(), paper.getSubjectId(), briefs);
        }
    }

    @Test
    @DisplayName("跨科引用当场被拒：把科目 2 卷里的首题换成科目 1 的 qid 1")
    void crossSubjectReferenceIsRejected() {
        QbPaper math = paperMapper.selectList(null).stream()
                .filter(p -> p.getSubjectId() == 2L).findFirst().orElseThrow();
        // 注意 \s*：json 列回读的是 MySQL 规范化后的串（冒号后带空格），
        // 用 "questionId":[0-9]+ 这种紧贴写法匹配不到——本用例第一版就是这么静默没改成功、
        // 于是"期望抛异常"落空的。凡是对 json 列做字符串改写都必须按规范化形态匹配。
        String tampered = math.getStructureJson()
                .replaceFirst("\"questionId\"\\s*:\\s*[0-9]+", "\"questionId\":1");
        assertTrue(tampered.contains("\"questionId\":1"),
                "改写必须真的生效，否则这条用例是在断言一份没被动过的合法结构：" + tampered);
        PaperStructure structure = PaperStructureParser.parse(tampered);
        Map<Long, PaperStructureValidator.QuestionBrief> briefs = new HashMap<>();
        for (QbQuestion q : questionMapper.selectBatchIds(structure.questionsInOrder().stream()
                .map(PaperStructure.QuestionRef::questionId).distinct().toList())) {
            briefs.put(q.getId(), new PaperStructureValidator.QuestionBrief(
                    q.getId(), q.getSubjectId(), q.getStatus()));
        }
        PracticeException e = assertThrows(PracticeException.class, () -> PaperStructureValidator.validate(
                structure, math.getQuestionCount(), math.getTotalScore(),
                math.getDurationMinutes(), math.getSubjectId(), briefs));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        assertTrue(e.getMessage().contains("科目"), "必须报跨科而不是别的，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("sort_order 并列时翻页不重不漏：(sort_order,id) 是全序")
    void pagingIsStableUnderDuplicateSortOrder() {
        // 科目 1 的启用卷本来是 P1(sort_order=1) 与 P2(sort_order=2) 两套；
        // 再造三套 sort_order 全等于 999 的，凑成 5 行、并列 3 行，分两页每页 3 条。
        List<Long> created = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                QbPaper p = new QbPaper();
                p.setSubjectId(1L);
                p.setOwnerId(1L);
                p.setTitle("并列排序卷");
                p.setDurationMinutes(10);
                p.setTotalScore(new BigDecimal("7.00"));
                p.setQuestionCount(1);
                p.setStructureJson("{\"parts\":[]}");
                p.setStatus(1);
                p.setSortOrder(999);
                paperMapper.insert(p);
                created.add(p.getId());
            }
            List<Long> first = paperMapper.selectPage(1L, null, null, null, 10L, 0L, 3L)
                    .stream().map(PaperCardRow::paperId).toList();
            List<Long> second = paperMapper.selectPage(1L, null, null, null, 10L, 3L, 3L)
                    .stream().map(PaperCardRow::paperId).toList();

            assertEquals(3, first.size());
            assertEquals(2, second.size(), "5 行分两页，第二页只剩 2 行");
            assertTrue(Collections.disjoint(first, second),
                    "并列 sort_order 下两页不许有重复：first=" + first + " second=" + second);
            Set<Long> union = new HashSet<>(first);
            union.addAll(second);
            assertEquals(5, union.size(), "两页合起来必须是 5 行，少一行就是翻页漏行");
            assertTrue(union.containsAll(created), "自造的三张卷必须都出现在某页里：" + union);
        } finally {
            for (Long id : created) {
                paperMapper.deleteById(id);
            }
        }
    }

    @Test
    @DisplayName("owner 与 status 两个过滤都在 SQL 里：禁用卷与他人的私有卷都不进列表")
    void listHidesDisabledAndForeignPapers() {
        long privateId = -1L;
        try {
            QbPaper p4 = paperMapper.selectList(null).stream()
                    .filter(p -> p.getStatus() == 0).findFirst().orElseThrow();
            List<Long> visible = paperMapper.selectPage(1L, null, null, null, 10L, 0L, 20L)
                    .stream().map(PaperCardRow::paperId).toList();
            assertFalse(visible.contains(p4.getId()), "禁用卷必须不出现在列表里");
            assertEquals(2L, paperMapper.countForList(1L, null, null, null, 10L),
                    "count 与 page 的过滤口径必须一致：科目 1 只剩 P1、P2 两套可见");

            QbPaper foreign = new QbPaper();
            foreign.setSubjectId(1L);
            foreign.setOwnerId(999004L);
            foreign.setTitle("他人私有卷");
            foreign.setDurationMinutes(10);
            foreign.setTotalScore(new BigDecimal("7.00"));
            foreign.setQuestionCount(1);
            foreign.setStructureJson("{\"parts\":[]}");
            foreign.setStatus(1);
            foreign.setSortOrder(MARKER_SORT);
            paperMapper.insert(foreign);
            privateId = foreign.getId();

            assertFalse(paperMapper.selectPage(1L, null, null, null, 10L, 0L, 20L)
                            .stream().map(PaperCardRow::paperId).toList().contains(privateId),
                    "owner_id 既不是 1 也不是当前用户的卷不能进列表");
            assertTrue(paperMapper.selectPage(1L, null, null, null, 999004L, 0L, 20L)
                            .stream().map(PaperCardRow::paperId).toList().contains(privateId),
                    "本人私有卷对自己可见");
        } finally {
            if (privateId > 0) {
                paperMapper.deleteById(privateId);
            }
        }
    }

    /**
     * Task 4.4 灌进来的样例卷 P1（科目 1、启用、4 道题、part I 的 Section A 带 originalText）。
     * 按内容取而不写死 id：AUTO 值随库里已有数据漂移，写死 id 的用例会在第二次 seed 后假失败。
     */
    private static QbPaper seededPaperOfSubjectOne() {
        return paperMapper.selectList(null).stream()
                .filter(p -> p.getSubjectId() == 1L && p.getStatus() == 1 && p.getQuestionCount() == 4)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Task 4.4 的样例卷 P1 不在库里，先跑 seg4/Seg4Seed"));
    }

    private Long insertAttempt(long paperId, int seqNo, int status, String answeredJson) throws SQLException {
        String sql = "INSERT INTO qb_exam_attempt (user_id, paper_id, subject_id, seq_no, status,"
                + " answered_json, started_at, deadline_at) VALUES (?,?,?,?,?,?, '2026-10-24 09:00:00',"
                + " '2026-12-31 09:00:00')";
        try (PreparedStatement ps = session.getConnection()
                .prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, TEST_USER);
            ps.setLong(2, paperId);
            ps.setLong(3, 1L);
            ps.setInt(4, seqNo);
            ps.setInt(5, status);
            ps.setString(6, answeredJson);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertRecord(long attemptId, long questionId) throws SQLException {
        try (PreparedStatement ps = session.getConnection().prepareStatement(
                "INSERT INTO qb_user_answer_record (user_id, subject_id, attempt_id, question_id,"
                        + " user_answer_json, is_correct, answer_mode) VALUES (?,?,?,?,?,?,2)")) {
            ps.setLong(1, TEST_USER);
            ps.setLong(2, 1L);
            ps.setLong(3, attemptId);
            ps.setLong(4, questionId);
            ps.setString(5, "\"A\"");
            ps.setInt(6, 1);
            ps.executeUpdate();
        }
    }

    /**
     * 裸 JDBC 写完必须清一级缓存：同一会话里键相同的 select 会直接回缓存，
     * 于是真服务读到的是"写之前"的世界（draftJsonCanBeCleared 实测过一次，status 仍是 1）。
     * 本类的用例都用真服务重复打同一批 mapper 查询，所以每条用例每次写入后都要调它。
     */
    private void flushCache() {
        session.clearCache();
    }

    @Test
    @DisplayName("裁定二在真库上成立：无 attempt → 原文 null；草稿 → null；交卷 → 非 null")
    void originalTextGateOnRealData() throws SQLException {
        QbPaper paper = seededPaperOfSubjectOne();
        PaperServiceImpl service =
                new PaperServiceImpl(paperMapper, subjectMapper, attemptMapper, recordMapper);

        PaperDetailVO asNew = service.detail(TEST_USER, paper.getId());
        assertNull(asNew.getParts().get(0).getSections().get(0).getOriginalText(), "没作答就该看不见原文");
        assertEquals(0L, asNew.getSubmitCount());
        assertNull(asNew.getLatestAttemptId());

        insertAttempt(paper.getId(), 1, 1, null);
        flushCache();
        PaperDetailVO asDraft = service.detail(TEST_USER, paper.getId());
        assertNull(asDraft.getParts().get(0).getSections().get(0).getOriginalText(), "草稿态也必须看不见");
        assertEquals(0L, asDraft.getSubmitCount(), "草稿不占已提交次数（裁定三）");
        assertEquals(0, asDraft.getAnsweredCount());
        assertNotNull(asDraft.getLatestAttemptId(), "草稿也算最新一次——继续考试的入口要它");

        insertAttempt(paper.getId(), 2, 2, null);
        flushCache();
        PaperDetailVO asDone = service.detail(TEST_USER, paper.getId());
        assertNotNull(asDone.getParts().get(0).getSections().get(0).getOriginalText(), "交卷后原文必须可见");
        assertEquals(1L, asDone.getSubmitCount());
        assertEquals(1L, asDone.getLatestAttemptId() - asDraft.getLatestAttemptId(),
                "latest 跟着 MAX(id) 走：草稿在前、终态在后，id 差 1");
    }

    @Test
    @DisplayName("answeredCount 的两条分支在真库上各自成立：草稿数 key，终态数行")
    void answeredCountBranches() throws SQLException {
        QbPaper paper = seededPaperOfSubjectOne();
        PaperServiceImpl service =
                new PaperServiceImpl(paperMapper, subjectMapper, attemptMapper, recordMapper);

        insertAttempt(paper.getId(), 1, 1, "{\"1\":\"A\",\"6\":\"B\"}");
        flushCache();
        assertEquals(2, service.detail(TEST_USER, paper.getId()).getAnsweredCount(),
                "草稿的已答数是 answered_json 的 key 数");

        Long doneId = insertAttempt(paper.getId(), 2, 2, null);
        for (long qid : List.of(1L, 6L, 2L)) {
            insertRecord(doneId, qid);
        }
        flushCache();
        PaperDetailVO vo = service.detail(TEST_USER, paper.getId());
        assertEquals(3, vo.getAnsweredCount(), "终态的已答数走 qb_user_answer_record 的行数");
        assertEquals(4, vo.getQuestionCount());
        assertEquals(75.0, vo.getProgressPercent(), 1e-9, "3/4 = 75.00");
    }
}
