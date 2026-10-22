package org.leon.aimodule.rag.chunk;

import org.leon.aimodule.rag.parse.ChunkDraft;
import org.leon.aimodule.rag.parse.ParsedDoc;
import org.leon.aimodule.rag.parse.ParsedPage;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 500 token 预算 / 60 token 重叠；段落优先，单段超预算按句切，句子仍超预算按 token 硬切。
 * 纯函数：不读库、不发请求，输入相同输出必然相同。
 */
@Component
public class ChunkPlanner {

    public static final int BUDGET_TOKENS = 500;
    public static final int OVERLAP_TOKENS = 60;

    private static final Pattern HEADING = Pattern.compile(
            "^(第[一二三四五六七八九十百千零〇\\d]+[章节篇].{0,48}|\\d+(\\.\\d+){0,3}[\\s、.]\\S.{0,48}|[一二三四五六七八九十]+、\\S.{0,48})$");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[。！？!?；;\\n])|(?<=[.!?]\\s)");
    private static final String JOIN_SEPARATOR = "\n\n";

    private final TokenCounter tokenCounter;

    public ChunkPlanner(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    public List<ChunkDraft> plan(ParsedDoc doc) {
        List<Segment> segments = toSegments(doc);
        List<ChunkDraft> drafts = new ArrayList<>();
        List<Segment> window = new ArrayList<>();

        for (Segment segment : segments) {
            // 按拼接后的实际文本计 token，避免段落分隔符被漏算
            if (!window.isEmpty() && joinedTokens(join(window), segment) > BUDGET_TOKENS) {
                drafts.add(toDraft(drafts.size() + 1, window));
                window = overlapWindow(drafts.get(drafts.size() - 1), segment);
            }
            window.add(segment);
        }
        if (!window.isEmpty()) {
            drafts.add(toDraft(drafts.size() + 1, window));
        }
        return drafts;
    }

    private List<Segment> toSegments(ParsedDoc doc) {
        List<Segment> result = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        for (ParsedPage page : doc.pages()) {
            for (String paragraph : splitParagraphs(page.text())) {
                String text = paragraph.strip();
                if (text.isEmpty()) {
                    continue;
                }
                if (isHeading(text)) {
                    updateHeadings(headings, text);
                }
                String chapter = headings.isEmpty() ? null : String.join(" → ", headings);
                for (String piece : splitToBudget(text)) {
                    for (String canonical : canonicalPieces(piece)) {
                        result.add(new Segment(canonical, page.pageNo(), chapter));
                    }
                }
            }
        }
        return result;
    }

    private List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String block : text.split("\\R\\s*\\R")) {
            if (block.isBlank()) {
                continue;
            }
            if (block.indexOf('\n') < 0) {
                paragraphs.add(block);
                continue;
            }
            for (String line : block.split("\\R")) {
                if (!line.isBlank()) {
                    paragraphs.add(line);
                }
            }
        }
        return paragraphs;
    }

    private List<String> splitToBudget(String text) {
        if (tokenCounter.count(text) <= BUDGET_TOKENS) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : SENTENCE.split(text)) {
            if (sentence.isBlank()) {
                continue;
            }
            if (tokenCounter.count(sentence) > BUDGET_TOKENS) {
                if (current.length() > 0) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                pieces.addAll(tokenCounter.hardSplit(sentence, BUDGET_TOKENS));
                continue;
            }
            if (current.length() > 0 && tokenCounter.count(current + sentence) > BUDGET_TOKENS) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    /**
     * 硬切与句切片段可能以空白开头（按 token 解码常带前导空格），
     * 而 strip 并非 token 单调（如 " differ" 1 token → "differ" 2 token），
     * 所以片段在计数与入窗前统一 strip；strip 后仍超预算的继续硬切，直到每段都不超预算。
     */
    private List<String> canonicalPieces(String piece) {
        List<String> result = new ArrayList<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.addLast(piece);
        while (!pending.isEmpty()) {
            String text = pending.removeFirst().strip();
            if (text.isEmpty()) {
                continue;
            }
            if (tokenCounter.count(text) <= BUDGET_TOKENS) {
                result.add(text);
                continue;
            }
            List<String> parts = tokenCounter.hardSplit(text, BUDGET_TOKENS);
            if (parts.size() <= 1) {
                // 已无法再切，保留原文防止死循环
                result.add(text);
                continue;
            }
            for (int i = parts.size() - 1; i >= 0; i--) {
                pending.addFirst(parts.get(i));
            }
        }
        return result;
    }

    private boolean isHeading(String text) {
        return text.length() <= 80 && HEADING.matcher(text).matches();
    }

    private void updateHeadings(List<String> headings, String text) {
        boolean topLevel = text.matches("^第[一二三四五六七八九十百千零〇\\d]+[章篇].*")
                || text.matches("^\\d+[\\s、.](?!\\d).*");
        if (topLevel) {
            headings.clear();
        }
        headings.add(text.length() > 60 ? text.substring(0, 60) : text);
        while (headings.size() > 3) {
            headings.remove(0);
        }
    }

    private List<Segment> overlapWindow(ChunkDraft last, Segment next) {
        // 尾巴由 token 解码而来，可能以空格开头；先 strip 再入窗，保证窗口内容无边界空白
        String tail = tokenCounter.tail(last.content(), OVERLAP_TOKENS).strip();
        if (tail.isBlank()) {
            return new ArrayList<>();
        }
        if (joinedTokens(tail, next) > BUDGET_TOKENS) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(new Segment(tail, next.pageNo(), next.chapterPath())));
    }

    /**
     * 预算守卫按写盘时完全相同的文本计数：toDraft 会 strip 拼接结果，
     * 而 strip 并非 token 单调（如 " differ" 1 token → "differ" 2 token），
     * 所以这里必须先 strip 再计数，否则以空白开头的重叠尾巴会让预算溢出。
     */
    private int joinedTokens(String joinedHead, Segment next) {
        return tokenCounter.count((joinedHead + JOIN_SEPARATOR + next.text()).strip());
    }

    private String join(List<Segment> window) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < window.size(); i++) {
            if (i > 0) {
                sb.append(JOIN_SEPARATOR);
            }
            sb.append(window.get(i).text());
        }
        return sb.toString();
    }

    private ChunkDraft toDraft(int seq, List<Segment> window) {
        String content = join(window).strip();
        Segment first = window.get(0);
        // 窗口可能跨多个小节，取窗口内最深的章节路径
        String chapterPath = null;
        for (Segment segment : window) {
            if (segment.chapterPath() != null) {
                chapterPath = segment.chapterPath();
            }
        }
        return new ChunkDraft(seq, content, tokenCounter.count(content),
                first.pageNo(), null, chapterPath);
    }

    private record Segment(String text, Integer pageNo, String chapterPath) {
    }
}
