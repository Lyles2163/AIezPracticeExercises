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
import org.leon.practicemodule.pojo.QbQuestion;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真库探针：证明重生成后的实体能被 BaseMapper 正常查询。
 * 改动前这条必然报 Unknown column 'owner_id'，这是 Task 1.2 唯一的硬证据。
 * 自建 SqlSession，不用 @SpringBootTest（那会去拉 Nacos/Redis/Seata）。
 *
 * 关键点：BaseMapper 需要 MyBatis-Plus 自己的 MybatisConfiguration +
 * MybatisSqlSessionFactoryBuilder。用原生 Configuration/SqlSessionFactoryBuilder
 * 会静默丢掉 MP 的 SQL 注入，selectList 直接报"方法不存在"。
 */
@Tag("db-it")
@DisplayName("qb_question 实体与真实列一致（真库）")
class QbQuestionMapperDbIT {

    private static SqlSession session;
    private static QbQuestionMapper questionMapper;
    private static UsersReadMapper usersReadMapper;

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
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
        session = factory.openSession();
        questionMapper = session.getMapper(QbQuestionMapper.class);
        usersReadMapper = session.getMapper(UsersReadMapper.class);
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
        assertInstanceOf(String.class, rows.get(0).getAnswerJson(),
                "json 列必须以 String 承载，解析留给段 2 的 AnswerGrader");
    }

    @Test
    @DisplayName("users_dev 只读取 id：实测存在的 username 能换到 id")
    void usersReadMapperWorks() {
        // 实测 users_dev（2026-10-23）：1=admin, 4=wang_wu, 5=zhao_liu, 6=testuser01,
        // 8=testuser02, 9=test01, 10=test918, 11=test920, 14=adminL, 15=mqcap01, 16=adminL2
        assertEquals(1L, usersReadMapper.selectIdByUsername("admin"));
        assertNull(usersReadMapper.selectIdByUsername("__no_such_user__"));
    }
}
