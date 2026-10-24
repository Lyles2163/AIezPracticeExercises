package org.leon.aimodule.chat.stream;

import java.util.ArrayList;
import java.util.List;

/**
 * reasoning token 流 → 可读短句。上游是按 token 吐的（实测一个短问题的 92 帧里
 * 63 帧是 reasoning，每帧一两个词），直接贴到思考线上就是几十行碎片。
 *
 * 只有编排层的 onNext 串行路径碰它，所以不加锁 —— 别顺手加一把没用的锁。
 * 不计时：时间不是切句职责，见 ThinkingTracker。
 */
public final class ReasoningSentenceSplitter {

    private static final String CJK_BOUNDARY = "。！？\n\r";
    private static final String LATIN_BOUNDARY = ".!?";
    private static final String ELLIPSIS = "…";

    private final StringBuilder buf = new StringBuilder();
    private final int maxStepChars;
    private final int maxSteps;
    private int emitted;
    private boolean saturated;

    public ReasoningSentenceSplitter(int maxStepChars, int maxSteps) {
        this.maxStepChars = Math.max(8, maxStepChars);
        this.maxSteps = Math.max(1, maxSteps);
    }

    public List<String> feed(String piece) {
        List<String> out = new ArrayList<>();
        if (saturated || piece == null || piece.isEmpty()) {
            return out;
        }
        buf.append(piece);
        int cut;
        while (!saturated && (cut = findBoundary()) >= 0) {
            emit(out, buf.substring(0, cut));
            buf.delete(0, cut);
        }
        while (!saturated && buf.length() >= maxStepChars) {
            int force = forceCutIndex();
            // 强切是"我知道句子还没完"，所以补省略号；自然边界不补。
            emit(out, buf.substring(0, force) + ELLIPSIS);
            buf.delete(0, force);
        }
        return out;
    }

    public List<String> flush() {
        List<String> out = new ArrayList<>();
        if (saturated) {
            return out;
        }
        emit(out, buf.toString());
        buf.setLength(0);
        return out;
    }

    /** 返回"切点之后第一个位置"，-1 表示还没有完整边界。 */
    private int findBoundary() {
        for (int i = 0; i < buf.length(); i++) {
            char c = buf.charAt(i);
            if (CJK_BOUNDARY.indexOf(c) >= 0) {
                return i + 1;
            }
            if (LATIN_BOUNDARY.indexOf(c) >= 0) {
                // 必须看到后一个字符才敢切：否则 "1.5" 和 "e.g." 会被劈开。
                if (i + 1 == buf.length()) {
                    return -1;
                }
                char next = buf.charAt(i + 1);
                if (Character.isWhitespace(next) || CJK_BOUNDARY.indexOf(next) >= 0
                        || LATIN_BOUNDARY.indexOf(next) >= 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private int forceCutIndex() {
        int lastSpace = -1;
        for (int i = 0; i < maxStepChars && i < buf.length(); i++) {
            if (buf.charAt(i) == ' ') {
                lastSpace = i;
            }
        }
        return lastSpace > 0 ? lastSpace : maxStepChars;
    }

    private void emit(List<String> out, String raw) {
        if (saturated) {
            return;
        }
        String step = clean(raw);
        if (step.isEmpty()) {
            return;
        }
        out.add(step);
        if (++emitted >= maxSteps) {
            saturated = true;
            buf.setLength(0);
        }
    }

    private String clean(String raw) {
        String s = raw.replaceAll("\\s+", " ").trim();
        if (s.length() > maxStepChars) {
            s = s.substring(0, maxStepChars - 1) + ELLIPSIS;
        }
        return s;
    }
}
