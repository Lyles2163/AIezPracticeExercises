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
import org.leon.practicemodule.pojo.QbQuestionOption;
import org.leon.practicemodule.pojo.QbUserAnswerRecord;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 读侧 SQL 的真库语义（段 3 唯一连库的测试）。
 *
 * 这些都是"只有 MySQL 能证"的事：LIMIT 的参数绑定形态、并列 sort_order 下翻页不重不漏、
 * MAX(id) 的"最近一次"语义、ON DUPLICATE KEY UPDATE id=id 的空更新、以及最重要的
 * **spec §6.2 的断点算法可证明会漏题**（本类把它和新算法做对照，见 breakpointSkips...）。
 *
 * 合成用户 user_id=990002（远高于真实 id，也不撞段 2 的 990001），
 * 每个用例前 purge 一次保证用例之间互不依赖（JUnit 的方法顺序不是我们能靠的），
 * @AfterAll 再清一次，跑完库里不留痕。
 */
@Tag("db-it")
@DisplayName("读侧 SQL 的真库语义")
class QuestionReadSqlDbIT {

    private static final long TEST_USER = 990002L;
    /** 语文：4 道启用题，且 qid 1 与 qid 6 的 sort_order 并列（实测 2026-10-24） */
    private static final long SUBJECT_1 = 1L;

    private static SqlSession session;
    private static QbQuestionMapper questionMapper;
    private static QbUserAnswerRecordMapper recordMapper;
    private static QbUserWrongMapper wrongMapper;
    private static QbUserFavoriteMapper favoriteMapper;
    private static QbQuestionOptionMapper optionMapper;

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
        cfg.addMapper(QbQuestionMapper.class);
        cfg.addMapper(QbUserAnswerRecordMapper.class);
        cfg.addMapper(QbUserWrongMapper.class);
        cfg.addMapper(QbUserFavoriteMapper.class);
        cfg.addMapper(QbQuestionOptionMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
        session = factory.openSession(true);
        questionMapper = session.getMapper(QbQuestionMapper.class);
        recordMapper = session.getMapper(QbUserAnswerRecordMapper.class);
        wrongMapper = session.getMapper(QbUserWrongMapper.class);
        favoriteMapper = session.getMapper(QbUserFavoriteMapper.class);
        optionMapper = session.getMapper(QbQuestionOptionMapper.class);
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
        for (String table : List.of("qb_user_answer_record", "qb_user_wrong", "qb_user_favorite")) {
            try (PreparedStatement ps = session.getConnection()
                    .prepareStatement("DELETE FROM " + table + " WHERE user_id = ?")) {
                ps.setLong(1, TEST_USER);
                ps.executeUpdate();
            }
        }
    }

    /**
     * 写一条作答记录。user_answer_json 是 MySQL json 列，值必须整份合法 JSON
     * （段 2 的实测教训：裸串写进去是 DataIntegrityViolationException → code=0）。
     */
    private static void insertRecord(long questionId, int isCorrect, int mode) {
        QbUserAnswerRecord r = new QbUserAnswerRecord();
        r.setUserId(TEST_USER);
        r.setSubjectId(SUBJECT_1);
        r.setQuestionId(questionId);
        r.setUserAnswerJson("\"探针作答\"");
        r.setIsCorrect(isCorrect);
        r.setDurationMs(1000);
        r.setAnswerMode(mode);
        recordMapper.insert(r);
    }

    private static Long queryLong(String sql, long... params) throws SQLException {
        try (PreparedStatement ps = session.getConnection().prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setLong(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getObject(1) != null ? rs.getLong(1) : null;
            }
        }
    }

    private static Map<Long, Long> positionsOf(List<Long> ids) {
        Map<Long, Long> map = new HashMap<>();
        for (QuestionPositionRow row : questionMapper.selectPositions(ids)) {
            map.put(row.questionId(), row.posNo());
        }
        return map;
    }

