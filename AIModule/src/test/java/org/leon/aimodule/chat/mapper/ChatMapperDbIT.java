package org.leon.aimodule.chat.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.entity.AiConversation;
import org.leon.aimodule.chat.entity.AiMessage;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("db-it")
class ChatMapperDbIT {

    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://localhost:3306/ezpracticeexercises_chat_it"
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        ds.setUser("root");
        ds.setPassword("");
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setEnvironment(new Environment("db-it", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(AiConversationMapper.class);
        cfg.addMapper(AiMessageMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
    }

    @BeforeEach
    void clean() {
        try (SqlSession s = factory.openSession(true)) {
            s.getConnection().createStatement().execute("DELETE FROM ai_message");
            s.getConnection().createStatement().execute("DELETE FROM ai_conversation");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Long newConversation(SqlSession s, String userName) {
        AiConversation c = new AiConversation();
        c.setUserName(userName);
        s.getMapper(AiConversationMapper.class).insert(c);
        assertNotNull(c.getId(), "自增主键必须回填到实体");
        return c.getId();
    }

    private Long newMessage(SqlSession s, Long cid, String role, String clientMsgId) {
        AiMessage m = new AiMessage();
        m.setConversationId(cid);
        m.setRole(role);
        m.setContent("x");
        m.setClientMsgId(clientMsgId);
        s.getMapper(AiMessageMapper.class).insert(m);
        return m.getId();
    }

    /** 用直连 SQL 把 update_time 回填到 1 分钟前；它绕过 MyBatis，故手动清一级缓存。 */
    private void backdateUpdateTime(SqlSession s, String table, Long id) throws SQLException {
        s.getConnection().createStatement().execute(
                "UPDATE " + table + " SET update_time = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) WHERE id = " + id);
        s.clearCache();
    }

    @Test
    @DisplayName("NOT NULL 列由 DDL 默认值补齐，DB 托管时间非空")
    void appliesDbDefaults() {
        try (SqlSession s = factory.openSession(true)) {
            Long cid = newConversation(s, "alice");
            AiConversation c = s.getMapper(AiConversationMapper.class).selectById(cid);
            assertEquals("新对话", c.getTitle());
            assertEquals("deepseek-chat", c.getModelKey());
            assertEquals("Medium", c.getEffort());
            assertEquals(0L, c.getMessageCount());
            assertEquals(1, c.getStatus());
            assertNotNull(c.getCreateTime());
            assertNotNull(c.getUpdateTime());
        }
    }

    @Test
    @DisplayName("assistant 行不填 client_msg_id，同会话多条不撞唯一键")
    void nullClientMsgIdDoesNotCollide() {
        try (SqlSession s = factory.openSession(true)) {
            Long cid = newConversation(s, "alice");
            AiMessageMapper mapper = s.getMapper(AiMessageMapper.class);
            for (int i = 0; i < 3; i++) {
                AiMessage m = new AiMessage();
                m.setConversationId(cid);
                m.setRole(AiMessage.ROLE_ASSISTANT);
                m.setContent("answer " + i);
                m.setStatus(AiMessage.ST_DONE);
                mapper.insert(m);
                assertNotNull(m.getId());
            }
            assertEquals(3, mapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiMessage>()
                            .eq(AiMessage::getConversationId, cid)));
        }
    }

    @Test
    @DisplayName("同一 user 消息下的旧 assistant 被 supersede，新的保持 done")
    void supersedeOnlyTargetsSameReplyTo() {
        try (SqlSession s = factory.openSession(true)) {
            Long cid = newConversation(s, "alice");
            AiMessageMapper mapper = s.getMapper(AiMessageMapper.class);
            Long user1 = newMessage(s, cid, AiMessage.ROLE_USER, "u1");
            Long user2 = newMessage(s, cid, AiMessage.ROLE_USER, "u2");

            AiMessage old = new AiMessage();
            old.setConversationId(cid);
            old.setRole(AiMessage.ROLE_ASSISTANT);
            old.setContent("v1");
            old.setStatus(AiMessage.ST_DONE);
            old.setReplyToMessageId(user1);
            mapper.insert(old);

            AiMessage other = new AiMessage();
            other.setConversationId(cid);
            other.setRole(AiMessage.ROLE_ASSISTANT);
            other.setContent("for u2");
            other.setStatus(AiMessage.ST_DONE);
            other.setReplyToMessageId(user2);
            mapper.insert(other);

            assertEquals(1, mapper.supersedePrevious(user1));
            assertEquals(AiMessage.ST_SUPERSEDED, mapper.selectById(old.getId()).getStatus());
            assertEquals(AiMessage.ST_DONE, mapper.selectById(other.getId()).getStatus(), "不能越界改别的回答");
        }
    }

    @Test
    @DisplayName("只回收超时的 streaming 行，未超时与已完成的不动")
    void reapOnlyStaleStreaming() throws SQLException {
        try (SqlSession s = factory.openSession(true)) {
            Long cid = newConversation(s, "alice");
            AiMessageMapper mapper = s.getMapper(AiMessageMapper.class);

            AiMessage live = new AiMessage();
            live.setConversationId(cid);
            live.setRole(AiMessage.ROLE_ASSISTANT);
            live.setContent("");
            live.setStatus(AiMessage.ST_STREAMING);
            mapper.insert(live);

            AiMessage stale = new AiMessage();
            stale.setConversationId(cid);
            stale.setRole(AiMessage.ROLE_ASSISTANT);
            stale.setContent("");
            stale.setStatus(AiMessage.ST_STREAMING);
            mapper.insert(stale);
            s.getConnection().createStatement().execute(
                    "UPDATE ai_message SET create_time = DATE_SUB(NOW(3), INTERVAL 30 MINUTE) WHERE id = " + stale.getId());

            assertEquals(1, mapper.countLiveStreaming(cid, 5), "只有未超时的算活跃");
            assertEquals(1, mapper.reapStaleStreaming(cid, 5, "生成中断"));
            assertEquals(AiMessage.ST_FAILED, mapper.selectById(stale.getId()).getStatus());
            assertEquals("生成中断", mapper.selectById(stale.getId()).getErrorMsg());
            assertEquals(AiMessage.ST_STREAMING, mapper.selectById(live.getId()).getStatus());
        }
    }

    @Test
    @DisplayName("touchOnNewMessage 累加计数，renameIfDefault 只改默认标题")
    void touchAndConditionalRename() {
        try (SqlSession s = factory.openSession(true)) {
            Long cid = newConversation(s, "alice");
            AiConversationMapper cm = s.getMapper(AiConversationMapper.class);
            AiMessageMapper mm = s.getMapper(AiMessageMapper.class);
            Long mid = newMessage(s, cid, AiMessage.ROLE_USER, "u1");

            assertEquals(1, cm.touchOnNewMessage(cid, mid, "把这段改成 STAR", 1));
            AiConversation c = cm.selectById(cid);
            assertEquals(1L, c.getMessageCount());
            assertEquals(mid, c.getLastMessageId());
            assertEquals("把这段改成 STAR", c.getLastPreview());

            assertEquals(1, cm.renameIfDefault(cid, "把这段改成"));
            assertEquals("把这段改成", cm.selectById(cid).getTitle());
            assertEquals(0, cm.renameIfDefault(cid, "不该覆盖"), "已改名的会话不再被自动改名");
            assertEquals("把这段改成", cm.selectById(cid).getTitle());

            cm.touchOnNewMessage(cid, mid, "x", -1);
            assertEquals(0L, cm.selectById(cid).getMessageCount());
            assertNull(cm.selectById(999999L), "不存在的会话返回 null");
            List<AiConversation> all = cm.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiConversation>()
                            .eq(AiConversation::getUserName, "alice"));
            assertEquals(1, all.size());
        }
    }

    @Test
    @DisplayName("delta=0 且其余列不变时 touch 仍推进 update_time，列表游标不会被卡住")
    void cursorAdvancesWhenDeltaZeroTouchChangesNothingElse() throws SQLException {
        try (SqlSession s = factory.openSession(true)) {
            Long cid = newConversation(s, "alice");
            AiConversationMapper cm = s.getMapper(AiConversationMapper.class);
            Long mid = newMessage(s, cid, AiMessage.ROLE_USER, "u1");
            String preview = "同一条预览";

            backdateUpdateTime(s, "ai_conversation", cid);
            LocalDateTime beforeFirst = cm.selectById(cid).getUpdateTime();
            assertEquals(1, cm.touchOnNewMessage(cid, mid, preview, 0));
            AiConversation afterFirst = cm.selectById(cid);
            assertTrue(afterFirst.getUpdateTime().isAfter(beforeFirst), "第一次 touch 必须推进 update_time");
            assertTrue(afterFirst.getUpdateTime().isAfter(beforeFirst.plusSeconds(30)), "回填 1 分钟必须生效");
            assertEquals(mid, afterFirst.getLastMessageId());
            assertEquals(preview, afterFirst.getLastPreview());
            assertEquals(0L, afterFirst.getMessageCount());

            backdateUpdateTime(s, "ai_conversation", cid);
            AiConversation beforeSecondRow = cm.selectById(cid);
            LocalDateTime beforeSecond = beforeSecondRow.getUpdateTime();
            assertEquals(mid, beforeSecondRow.getLastMessageId(), "第二次调用前其余列已与入参逐字节一致");
            assertEquals(preview, beforeSecondRow.getLastPreview());
            assertEquals(0L, beforeSecondRow.getMessageCount());
            assertEquals(1, cm.touchOnNewMessage(cid, mid, preview, 0));
            AiConversation afterSecond = cm.selectById(cid);
            assertTrue(afterSecond.getUpdateTime().isAfter(beforeSecond),
                    "delta=0 且其余列不变时 update_time 仍须前进，否则会话会被游标漏掉");
            assertTrue(afterSecond.getUpdateTime().isAfter(beforeSecond.plusSeconds(30)), "回填 1 分钟必须生效");
            assertEquals(mid, afterSecond.getLastMessageId());
            assertEquals(preview, afterSecond.getLastPreview());
            assertEquals(0L, afterSecond.getMessageCount());
        }
    }

    @Test
    @DisplayName("finalizeAssistant 一次写完正文、停止原因与用量，并严格推进 update_time")
    void finalizeWritesEverythingOnce() throws SQLException {
        try (SqlSession s = factory.openSession(true)) {
            Long cid = newConversation(s, "alice");
            AiMessageMapper mm = s.getMapper(AiMessageMapper.class);
            AiMessage m = new AiMessage();
            m.setConversationId(cid);
            m.setRole(AiMessage.ROLE_ASSISTANT);
            m.setContent("");
            m.setStatus(AiMessage.ST_STREAMING);
            mm.insert(m);

            backdateUpdateTime(s, "ai_message", m.getId());
            LocalDateTime before = mm.selectById(m.getId()).getUpdateTime();
            assertEquals(1, mm.finalizeAssistant(m.getId(), "## 正文", AiMessage.ST_DONE,
                    AiMessage.STOP_DONE, 812, 1640));
            AiMessage done = mm.selectById(m.getId());
            assertEquals("## 正文", done.getContent());
            assertEquals(AiMessage.ST_DONE, done.getStatus());
            assertEquals(AiMessage.STOP_DONE, done.getStopReason());
            assertEquals(812, done.getPromptTokens());
            assertEquals(1640, done.getCompletionTokens());
            assertTrue(done.getUpdateTime().isAfter(before),
                    "update_time 必须严格前进，否则按 update_time 排序的列表看不到这次写入");
            assertTrue(done.getUpdateTime().isAfter(before.plusSeconds(30)),
                    "回填 1 分钟必须生效，否则上一条断言证明不了推进");
        }
    }
}
