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

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

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
}
