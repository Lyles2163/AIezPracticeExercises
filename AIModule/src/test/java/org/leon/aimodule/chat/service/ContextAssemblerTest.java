package org.leon.aimodule.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.rag.chunk.TokenCounter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextAssemblerTest {

    private ContextAssembler assembler(int budget) {
        ChatProperties props = new ChatProperties();
        props.setContextMaxTokens(budget);
        return new ContextAssembler(new TokenCounter(), props);
    }

    private static AiMessage msg(String role, String content) {
        AiMessage m = new AiMessage();
        m.setRole(role);
        m.setContent(content);
        m.setStatus(AiMessage.ST_DONE);
        return m;
    }

    private static String repeat(String unit, int times) {
        return unit.repeat(times);
    }

    @Test
    @DisplayName("历史为空时只有最新一条用户消息")
    void onlyLatest() {
        List<CtxMessage> out = assembler(8000).assemble(List.of(), "你好");
        assertEquals(1, out.size());
        assertEquals(AiMessage.ROLE_USER, out.get(0).role());
        assertEquals("你好", out.get(0).content());
    }

    @Test
    @DisplayName("预算够时按原顺序全带上")
    void keepsAllWhenFits() {
        List<AiMessage> prior = List.of(msg(AiMessage.ROLE_USER, "q1"),
                msg(AiMessage.ROLE_ASSISTANT, "a1"),
                msg(AiMessage.ROLE_USER, "q2"));
        List<CtxMessage> out = assembler(8000).assemble(prior, "q3");
        assertEquals(List.of(AiMessage.ROLE_USER, AiMessage.ROLE_ASSISTANT,
                        AiMessage.ROLE_USER, AiMessage.ROLE_USER),
                out.stream().map(CtxMessage::role).toList());
        assertEquals(List.of("q1", "a1", "q2", "q3"),
                out.stream().map(CtxMessage::content).toList());
    }

    @Test
    @DisplayName("预算不够时从最老一侧丢，最新消息永不丢")
    void dropsOldestFirst() {
        List<AiMessage> prior = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            prior.add(msg(AiMessage.ROLE_USER, repeat("上下文片段。", 40)));
            prior.add(msg(AiMessage.ROLE_ASSISTANT, repeat("回答内容。", 40)));
        }
        List<CtxMessage> out = assembler(500).assemble(prior, "最新问题");
        // cl100k 计数：最新问题 3、每条 user 历史 240、assistant 历史 200，预算 500。
        // 从新到旧累加：3+200=203 收，+240=443 收，再加下一条 200 会到 643，故停。
        // 只保留最后两条历史（user + assistant）并保持原顺序，旧的 78 条全丢。
        assertTrue(out.size() < prior.size(), "必须发生截断");
        assertEquals(3, out.size());
        assertEquals(List.of(AiMessage.ROLE_USER, AiMessage.ROLE_ASSISTANT, AiMessage.ROLE_USER),
                out.stream().map(CtxMessage::role).toList());
        assertEquals(repeat("上下文片段。", 40), out.get(0).content());
        assertEquals(repeat("回答内容。", 40), out.get(1).content());
        assertEquals("最新问题", out.get(2).content());
        assertEquals(443, assembler(500).totalTokens(out));
    }

    @Test
    @DisplayName("截断后首条不能是 assistant，否则模型以为对话是自己起的头")
    void neverStartsWithAssistant() {
        List<AiMessage> prior = List.of(
                msg(AiMessage.ROLE_USER, repeat("很长的背景。", 60)),
                msg(AiMessage.ROLE_ASSISTANT, repeat("回答。", 60)),
                msg(AiMessage.ROLE_USER, "短问题"),
                msg(AiMessage.ROLE_ASSISTANT, "短回答"));
        List<CtxMessage> out = assembler(120).assemble(prior, "再来一次");
        assertFalse(out.isEmpty());
        assertEquals(AiMessage.ROLE_USER, out.get(0).role(),
                "实际序列: " + out.stream().map(CtxMessage::role).toList());
        assertEquals(List.of("短问题", "短回答", "再来一次"),
                out.stream().map(CtxMessage::content).toList());
    }

    @Test
    @DisplayName("连续多条 assistant 开头也要一并丢掉")
    void dropsConsecutiveAssistantHead() {
        List<AiMessage> prior = List.of(
                msg(AiMessage.ROLE_USER, repeat("超长。", 80)),
                msg(AiMessage.ROLE_ASSISTANT, "a1"),
                msg(AiMessage.ROLE_ASSISTANT, "a2"),
                msg(AiMessage.ROLE_USER, "u3"));
        List<CtxMessage> out = assembler(60).assemble(prior, "q");
        // cl100k 计数：q=1、u3=2、a2=2、a1=2，合计 7 ≤ 60；最老的 user 超长。x80 = 240，
        // 从新往旧累加到它时 7+240=247 > 60，于是从它起截断。截断窗口留下 a1、a2 两条
        // 连续 assistant 作为开头；若只丢一条，历史会以 assistant 起头，故必须整段丢掉，
        // 最终只剩 u3 与最新问题 q。
        assertEquals(2, out.size());
        assertEquals(List.of(AiMessage.ROLE_USER, AiMessage.ROLE_USER),
                out.stream().map(CtxMessage::role).toList());
        assertEquals(List.of("u3", "q"), out.stream().map(CtxMessage::content).toList());
        assertEquals(3, assembler(60).totalTokens(out));
    }

    @Test
    @DisplayName("预算恰好用完时保留（边界判断是 > 而不是 >=）")
    void includesExactlyFittingMessages() {
        // q=1、u3=2，恰好等于预算 3，必须保留
        List<CtxMessage> out = assembler(3).assemble(List.of(msg(AiMessage.ROLE_USER, "u3")), "q");
        assertEquals(2, out.size());
        assertEquals(List.of(AiMessage.ROLE_USER, AiMessage.ROLE_USER),
                out.stream().map(CtxMessage::role).toList());
        assertEquals(List.of("u3", "q"), out.stream().map(CtxMessage::content).toList());
        assertEquals(3, assembler(3).totalTokens(out));

        // 最新消息本身恰好等于预算，也不算超预算
        List<CtxMessage> latestOnly = assembler(1).assemble(List.of(), "q");
        assertEquals(1, latestOnly.size());
        assertEquals("q", latestOnly.get(0).content());
    }

    @Test
    @DisplayName("单条最新消息就超预算：拒绝而不是静默截断")
    void rejectsOversizedLatest() {
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> assembler(10).assemble(List.of(), repeat("这个词很长很长很长。", 50)));
        assertEquals("消息过长，超出上下文预算", e.getMessage());
    }

    @Test
    @DisplayName("null 历史、null 内容与空白内容都不崩")
    void toleratesNulls() {
        List<CtxMessage> out = assembler(8000).assemble(null, "hi");
        assertEquals(1, out.size());
        assertEquals(AiMessage.ROLE_USER, out.get(0).role());
        assertEquals("hi", out.get(0).content());

        List<CtxMessage> nullLatest = assembler(8000).assemble(null, null);
        assertEquals(1, nullLatest.size());
        assertEquals("", nullLatest.get(0).content());
        assertEquals(0, assembler(8000).totalTokens(nullLatest));

        List<AiMessage> prior = new ArrayList<>();
        prior.add(msg(AiMessage.ROLE_USER, "   "));
        List<CtxMessage> blank = assembler(8000).assemble(prior, "hi");
        assertEquals(2, blank.size());
        assertEquals(1, assembler(8000).totalTokens(blank));
    }
}
