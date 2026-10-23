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
 * RAG 文档切片规划器：把 {@link ParsedDoc} 拆成若干 {@link ChunkDraft}。
 *
 * <p>策略概览：
 * <ul>
 *   <li>预算：单片 ≤ {@value #BUDGET_TOKENS} token；相邻片之间保留 {@value #OVERLAP_TOKENS} token 重叠，
 *       以减少跨片语义断裂。</li>
 *   <li>切分优先级：段落 → 句子 → token 硬切，尽量在自然边界断开。</li>
 *   <li>章节路径：识别 “第 X 章/节/篇”、“1.2.3” 等标题样式，向下透传到片段，供召回过滤/溯源使用。</li>
 * </ul>
 *
 * <p>实现为纯函数：不读库、不发请求、不依赖外部时钟；同一 {@link ParsedDoc} 输入必然得到同一输出，
 * 便于单元测试和幂等重放。token 计数委托给 {@link TokenCounter}（CL100K_BASE）。
 */
@Component
public class ChunkPlanner {

    /** 单个 chunk 的 token 预算上限，超过则必须再切 */
    public static final int BUDGET_TOKENS = 500;
    /** 相邻 chunk 之间保留的重叠 token 数，用于维持跨片上下文 */
    public static final int OVERLAP_TOKENS = 60;

    /**
     * 标题识别正则，匹配三种常见样式：
     * <ol>
     *   <li>“第 X 章/节/篇 …”（中文数字或阿拉伯数字）</li>
     *   <li>“1 / 1.2 / 1.2.3 …” 形式的数字编号</li>
     *   <li>“一、 / 二、 …” 形式的中文序号</li>
     * </ol>
     * 尾部 {@code .{0,48}} 限制标题最大长度，避免把整段正文误判为标题。
     */
    private static final Pattern HEADING = Pattern.compile(
            "^(第[一二三四五六七八九十百千零〇\\d]+[章节篇].{0,48}|\\d+(\\.\\d+){0,3}[\\s、.]\\S.{0,48}|[一二三四五六七八九十]+、\\S.{0,48})$");

    /**
     * 句子切分点：中英文句末标点（。！？!?；; 与 [.!?] + 空白）以及换行符之后。
     * 使用零宽断言，切分时保留标点，避免语义损失。
     */
    private static final Pattern SENTENCE = Pattern.compile("(?<=[。！？!?；;\\n])|(?<=[.!?]\\s)");

    /** 片段拼接为 chunk 时使用的分隔符，与写盘/召回保持同一份文本 */
    private static final String JOIN_SEPARATOR = "\n\n";

    /** token 计数与硬切工具，负责 CL100K_BASE 编解码 */
    private final TokenCounter tokenCounter;

    public ChunkPlanner(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    /**
     * 主入口：将解析后的文档规划为 chunk 列表。
     *
     * <p>采用滑动窗口方式：不断把 {@link Segment} 加入窗口，一旦拼接后的 token 超预算，
     * 立即将当前窗口固化成一个 {@link ChunkDraft}，并用 {@link #overlapWindow} 生成
     * 携带重叠尾巴的新窗口继续累积；文档结束后剩余窗口单独收尾。
     *
     * @param doc 已解析的文档（按页携带原始文本）
     * @return 按顺序编号的 chunk 草稿列表，交给下游做向量化与写盘
     */
    public List<ChunkDraft> plan(ParsedDoc doc) {
        List<Segment> segments = toSegments(doc);
        List<ChunkDraft> drafts = new ArrayList<>();
        List<Segment> window = new ArrayList<>();

        for (Segment segment : segments) {
            // 按拼接后的实际文本计 token，避免段落分隔符被漏算
            if (!window.isEmpty() && joinedTokens(join(window), segment) > BUDGET_TOKENS) {
                drafts.add(toDraft(drafts.size() + 1, window));
                // 切窗后从上一片尾部取重叠 token 作为新窗口起点，保证语义连续
                window = overlapWindow(drafts.get(drafts.size() - 1), segment);
            }
            window.add(segment);
        }
        // 处理文档末尾未触发溢出的最后一窗
        if (!window.isEmpty()) {
            drafts.add(toDraft(drafts.size() + 1, window));
        }
        return drafts;
    }

    /**
     * 文档 → 片段序列：逐页拆段落，识别标题维护章节栈，再按预算切成 {@link Segment}。
     *
     * <p>标题本身既会更新 {@code headings} 栈，也会作为普通文本进入片段，方便召回时命中章节名。
     */
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
                // 当前章节路径以 " → " 连接，最多保留 3 层，避免路径字符串过长
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

    /**
     * 段落切分：优先按空行分块（识别任意换行符 + 中间空白），
     * 块内若仍含多行（如 PDF 抽取时软换行未合并），进一步按单行拆开，
     * 保证每个段落自身在语义上尽量独立。
     */
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

    /**
     * 段内预算切分：段落不超预算直接返回；否则按句子累积，句子拼入会超预算时先封箱，
     * 单句仍超预算则调用 {@link TokenCounter#hardSplit} 做 token 硬切兜底。
     */
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
                // 句子自身超预算：先封箱已累积内容，再对该句直接 token 硬切
                if (current.length() > 0) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                pieces.addAll(tokenCounter.hardSplit(sentence, BUDGET_TOKENS));
                continue;
            }
            if (current.length() > 0 && tokenCounter.count(current + sentence) > BUDGET_TOKENS) {
                // 拼接后超预算：当前累积封箱，本句另起一片
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

    /** 判断给定文本是否为标题行：长度 ≤ 80 且匹配 {@link #HEADING} 三种样式之一 */
    private boolean isHeading(String text) {
        return text.length() <= 80 && HEADING.matcher(text).matches();
    }

    /**
     * 维护章节标题栈：
     * <ul>
     *   <li>遇到顶层标题（“第 X 章/篇”或 “1 / 1 、 / 1 .” 形式的数字编号）时清空历史栈，
     *       表示进入新的一章。</li>
     *   <li>标题超过 60 字符时截断，防止路径字符串膨胀。</li>
     *   <li>栈深度上限为 3，超出时丢弃最早的一层，保证 “章 → 节 → 小节” 三层可见。</li>
     * </ul>
     */
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

    /**
     * 切窗后构造新的起始窗口：从上一片尾部取 {@value #OVERLAP_TOKENS} token 作为重叠尾巴。
     *
     * <p>两种情况下放弃重叠、返回空窗口：
     * <ol>
     *   <li>尾块 strip 后为空（极端情况：整段重叠都是空白）；</li>
     *   <li>尾块 + 下一片段拼接后立刻超预算——此时保留重叠反而会挤压后续内容。</li>
     * </ol>
     * 重叠段继承下一片段的 {@code pageNo} 与 {@code chapterPath}，保证跨片元数据一致。
     */
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

    /** 按 {@link #JOIN_SEPARATOR} 拼接窗口内所有片段，得到写盘用的原始文本 */
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

    /**
     * 窗口 → {@link ChunkDraft}：
     * <ul>
     *   <li>正文 strip 后计数，保证与最终写盘文本一致；</li>
     *   <li>{@code pageNo} 取窗口首段所在页，作为主要溯源锚点；</li>
     *   <li>{@code chapterPath} 取窗口内“最深”（最后一个非空）章节路径，避免早期小节覆盖后续小节信息。</li>
     * </ul>
     */
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

    /**
     * 内部切片的最小单元：一段已经符合预算、strip 干净的文本，
     * 携带来源页码与当时的章节路径；窗口按 {@link Segment} 组合成最终 chunk。
     */
    private record Segment(String text, Integer pageNo, String chapterPath) {
    }
}