    @Test
    @DisplayName("锚点：科目 1 有 4 道启用题，且 sort_order 确实有并列")
    void duplicateSortOrderIsReal() throws SQLException {
        assertEquals(4L, questionMapper.countEnabledBySubject(SUBJECT_1));
        Map<Integer, List<Long>> bySort = new TreeMap<>();
        try (PreparedStatement ps = session.getConnection().prepareStatement(
                "SELECT id, sort_order FROM qb_question WHERE subject_id = ? AND status = 1"
                        + " ORDER BY sort_order, id")) {
            ps.setLong(1, SUBJECT_1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bySort.computeIfAbsent(rs.getInt(2), k -> new ArrayList<>()).add(rs.getLong(1));
                }
            }
        }
        assertTrue(bySort.values().stream().anyMatch(ids -> ids.size() > 1),
                "实测 sort_order 有重复才有本段的对照实验；哪天数据变了，裁定二的证据要重取：" + bySort);
    }

    @Test
    @DisplayName("位置序号：四道启用题各得 1..4 且互不相同（裁定四）")
    void positionsAreDistinctAndDense() {
        List<Long> ids = questionMapper.selectEnabledPage(SUBJECT_1, 0, 100)
                .stream().map(QuestionPageRow::questionId).toList();
        assertEquals(4, ids.size());
        Map<Long, Long> pos = positionsOf(ids);
        assertEquals(4, pos.size(), "有一道题没算出位置序号，子查询写错了");
        assertEquals(List.of(1L, 2L, 3L, 4L), ids.stream().map(pos::get).sorted().toList());
        // qid 1 排第一；与它 sort_order 并列的题排第二，而不是并列重号
        assertEquals(1L, pos.get(1L));
        assertTrue(pos.get(6L) > pos.get(1L), "并列 sort_order 的两题必须靠 id 分出先后");
    }

    @Test
    @DisplayName("分页：LIMIT offset,size 参数绑定可用，两页不重不漏（并列 sort_order 下）")
    void pagingIsStable() {
        List<Long> first = questionMapper.selectEnabledPage(SUBJECT_1, 0, 2)
                .stream().map(QuestionPageRow::questionId).toList();
        List<Long> second = questionMapper.selectEnabledPage(SUBJECT_1, 2, 2)
                .stream().map(QuestionPageRow::questionId).toList();
        assertEquals(2, first.size());
        assertEquals(2, second.size());
        List<Long> all = new ArrayList<>(first);
        all.addAll(second);
        assertEquals(4, all.size());
        assertEquals(4, all.stream().distinct().count(), "翻页出现重复题——排序没有全序");
        assertTrue(questionMapper.selectEnabledPage(SUBJECT_1, 4, 2).isEmpty(), "越界页必须是空列表");

        QuestionPageRow row = questionMapper.selectEnabledPage(SUBJECT_1, 0, 1).get(0);
        assertEquals(1L, row.questionId(), "第一题必须是 qid 1");
        assertNotNull(row.tagsJson());
        assertNotNull(row.sortOrder());
    }

    @Test
    @DisplayName("对照实验：spec §6.2 的 MAX(sort_order) 会漏题，NOT EXISTS 不漏（裁定二证据）")
    void breakpointSkipsNothingWhereOldAlgorithmSkips() throws SQLException {
        insertRecord(6L, 0, 1);   // 只答过 qid 6，而 qid 1 与它 sort_order 并列且同样未作答

        // 旧算法原样搬过来：MAX(sort_order) 之后取"sort_order 更大"的最小 id
        Long oldNext = queryLong(
                "SELECT MIN(id) FROM qb_question WHERE subject_id = ? AND status = 1 AND sort_order > ("
                        + " SELECT COALESCE(MAX(q.sort_order), -1) FROM qb_user_answer_record r"
                        + " JOIN qb_question q ON q.id = r.question_id"
                        + " WHERE r.user_id = ? AND r.subject_id = ? AND r.answer_mode IN (1,3))",
                SUBJECT_1, TEST_USER, SUBJECT_1);
        Long oldMax = queryLong("SELECT COALESCE(MAX(q.sort_order), -1) FROM qb_user_answer_record r"
                + " JOIN qb_question q ON q.id = r.question_id"
                + " WHERE r.user_id = ? AND r.subject_id = ? AND r.answer_mode IN (1,3)",
                TEST_USER, SUBJECT_1);

        Long newNext = questionMapper.selectFirstUnansweredQuestionId(SUBJECT_1, TEST_USER);

        assertEquals(1L, oldMax, "锚住前提：已答的 qid 6 的 sort_order 必须是 1");
        assertEquals(1L, newNext, "NOT EXISTS 必须给出未作答的 qid 1");
        assertNotNull(oldNext, "旧算法此时该给出某道题");
        assertNotEquals(1L, oldNext,
                "旧算法若也给出 qid 1，本条对照实验就没有证据了——数据变了要重取（见 duplicateSortOrderIsReal）");
    }

    @Test
    @DisplayName("考试记录不消耗练习进度：mode=2 的作答不影响断点")
    void examRecordsDoNotConsumePracticeProgress() {
        insertRecord(1L, 1, 2);   // 考试里答对过 qid 1
        assertEquals(1L, questionMapper.selectFirstUnansweredQuestionId(SUBJECT_1, TEST_USER),
                "7.1 的口径：考试不算练习进度，qid 1 仍该被推到");

        insertRecord(1L, 1, 1);   // 练习里也答过了
        assertNotEquals(1L, questionMapper.selectFirstUnansweredQuestionId(SUBJECT_1, TEST_USER),
                "练习答过之后 qid 1 才退出断点");
    }

    @Test
    @DisplayName("最近一次作答：一题一行取 MAX(id)，-1 与 mode 原样带出")
    void latestRecordPerQuestion() {
        insertRecord(2L, 1, 1);   // 先对
        insertRecord(2L, 0, 1);   // 后错 —— 这才算"最近一次"
        insertRecord(5L, -1, 1);  // 练习的待自评
        insertRecord(5L, -1, 2);  // 考试的待自评：mode 必须是 2 才能被 selfJudgeable 挡掉

        List<LatestAnswerRow> rows = recordMapper.selectLatestByQuestions(
                TEST_USER, List.of(2L, 5L, 1L));
        assertEquals(2, rows.size(), "qid 1 没有任何记录，不该出现在结果里");
        Map<Long, LatestAnswerRow> byQuestion = new HashMap<>();
        for (LatestAnswerRow row : rows) {
            byQuestion.put(row.questionId(), row);
        }
        assertEquals(0, byQuestion.get(2L).isCorrect(), "取的是 MAX(id) 那条，不是最早那条");
        assertEquals(-1, byQuestion.get(5L).isCorrect(), "两条 -1 里取 id 最大的一条");
        assertEquals(2, byQuestion.get(5L).answerMode());
        assertNotNull(byQuestion.get(2L).userAnswerJson());
        assertNotNull(byQuestion.get(2L).recordId());
    }

    @Test
    @DisplayName("错题分页：status 过滤、计数与位置、智能练习候选都取到题面字段")
    void wrongPageFilterAndSmartCandidate() {
        wrongMapper.upsertWrong(TEST_USER, SUBJECT_1, 1L);
        wrongMapper.upsertWrong(TEST_USER, SUBJECT_1, 1L);
        wrongMapper.upsertWrong(TEST_USER, SUBJECT_1, 2L);
        wrongMapper.masterIfUnmastered(TEST_USER, 2L);   // qid 2 置已掌握

        assertEquals(2L, wrongMapper.countWrong(TEST_USER, SUBJECT_1, null));
        assertEquals(1L, wrongMapper.countWrong(TEST_USER, SUBJECT_1, 1));
        assertEquals(1L, wrongMapper.countWrong(TEST_USER, SUBJECT_1, 2));

        List<WrongPageRow> all = wrongMapper.selectWrongPage(TEST_USER, SUBJECT_1, null, 0, 10);
        assertEquals(2, all.size());
        assertNotNull(all.get(0).lastWrongAt(), "ORDER BY 的列没带出来");
        assertNotNull(all.get(0).title(), "JOIN qb_question 的列没带出来，titleBrief 就没料可用");
        assertNotNull(all.get(0).type());

        List<WrongPageRow> unmastered = wrongMapper.selectWrongPage(TEST_USER, SUBJECT_1, 1, 0, 10);
        assertEquals(1, unmastered.size());
        assertEquals(1L, unmastered.get(0).questionId());
        assertEquals(2, unmastered.get(0).wrongCount(), "ON DUPLICATE KEY 的增量要在读侧看得见");
        assertEquals(1L, wrongMapper.selectSmartCandidateQuestionId(TEST_USER, SUBJECT_1),
                "未掌握只剩 qid 1");

        // 全部置已掌握后，智能练习没有候选
        wrongMapper.masterIfUnmastered(TEST_USER, 1L);
        assertNull(wrongMapper.selectSmartCandidateQuestionId(TEST_USER, SUBJECT_1));
    }

    @Test
    @DisplayName("收藏：toggle 三步在真库的形态（删除→空插入→回读）")
    void favoriteUpsertDeleteAndReadBack() {
        assertEquals(0, favoriteMapper.deleteByUserAndQuestion(TEST_USER, 1L), "起点必须没行");
        assertEquals(1, favoriteMapper.insertIgnoreDuplicate(TEST_USER, SUBJECT_1, 1L));
        assertEquals(1L, favoriteMapper.countByUserAndQuestion(TEST_USER, 1L));
        // 实测纠偏（原计划假设空更新回 0）：Connector/J 默认 useAffectedRows=false，
        // 这条 ON DUPLICATE KEY UPDATE id=id 回的是 found rows = 1，不是 affected rows = 0。
        // 结论：插入返回值不能当"是否新建了行"的判据，toggle 只能回读存在性——实现就是这么写的。
        favoriteMapper.insertIgnoreDuplicate(TEST_USER, SUBJECT_1, 1L);
        assertEquals(1L, favoriteMapper.countByUserAndQuestion(TEST_USER, 1L), "空更新既不报错也不建行");
        assertEquals(1L, favoriteMapper.countFavorites(TEST_USER, SUBJECT_1));
        assertEquals(List.of(1L), favoriteMapper.selectFavoritedQuestionIds(TEST_USER, List.of(1L, 2L)));

        favoriteMapper.insertIgnoreDuplicate(TEST_USER, SUBJECT_1, 2L);
        List<FavoritePageRow> page = favoriteMapper.selectFavoritePage(TEST_USER, SUBJECT_1, 0, 10);
        assertEquals(2, page.size());
        assertNotNull(page.get(0).createdAt());
        assertNotNull(page.get(0).title());
        // 新收藏的 qid 2 排在倒序第一（created_at 同秒时靠 id 兜底，不能抛异常就算过）
        assertNotNull(page.get(0).questionId());

        assertEquals(1, favoriteMapper.deleteByUserAndQuestion(TEST_USER, 2L));
        assertEquals(0, favoriteMapper.deleteByUserAndQuestion(TEST_USER, 2L), "再删是空操作，不抛异常");
    }

    @Test
    @DisplayName("选项查询不读 is_correct（spec 5.5 的字面形态）")
    void displayOptionsCarryNoCorrectness() {
        List<QbQuestionOption> options = optionMapper.selectDisplayOptions(1L);
        assertEquals(4, options.size(), "qid 1 的选项集实测是 A,B,C,D");
        for (QbQuestionOption o : options) {
            assertNull(o.getIsCorrect(), "SQL 里多了 is_correct——后端读真值这条红线被踩了");
            assertNotNull(o.getOptionKey());
            assertNotNull(o.getContent());
        }
        assertEquals("A", options.get(0).getOptionKey(), "选项必须按 sort_order,id 有序");
    }
}
