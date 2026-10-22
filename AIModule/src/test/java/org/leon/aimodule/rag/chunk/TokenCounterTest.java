package org.leon.aimodule.rag.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenCounterTest {

    private final TokenCounter counter = new TokenCounter();

    @Test
    @DisplayName("空文本计 0 token")
    void emptyIsZero() {
        assertEquals(0, counter.count(""));
        assertEquals(0, counter.count(null));
    }

    @Test
    @DisplayName("tail 只保留末尾预算内的 token")
    void tailKeepsSuffix() {
        String text = "alpha beta gamma delta epsilon";
        String tail = counter.tail(text, 2);
        assertTrue(counter.count(tail) <= 2);
        assertTrue(text.endsWith(tail) || tail.contains("epsilon"));
    }

    @Test
    @DisplayName("hardSplit 每段都不超过预算且拼回原文 token 数不变")
    void hardSplitRespectsBudget() {
        String text = "快速排序的时间复杂度是O(nlogn)，空间复杂度是O(logn)。".repeat(20);
        List<String> parts = counter.hardSplit(text, 30);
        assertTrue(parts.size() > 1);
        int total = parts.stream().mapToInt(counter::count).sum();
        assertTrue(total >= counter.count(text) - parts.size());
        parts.forEach(p -> assertTrue(counter.count(p) <= 30));
    }
}
