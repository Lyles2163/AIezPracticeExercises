package org.leon.aimodule.chat.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningSentenceSplitterTest {

    private static ReasoningSentenceSplitter s() {
        return new ReasoningSentenceSplitter(120, 12);
    }

    private static String feedAll(ReasoningSentenceSplitter sp, String... pieces) {
        StringBuilder out = new StringBuilder();
        for (String p : pieces) {
            for (String step : sp.feed(p)) {
                out.append("[").append(step).append("]");
            }
        }
        for (String step : sp.flush()) {
            out.append("[").append(step).append("]");
        }
        return out.toString();
    }

    @Test
    @DisplayName("逐 token 喂英文整句：一句一步，边界标点留在句内")
    void englishSentences() {
        assertEquals("[The user asks 17*23.][I should compute it.]",
                feedAll(s(), "The", " user", " asks", " 17*23", ".", " I", " should", " compute", " it", "."));
    }

    @Test
    @DisplayName("边界被劈在两个分片中间也要等齐再切")
    void boundarySplitAcrossPieces() {
        assertEquals("[together now.]", feedAll(s(), "toge", "ther now", "."));
    }

    @Test
    @DisplayName("小数点不切：1.5 属于同一句（lookahead 规则）")
    void doesNotSplitOnDecimal() {
        assertEquals("[The value is 1.5 meters.][Shown here.]",
                feedAll(s(), "The value is 1.5 meters", ". ", "Shown here", ". "));
    }

    @Test
    @DisplayName("小数点跨帧到达：句末点号落在分片末尾时必须等下一帧再判")
    void trailingPeriodWaitsForNextPiece() {
        // 上游按 token 吐，"…1" / "." / "5 meters" 三帧把一个小数劈开是真实形状。
        // 若分片末尾的 '.' 被当成已确认的边界，这里就会切出 "The value is 1." 这种半句。
        // 摘掉"末尾点号要等下一个字符"这条规则时，只有这条用例会红 ——
        // 其余用例都把点号放在分片中间，看不出时机差异。
        assertEquals("[The value is 1.5 meters.]",
                feedAll(s(), "The value is 1", ".", "5 meters", ". "));
    }

    @Test
    @DisplayName("已知取舍：e.g. 这类缩写会在后随空格处提前断句 —— 接受，不要当 bug 修")
    void abbreviationSplitsAreAccepted() {
        // "See e.g. below." 被切成两步。切句只服务展示层，多切一刀不影响任何语义；
        // 为它维护缩写表是 scope creep。这条用例钉住"当前行为"，
        // 将来有人加缩写表时会在这里被提醒，而不是悄悄改掉语义。
        assertEquals("[See e.g.][below.]", feedAll(s(), "See e.g. below", ". "));
    }

    @Test
    @DisplayName("中文句号自终结，不需要后视")
    void cjkBoundaryIsSelfTerminating() {
        assertEquals("[先算乘法。][再核对。]", feedAll(s(), "先算乘法。", "再核对", "。"));
    }

    @Test
    @DisplayName("换行是边界，且清洗后不留空白步骤")
    void newlineIsBoundary() {
        assertEquals("[line one][line two]", feedAll(s(), "line one\nline two\n"));
    }

    @Test
    @DisplayName("无标点的长文触发强切：在空格处断，不硬砍单词，并标出被切过")
    void forceCutAtCap() {
        ReasoningSentenceSplitter sp = new ReasoningSentenceSplitter(20, 12);
        assertEquals("[aaaa bbbb cccc dddd…][eeee ffff]",
                feedAll(sp, "aaaa bbbb cccc dddd eeee ffff"));
    }

    @Test
    @DisplayName("纯空白与纯标点碎片不产出步骤")
    void blankFragmentsProduceNothing() {
        ReasoningSentenceSplitter sp = s();
        assertEquals(List.of(), sp.feed("   "));
        assertEquals(List.of(), sp.feed("\n"));   // 切出来是空串，被丢弃
        assertEquals(List.of(), sp.flush());
    }

    @Test
    @DisplayName("超步骤上限后不再产出，但 flush 不报错")
    void saturatesAtMaxSteps() {
        ReasoningSentenceSplitter sp = new ReasoningSentenceSplitter(120, 2);
        // 每个分片以空格收尾：单独喂 "a." 时末尾的 '.' 会因 lookahead 而"等下一个字符"，
        // 那样测的是后视规则而不是上限规则。
        assertEquals("[a.][b.]", feedAll(sp, "a. ", "b. ", "c. ", "d. "));
    }

    @Test
    @DisplayName("flush 连调两次不重复吐出（进入正文相与 onComplete 都会调）")
    void flushIsIdempotent() {
        ReasoningSentenceSplitter sp = s();
        sp.feed("tail without boundary");
        assertEquals(List.of("tail without boundary"), sp.flush());
        assertEquals(List.of(), sp.flush());
    }

    @Test
    @DisplayName("超长单句截断到上限并加省略号")
    void truncatesOverlongSentence() {
        ReasoningSentenceSplitter sp = new ReasoningSentenceSplitter(10, 12);
        List<String> out = sp.feed("abcdefghijklmno.");
        assertEquals(1, out.size());
        assertEquals(10, out.get(0).length());
        assertTrue(out.get(0).endsWith("…"));
    }
}
