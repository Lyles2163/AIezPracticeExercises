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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.pojo.QbUserAnswerRecord;
import org.leon.practicemodule.pojo.QbUserSubjectStat;
import org.leon.practicemodule.pojo.QbUserWrong;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 作答落库 SQL 的真库语义：ON DUPLICATE KEY UPDATE 的增量、掌握状态回退、
 * -1 哨兵在 STRICT 模式下可写、条件更新恰好生效一次。
 * 这些行为的正确性由 MySQL 保证，只有真库能证。
 *
 * 写入合成用户 user_id=990001（远高于真实 id），@BeforeAll/@AfterAll 各清一次，
 * 跑完库里不留痕。若中断留下残行，重新跑一次本类即可清掉。
 */
@Tag("db-it")
@DisplayName("作答落库 SQL 的真库语义")
class AnswerFlowDbIT {

    private static final long TEST_USER = 990001L;

    private static SqlSession session;
    private static QbUserWrongMapper wrongMapper;
    private static QbUserSubjectStatMapper statMapper;
    private static QbUserAnswerRecordMapper recordMapper;

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
        cfg.addMapper(QbUserWrongMapper.class);
        cfg.addMapper(QbUserSubjectStatMapper.class);
        cfg.addMapper(QbUserAnswerRecordMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
        session = factory.openSession(true);
        wrongMapper = session.getMapper(QbUserWrongMapper.class);
        statMapper = session.getMapper(QbUserSubjectStatMapper.class);
        recordMapper = session.getMapper(QbUserAnswerRecordMapper.class);
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
        for (String table : List.of("qb_user_wrong", "qb_user_answer_record", "qb_user_subject_stat")) {
            try (PreparedStatement ps = session.getConnection()
                    .prepareStatement("DELETE FROM " + table + " WHERE user_id = ?")) {
                ps.setLong(1, TEST_USER);
                ps.executeUpdate();
            }
        }
    }

    private static long countRows(String table) throws SQLException {
        try (PreparedStatement ps = session.getConnection()
                .prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?")) {
            ps.setLong(1, TEST_USER);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    @DisplayName("答错累加 -> 答对置已掌握 -> 已掌握再答错回退（spec 7.3 与验收清单第 2 条）")
    void wrongBookLifecycle() throws SQLException {
        Long questionId = 1L;
        wrongMapper.upsertWrong(TEST_USER, 1L, questionId);
        wrongMapper.upsertWrong(TEST_USER, 1L, questionId);
        QbUserWrong row = wrongMapper.selectByUserAndQuestion(TEST_USER, questionId);
        assertEquals(2, row.getWrongCount(),
                "两次答错 wrong_count 必须是 2 —— ON DUPLICATE KEY UPDATE 没生效？");
        assertEquals(1, row.getStatus());

        assertEquals(1, wrongMapper.masterIfUnmastered(TEST_USER, questionId));
        assertEquals(2, wrongMapper.selectByUserAndQuestion(TEST_USER, questionId).getStatus());
        assertEquals(0, wrongMapper.masterIfUnmastered(TEST_USER, questionId),
                "已掌握再答对应是空更新");

        wrongMapper.upsertWrong(TEST_USER, 1L, questionId);
        row = wrongMapper.selectByUserAndQuestion(TEST_USER, questionId);
        assertEquals(3, row.getWrongCount());
        assertEquals(1, row.getStatus(), "已掌握后再答错必须回退为未掌握");
        assertEquals(1L, countRows("qb_user_wrong"), "uk_user_question 保证全程只有一行");
    }

    @Test
    @DisplayName("统计 upsert：一条 SQL 完成建行与增量")
    void statAccumulates() {
        statMapper.upsertAccumulate(TEST_USER, 7L, 1, 0);
        statMapper.upsertAccumulate(TEST_USER, 7L, 0, 1);
        statMapper.upsertAccumulate(TEST_USER, 7L, 0, 1);
        QbUserSubjectStat stat = statMapper.selectByUserAndSubject(TEST_USER, 7L);
        assertEquals(3, stat.getTotalCount());
        assertEquals(1, stat.getCorrectCount());
        assertEquals(2, stat.getWrongCount());
        assertNotNull(stat.getLastAnswerAt());
    }

    @Test
    @DisplayName("-1 待自评哨兵可写；条件更新恰好生效一次，且跨用户不可判")
    void pendingSentinelAndConditionalJudge() {
        QbUserAnswerRecord record = new QbUserAnswerRecord();
        record.setUserId(TEST_USER);
        record.setSubjectId(1L);
        record.setQuestionId(5L);
        record.setUserAnswerJson("\"我的作答\"");
        record.setIsCorrect(-1);
        record.setDurationMs(1000);
        record.setAnswerMode(1);
        recordMapper.insert(record);
        assertNotNull(record.getId(), "IdType.AUTO 必须把自增主键回填到实体");

        assertEquals(0, recordMapper.judgeIfPending(record.getId(), TEST_USER + 1, 1),
                "别人的记录不能被判");
        assertEquals(1, recordMapper.judgeIfPending(record.getId(), TEST_USER, 1));
        assertEquals(0, recordMapper.judgeIfPending(record.getId(), TEST_USER, 0),
                "第二次必须 0 —— 这就是自评的幂等闸门");
    }
}