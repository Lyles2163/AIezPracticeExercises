package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import org.leon.practicemodule.pojo.QbEntityColumns;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbSubject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真库探针：证明重生成后的实体能被 BaseMapper 正常查询。
 * 改动前这条必然报 Unknown column 'owner_id'，这是 Task 1.2 唯一的硬证据。
 * 自建 SqlSession，不用 @SpringBootTest（那会去拉 Nacos/Redis/Seata）。
 *
 * 关键点：BaseMapper 需要 MyBatis-Plus 自己的 MybatisConfiguration +
 * MybatisSqlSessionFactoryBuilder。用原生 Configuration/SqlSessionFactoryBuilder
 * 会静默丢掉 MP 的 SQL 注入，selectList 直接报"方法不存在"。
 *
 * 另有一条七表列对齐契约：单测只钉住"实体侧"声明的列，本类再拿 information_schema 的
 * 真实列与之逐名比对，库里多列/少列同样当场失败。
 */
@Tag("db-it")
@DisplayName("qb_question 实体与真实列一致（真库）")
class QbQuestionMapperDbIT {

    /** 与 open() 里 URL 的库名保持一致；列对齐契约就是在这个 schema 上取真实列。 */
    private static final String SCHEMA = "ezpracticeexercises";

    private static SqlSession session;
    private static QbQuestionMapper questionMapper;
    private static UsersReadMapper usersReadMapper;
    private static QbSubjectMapper subjectMapper;
    private static QbUserSubjectStatMapper subjectStatMapper;

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
        cfg.addMapper(QbQuestionMapper.class);
        cfg.addMapper(UsersReadMapper.class);
        cfg.addMapper(QbSubjectMapper.class);
        cfg.addMapper(QbUserSubjectStatMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
        session = factory.openSession();
        questionMapper = session.getMapper(QbQuestionMapper.class);
        usersReadMapper = session.getMapper(UsersReadMapper.class);
        subjectMapper = session.getMapper(QbSubjectMapper.class);
        subjectStatMapper = session.getMapper(QbUserSubjectStatMapper.class);
    }

    @AfterAll
    static void close() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    @DisplayName("selectList 不再报 Unknown column 'owner_id'")
    void selectListWorks() {
        List<QbQuestion> rows = questionMapper.selectList(
                new QueryWrapper<QbQuestion>().eq("status", 1).orderByAsc("sort_order"));
        assertNotNull(rows);
        assertFalse(rows.isEmpty(), "种子数据里 qb_question 有 7 行，全空说明连错库");
        assertNotNull(rows.get(0).getAnswerJson());
    }

    @Test
    @DisplayName("users_dev 只读取 id：实测存在的 username 能换到 id")
    void usersReadMapperWorks() {
        // 实测 users_dev（2026-10-23）：1=admin, 4=wang_wu, 5=zhao_liu, 6=testuser01,
        // 8=testuser02, 9=test01, 10=test918, 11=test920, 14=adminL, 15=mqcap01, 16=adminL2
        assertEquals(1L, usersReadMapper.selectIdByUsername("admin"));
        assertNull(usersReadMapper.selectIdByUsername("__no_such_user__"));
    }

    @Test
    @DisplayName("科目与统计的 @Select 脚本能跑通：驼峰映射、record 投影、foreach 都生效")
    void subjectQueriesWorkAgainstLiveDb() {
        List<QbSubject> subjects = subjectMapper.selectEnabledSubjects();
        assertFalse(subjects.isEmpty(), "qb_subject 实测有 2 行启用科目");
        assertTrue(subjects.stream().allMatch(s -> s.getSubjectKey() != null),
                "subject_key 没映射到 subjectKey —— 下划线转驼峰在注解查询里失效了");

        List<Long> ids = subjects.stream().map(QbSubject::getId).toList();
        List<StatCountRow> counts = subjectMapper.countEnabledQuestionRows(ids);
        assertFalse(counts.isEmpty(), "两个科目下都有启用题目；空结果说明 record 构造器投影失败");
        // 实测：科目 1 有 4 题、科目 2 有 3 题（status=1）
        assertEquals(4L, counts.stream().filter(r -> r.subjectId() == 1L)
                .findFirst().orElseThrow().cnt());
        assertEquals(3L, counts.stream().filter(r -> r.subjectId() == 2L)
                .findFirst().orElseThrow().cnt());

        // 种子统计属于幽灵用户 2/3（users_dev 无这两个 id），这里只验含 foreach 的 SQL 能跑
        assertNotNull(subjectStatMapper.selectByUserAndSubjects(1L, ids));
        assertNotNull(subjectStatMapper.selectByUserAndSubjects(2L, ids));
    }

    @Test
    @DisplayName("七张 qb_* 表的实体声明列与真实列逐名一致")
    void allSevenEntitiesMatchLiveColumns() throws SQLException {
        Map<String, List<String>> liveColumns = liveColumnsOfQbTables();
        for (Class<?> entity : QbEntityColumns.qbEntities()) {
            String table = QbEntityColumns.tableNameOf(entity);
            assertNotNull(table, entity.getSimpleName() + " 没声明 @TableName，无法与真实列比对");
            List<String> actual = liveColumns.get(table);
            assertNotNull(actual, "库里没有表 " + table + "（实体 " + entity.getSimpleName()
                    + "），列对齐契约失效");
            Set<String> declared = QbEntityColumns.columnsOf(entity);
            Set<String> real = new LinkedHashSet<>(actual);
            assertEquals(real, declared, table + " 与实体 " + entity.getSimpleName() + " 列集不一致："
                    + "实体多声明 " + minus(declared, real)
                    + "，实体漏声明 " + minus(real, declared)
                    + "（真实列按建表顺序：" + actual + "）");
        }
    }

    /**
     * 只读查 information_schema.COLUMNS，取九张 qb_* 表的真实列（按 ORDINAL_POSITION 排序）。
     * 表名取自各实体的 @TableName，因此这条同时验住了"实体指向的表确实存在"。
     */
    private static Map<String, List<String>> liveColumnsOfQbTables() throws SQLException {
        List<Class<?>> entities = QbEntityColumns.qbEntities();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < entities.size(); i++) {
            placeholders.append(i == 0 ? "?" : ",?");
        }
        String sql = "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME IN (" + placeholders + ")"
                + " ORDER BY TABLE_NAME, ORDINAL_POSITION";
        // 连接归 session 所有，由 @AfterAll 关闭，这里不能自己 close。
        Connection conn = session.getConnection();
        assertEquals(SCHEMA, conn.getCatalog(), "连的库不是 " + SCHEMA + "，真实列比对无意义");
        Map<String, List<String>> columns = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, SCHEMA);
            for (int i = 0; i < entities.size(); i++) {
                ps.setString(i + 2, QbEntityColumns.tableNameOf(entities.get(i)));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(rs.getString(2));
                }
            }
        }
        assertEquals(entities.size(), columns.size(),
                "information_schema 里只找到 " + columns.size() + " 张 qb_* 表，应有 "
                        + entities.size() + " 张：" + columns.keySet());
        return columns;
    }

    private static Set<String> minus(Set<String> from, Set<String> toRemove) {
        Set<String> diff = new LinkedHashSet<>(from);
        diff.removeAll(toRemove);
        return diff;
    }
}
