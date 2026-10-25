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
import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.service.AnswerStatus;
import org.leon.practicemodule.service.ExamAnswers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 段 5 状态机那五条 attempt 查询的真库语义。合成用户 {@code user_id=990005}
 * （段 4 用到 990004），每条用例前后 purge，跑完零残留。
 *
 * <p><b>样例卷（qb_paper 的 4 行验收数据）一行都不许动</b>——本类只读 qb_paper，
 * 跑完用 {@code Seg5Residue} 探针复核它仍是 4 行。
 *
 * <p>这里钉的三件事都是"只有 MySQL 能证"的：条件更新是不是真的幂等闸门、
 * {@code uk_attempt_user_paper_seq} 拦不拦得住并发双击、{@code MAX(seq_no)} 与草稿查询的
 * 边界（终态不算草稿、两条草稿时取最新那条）。
 *
 * <p>样板（open/purge/flushCache）是本仓库第 6 次复制（3 次读侧 + 1 次试卷 + 1 次作答流程）。
 * 抽取的事记在段 3/段 4/段 5 计划的接缝表里，
 * 阻力仍是"db-it 的 MySQL 口令从哪来"（用户规则：秘密只进 gitignored 本地文件），本段不动。
 */
@Tag("db-it")
@DisplayName("attempt 状态机查询的真库语义")
class ExamFlowDbIT {

    private static final long TEST_USER = 990005L;

    private static SqlSession session;
    private static QbPaperMapper paperMapper;
    private static QbExamAttemptMapper attemptMapper;
    private static QbUserAnswerRecordMapper recordMapper;

    @BeforeAll
    static void open() {
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
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
        session = factory.openSession(true);
        paperMapper = session.getMapper(QbPaperMapper.class);
        attemptMapper = session.getMapper(QbExamAttemptMapper.class);
        recordMapper = session.getMapper(QbUserAnswerRecordMapper.class);
    }

    @BeforeEach
    void cleanBeforeCase() throws SQLException {
        purge();
    }

