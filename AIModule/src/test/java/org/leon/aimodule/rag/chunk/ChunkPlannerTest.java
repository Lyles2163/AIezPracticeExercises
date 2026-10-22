package org.leon.aimodule.rag.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.parse.ChunkDraft;
import org.leon.aimodule.rag.parse.ParsedDoc;
import org.leon.aimodule.rag.parse.ParsedPage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPlannerTest {

    private final TokenCounter tokenCounter = new TokenCounter();
    private final ChunkPlanner planner = new ChunkPlanner(tokenCounter);

    @Test
    @DisplayName("长文按 500 token 预算切分且 seq 连续")
    void splitsByBudget() {
        String paragraph = "快速排序的时间复杂度是 O(n log n)，空间复杂度是 O(log n)。".repeat(60);
        ParsedDoc doc = new ParsedDoc("algo.txt", "算法", List.of(new ParsedPage(1, paragraph)));

        List<ChunkDraft> chunks = planner.plan(doc);

        assertTrue(chunks.size() >= 3);
        assertTrue(chunks.stream().allMatch(c -> c.tokenCount() <= ChunkPlanner.BUDGET_TOKENS));
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i + 1, chunks.get(i).seq());
        }
    }

    @Test
    @DisplayName("相邻切片有重叠")
    void hasOverlap() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 80; i++) {
            text.append("第").append(i).append("段：排序算法的稳定性与时间复杂度分析。\n\n");
        }
        ParsedDoc doc = new ParsedDoc("algo.txt", "算法", List.of(new ParsedPage(1, text.toString())));

        List<ChunkDraft> chunks = planner.plan(doc);
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(c -> tokenCounter.count(c.content()) <= ChunkPlanner.BUDGET_TOKENS),
                "每个切片的实际 token 数不得超过预算");

        String firstTail = chunks.get(0).content();
        firstTail = firstTail.substring(Math.max(0, firstTail.length() - 30));
        assertTrue(chunks.get(1).content().contains(firstTail),
                "第二个切片开头应包含第一个切片末尾的重叠文本");
    }

    @Test
    @DisplayName("预算计入段落间拼接分隔符")
    void budgetCountsJoinSeparators() {
        String paragraph = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda";
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            text.append(paragraph).append("\n\n");
        }
        ParsedDoc doc = new ParsedDoc("algo.txt", "算法", List.of(new ParsedPage(1, text.toString())));

        List<ChunkDraft> chunks = planner.plan(doc);

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(c -> tokenCounter.count(c.content()) <= ChunkPlanner.BUDGET_TOKENS),
                "每个切片的实际 token 数不得超过预算");
    }

    @Test
    @DisplayName("重叠窗口以空白开头时 strip 后仍不超预算")
    void overlapTailLeadingWhitespaceStillWithinBudget() {
        List<String> first = new ArrayList<>();
        for (int i = 0; i < 380; i++) {
            first.add("the");
        }
        first.add("differ");
        for (int i = 0; i < 59; i++) {
            first.add("the");
        }
        String paragraphA = String.join(" ", first);
        String paragraphC = String.join(" ", Collections.nCopies(439, "the"));
        ParsedDoc doc = new ParsedDoc("boundary.txt", "边界",
                List.of(new ParsedPage(1, paragraphA + "\n\n" + paragraphC)));

        List<ChunkDraft> chunks = planner.plan(doc);

        assertTrue(chunks.size() >= 2);
        // 重叠尾巴以 " differ"（1 token）开头，strip 后变成 "differ"（2 token）：预算必须按 strip 后计数
        assertTrue(chunks.stream().allMatch(c -> tokenCounter.count(c.content()) <= ChunkPlanner.BUDGET_TOKENS),
                "重叠窗口 strip 后实际 token 数不得超过预算: " + chunks.stream()
                        .map(c -> c.seq() + "=" + tokenCounter.count(c.content())).toList());
    }

    @Test
    @DisplayName("单段超预算时按句号切开")
    void splitsOversizedParagraph() {
        String paragraph = "这是一个很长的句子。".repeat(200);
        ParsedDoc doc = new ParsedDoc("a.txt", "标题", List.of(new ParsedPage(1, paragraph)));

        List<ChunkDraft> chunks = planner.plan(doc);

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(c -> c.tokenCount() <= ChunkPlanner.BUDGET_TOKENS));
    }

    @Test
    @DisplayName("页码取切片起始段落所在页")
    void keepsPageNumber() {
        String page1 = "第一页内容。".repeat(80);
        String page2 = "第二页内容。".repeat(80);
        ParsedDoc doc = new ParsedDoc("book.pdf", "教材", List.of(
                new ParsedPage(1, page1), new ParsedPage(2, page2)));

        List<ChunkDraft> chunks = planner.plan(doc);

        assertEquals(1, chunks.get(0).pageNo());
        assertTrue(chunks.stream().anyMatch(c -> Integer.valueOf(2).equals(c.pageNo())));
    }

    @Test
    @DisplayName("章节标题进入 chapterPath")
    void tracksChapterPath() {
        String text = "第3章 数据结构\n\n3.2 常见排序算法\n\n快速排序是分治算法。";
        ParsedDoc doc = new ParsedDoc("book.pdf", "教材", List.of(new ParsedPage(45, text)));

        List<ChunkDraft> chunks = planner.plan(doc);

        assertNotNull(chunks.get(0).chapterPath());
        assertTrue(chunks.get(0).chapterPath().contains("第3章"));
        assertTrue(chunks.get(0).chapterPath().contains("3.2"));
    }

    @Test
    @DisplayName("空文档返回空列表")
    void emptyDoc() {
        assertTrue(planner.plan(new ParsedDoc("empty.txt", "空", List.of())).isEmpty());
        assertTrue(planner.plan(new ParsedDoc("empty.txt", "空",
                List.of(new ParsedPage(1, "   ")))).isEmpty());
    }

    @Test
    @DisplayName("中英混排仍遵守预算")
    void mixedLanguage() {
        String text = ("Quick sort average time is O(n log n). 快速排序平均时间复杂度是 O(nlogn)。").repeat(80);
        ParsedDoc doc = new ParsedDoc("mixed.txt", "混排", List.of(new ParsedPage(1, text)));

        List<ChunkDraft> chunks = planner.plan(doc);

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(c -> c.tokenCount() <= ChunkPlanner.BUDGET_TOKENS));
    }
}