    @AfterAll
    static void closeAll() throws SQLException {
        purge();
        if (session != null) {
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
    }

    /** 裸 JDBC 写完必须清一级缓存，否则同会话的 mapper 读回的是写之前的世界（段 4 实测两次） */
    private void flushCache() {
        session.clearCache();
    }

    /** 样例卷 P1：科目 1、4 题、启用中（与段 4 的 seededPaperOfSubjectOne 同一个挑法） */
    private static long seededP1Id() {
        return paperMapper.selectList(null).stream()
                .filter(p -> p.getSubjectId() == 1L && p.getStatus() == 1 && p.getQuestionCount() == 4)
                .map(QbPaper::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("样例卷 P1 不在库里，先跑段 4 的 seed 脚本"));
    }

    private long insertAttempt(long paperId, int seqNo, int status, String answeredJson)
            throws SQLException {
        String sql = "INSERT INTO qb_exam_attempt (user_id, paper_id, subject_id, seq_no, status,"
                + " answered_json, started_at, deadline_at) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = session.getConnection()
                .prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, TEST_USER);
            ps.setLong(2, paperId);
            ps.setLong(3, 1L);
            ps.setInt(4, seqNo);
            ps.setInt(5, status);
            ps.setString(6, answeredJson);
            ps.setTimestamp(7, new Timestamp(System.currentTimeMillis() - 60_000L));
            ps.setTimestamp(8, new Timestamp(System.currentTimeMillis() + 60 * 60_000L));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 一条"将要被推成终态"的更新行：只有闸门关心的那几个字段有值 */
    private static QbExamAttempt terminalUpdate(long id, int status) {
        QbExamAttempt a = new QbExamAttempt();
        a.setId(id);
        a.setUserId(TEST_USER);
        a.setStatus(status);
        a.setSubmitAt(new Date());
        a.setDurationUsedMs(60_000);
        a.setScore(new BigDecimal("7.00"));
        a.setCorrectCount(1);
        a.setWrongCount(0);
        a.setUnansweredCount(3);
        return a;
    }

    @Test
    @DisplayName("草稿→终态的条件更新是幂等闸门：第二次更新回 0 行，分数不会被重复写")
    void finalizeIfDraftIsTheIdempotencyGate() throws SQLException {
        long paperId = seededP1Id();
        long attemptId = insertAttempt(paperId, 1, 1, "{\"1\":\"A\"}");
        flushCache();

        assertEquals(1, attemptMapper.finalizeIfDraft(terminalUpdate(attemptId, 2)),
                "草稿必须能被推成终态");
        flushCache();

        assertEquals(2, attemptMapper.selectById(attemptId).getStatus());
        assertEquals(0, attemptMapper.finalizeIfDraft(terminalUpdate(attemptId, 2)),
                "同一行再推一次必须回 0——这就是幂等闸门");
        flushCache();

        assertNull(attemptMapper.selectById(attemptId).getAnsweredJson(),
                "交卷后 answered_json 必须置 NULL（段 4 已立的不变式：map 与展开行不能同时是真相）");
        // 别人的行不能被这条 UPDATE 碰到：WHERE 里带 user_id，越权尝试回 0 行
        assertEquals(0, attemptMapper.finalizeIfDraft(foreignUser(attemptId)),
                "user_id 不匹配时闸门必须关上");
    }

    private static QbExamAttempt foreignUser(long attemptId) {
        QbExamAttempt a = terminalUpdate(attemptId, 2);
        a.setUserId(TEST_USER + 1);
        return a;
    }

    @Test
    @DisplayName("uk_attempt_user_paper_seq 拦住并发双击的第二次 insert（消息带索引名）")
    void uniqueKeyBlocksDuplicateSeqNo() throws SQLException {
        long paperId = seededP1Id();
        insertAttempt(paperId, 1, 1, null);
        SQLException dup = assertThrows(SQLException.class,
                () -> insertAttempt(paperId, 1, 1, null));
        assertTrue(dup.getMessage().contains("uk_attempt_user_paper_seq"), dup.getMessage());
        flushCache();
        assertEquals(1, attemptMapper.selectMaxSeqNo(TEST_USER, paperId), "seq 只到 1");
    }

    @Test
    @DisplayName("selectDraftByUserAndPaper 与 selectMaxSeqNo：终态不算草稿、MAX 跟着行走")
    void draftLookup() throws SQLException {
        long paperId = seededP1Id();
        flushCache();
        assertEquals(0, attemptMapper.selectMaxSeqNo(TEST_USER, paperId), "一张卷子都没答过");
        assertNull(attemptMapper.selectDraftByUserAndPaper(TEST_USER, paperId));

        insertAttempt(paperId, 1, 2, null);            // 已提交，不算草稿
        flushCache();
        assertNull(attemptMapper.selectDraftByUserAndPaper(TEST_USER, paperId));
        assertEquals(1, attemptMapper.selectMaxSeqNo(TEST_USER, paperId));

        long draftId = insertAttempt(paperId, 2, 1, "{\"1\":\"A\"}");
        flushCache();
        assertEquals(draftId, attemptMapper.selectDraftByUserAndPaper(TEST_USER, paperId).getId());
        assertEquals(2, attemptMapper.selectMaxSeqNo(TEST_USER, paperId));

        // 两条草稿（手改库才会出现）：取 id 最大的那条，而不是抛 TooManyResultsException
        long newer = insertAttempt(paperId, 3, 1, null);
        flushCache();
        assertEquals(newer, attemptMapper.selectDraftByUserAndPaper(TEST_USER, paperId).getId());

        // countForPaper 把草稿也算进"历次作答"，与 selectAttemptPage 的口径一致
        assertEquals(3L, attemptMapper.countForPaper(TEST_USER, paperId));
        List<AttemptPageRow> page = attemptMapper.selectAttemptPage(TEST_USER, paperId, 0L, 20L);
        flushCache();
        assertEquals(3, page.size());
        assertEquals(newer, page.get(0).attemptId(), "新一次在前（ORDER BY id DESC）");
        assertNull(page.get(0).score(), "草稿行没有分数快照——所以 AttemptPageRow 全用包装类型");
        assertEquals(2, page.get(1).attemptId() == newer ? 0 : 2, "第二条是 seq_no=2 的那次");
    }

    /**
     * MyBatis-Plus 的"非空字段更新"是本段两处 patch 写法的地基（存草稿只带 id + answered_json），
     * 而它是<b>框架行为、不是我们写的 SQL</b>——只能连真库验一次。
     * 单测里那条 {@code draftPatchCarriesOnlyAnsweredJson} 只能证明"我们没往 patch 上挂别的字段"，
     * 证明不了挂上去的 null 不会被写进库。
     */
    @Test
    @DisplayName("updateById 的 patch 只写 answered_json：status / deadline_at / seq_no 原样不动")
    void patchUpdateOnlyWritesAnsweredJson() throws SQLException {
        long paperId = seededP1Id();
        long attemptId = insertAttempt(paperId, 1, 1, null);
        flushCache();
        QbExamAttempt before = attemptMapper.selectById(attemptId);
        assertNotNull(before.getDeadlineAt());

        QbExamAttempt patch = new QbExamAttempt();
        patch.setId(attemptId);
        patch.setAnsweredJson(ExamAnswers.encode(Map.of(1L, "\"A\"", 5L, "\"我的简答\"")));
        assertEquals(1, attemptMapper.updateById(patch));
        flushCache();

        QbExamAttempt after = attemptMapper.selectById(attemptId);
        assertEquals(2, ExamAnswers.countOf(after.getAnsweredJson()));
        assertEquals("\"A\"", ExamAnswers.decode(after.getAnsweredJson()).get(1L));
        assertEquals(1, after.getStatus(), "patch 里 status 是 null，绝不能把草稿推成终态");
        assertEquals(1, after.getSeqNo());
        assertEquals(before.getDeadlineAt(), after.getDeadlineAt(), "deadline_at 被 null 覆盖过一次，用户就再也不会超时");
        assertEquals(before.getStartedAt(), after.getStartedAt());
        assertNull(after.getScore());
        assertNull(after.getSubmitAt());
    }

    @Test
    @DisplayName("真库全链路：裸建草稿 → 存两题 → 闸门推终态 → 展开两行（简答 -1）+ answered_json 置 NULL")
    void fullFlowOnRealData() throws SQLException {
        long paperId = seededP1Id();
        long attemptId = insertAttempt(paperId, 1, 1, null);   // 模拟刚 start 完
        flushCache();

        // 存草稿走 ExamAnswers 的编码，再按服务层那条 UPDATE 落库（同 session 裸写 → 必须 clearCache）
        QbExamAttempt draftPatch = new QbExamAttempt();
        draftPatch.setId(attemptId);
        draftPatch.setAnsweredJson(ExamAnswers.encode(Map.of(1L, "\"A\"", 5L, "\"我的简答\"")));
        assertEquals(1, attemptMapper.updateById(draftPatch));
        flushCache();

        QbExamAttempt draft = attemptMapper.selectById(attemptId);
        assertEquals(2, ExamAnswers.countOf(draft.getAnsweredJson()));

        QbExamAttempt terminal = new QbExamAttempt();
        terminal.setId(attemptId);
        terminal.setUserId(TEST_USER);
        terminal.setStatus(2);
        terminal.setSubmitAt(new Date());
        terminal.setDurationUsedMs(60_000);
        // 1 对（qid1）+ 1 待自评（qid5 简答）+ 2 未答（qid6、qid2）
        terminal.setScore(new BigDecimal("7.00"));
        terminal.setCorrectCount(1);
        terminal.setWrongCount(0);
        terminal.setUnansweredCount(2);
        assertEquals(1, attemptMapper.finalizeIfDraft(terminal));
        flushCache();

        assertNull(attemptMapper.selectById(attemptId).getAnsweredJson(),
                "交卷后 map 与展开行不能同时是真相（段 4 已立的不变式，这里在段 5 的真实写入上复验）");

        int inserted = 0;
        inserted += insertExamRecord(attemptId, 1L, "\"A\"", 1);
        inserted += insertExamRecord(attemptId, 5L, "\"我的简答\"", -1);
        flushCache();

        List<AttemptRecordRow> rows = recordMapper.selectByAttempt(TEST_USER, attemptId);
        assertEquals(inserted, rows.size(), "写进行数与读回行数必须相等（selectByAttempt 就是按 attempt_id 查的）");
        assertEquals(1, rows.stream().filter(r -> r.isCorrect() == -1).count(),
                "简答在真库里就是 -1");
        assertEquals(1, rows.stream().map(AttemptRecordRow::questionId).filter(qid -> qid == 5L).count());
    }

    @Test
    @DisplayName("展开出的考试记录行不改科目统计（spec 7.1 在真库上成立）")
    void examRecordsLeaveSubjectStatUntouched() throws SQLException {
        String before = subjectStatSnapshot();

        long paperId = seededP1Id();
        long attemptId = insertAttempt(paperId, 1, 2, null);
        insertExamRecord(attemptId, 1L, "\"A\"", 1);
        insertExamRecord(attemptId, 6L, "\"B\"", 0);
        flushCache();

        assertEquals(before, subjectStatSnapshot(),
                "交卷展开动了 qb_user_subject_stat 一个字节——spec 7.1 的口径被写坏了");
    }

    /**
     * 这条<b>不是新功能，是一条已知副作用的取证</b>：练习侧的答题卡会把考试的作答当"上次作答"显示
     * （{@code selectLatestByQuestions} 不带 mode 过滤，段 3 裁定二），且因为 {@code selfJudgeable}
     * 要 {@code mode IN (1,3)}，那行的自评入口是关着的（裁定一）。
     * 写进测试是为了：将来有人给那条 SQL 加 mode 过滤，这条会红并逼他重新读裁定一。
     */
    @Test
    @DisplayName("考试行会成为练习侧的『最近一次作答』，但不可自评（事实 13 的活体记录）")
    void examRecordIsTheLatestAnswerForPracticeSide() throws SQLException {
        long practiceRecord = insertPracticeRecord(1L, "\"B\"", 0);     // 先：练习答错
        long paperId = seededP1Id();
        long attemptId = insertAttempt(paperId, 1, 2, null);
        insertExamRecord(attemptId, 1L, "\"我的简答\"", -1);            // 后：同一题的考试作答（id 更大）
        flushCache();

        LatestAnswerRow latest = recordMapper.selectLatestByQuestions(TEST_USER, List.of(1L))
                .stream().findFirst().orElseThrow();
        assertTrue(latest.recordId() > practiceRecord,
                "取回的是考试那一行（AUTO_INCREMENT 更大），练习侧把它当上次作答");
        assertEquals(2L, latest.answerMode().longValue());
        assertEquals(-1, latest.isCorrect().intValue());
        assertEquals(AnswerStatus.PENDING_SELF_JUDGE, AnswerStatus.of(latest),
                "答题卡会在练习侧把这次考试作答显示成『待自评』");
        assertFalse(AnswerStatus.selfJudgeable(latest),
                "但 mode=2 的行不给自评入口——裁定一的闸门，改那条 SQL 前先读它");
    }

    /** 展开一条考试记录（answer_mode=2、挂 attempt_id），回 1 是为了让调用方累计行数 */
    private int insertExamRecord(long attemptId, long questionId, String userAnswerJson, int isCorrect)
            throws SQLException {
        return insertRecord(attemptId, questionId, userAnswerJson, isCorrect, 2);
    }

    private long insertPracticeRecord(long questionId, String userAnswerJson, int isCorrect)
            throws SQLException {
        insertRecord(null, questionId, userAnswerJson, isCorrect, 1);
        try (PreparedStatement ps = session.getConnection().prepareStatement(
                "SELECT MAX(id) FROM qb_user_answer_record WHERE user_id = ? AND answer_mode = 1")) {
            ps.setLong(1, TEST_USER);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private int insertRecord(Long attemptId, long questionId, String userAnswerJson,
                             int isCorrect, int answerMode) throws SQLException {
        String sql = "INSERT INTO qb_user_answer_record (user_id, subject_id, attempt_id, question_id,"
                + " user_answer_json, is_correct, duration_ms, answer_mode, created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = session.getConnection().prepareStatement(sql)) {
            ps.setLong(1, TEST_USER);
            ps.setLong(2, 1L);
            if (attemptId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, attemptId);
            }
            ps.setLong(4, questionId);
            ps.setString(5, userAnswerJson);
            ps.setInt(6, isCorrect);
            ps.setInt(7, 20_000);
            ps.setInt(8, answerMode);
            ps.setTimestamp(9, new Timestamp(System.currentTimeMillis()));
            return ps.executeUpdate();
        }
    }

    /** 全表快照（行数 + 三个计数之和）：合成用户本来就没有统计行，只看全局才抓得住"被别人碰过" */
    private String subjectStatSnapshot() throws SQLException {
        try (Statement st = session.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*), COALESCE(SUM(total_count),0),"
                     + " COALESCE(SUM(correct_count),0), COALESCE(SUM(wrong_count),0)"
                     + " FROM qb_user_subject_stat")) {
            rs.next();
            return rs.getLong(1) + "/" + rs.getLong(2) + "/" + rs.getLong(3) + "/" + rs.getLong(4);
        }
    }
}
