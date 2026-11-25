package org.leon.practicemodule.manage.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地规则题库解析器（无外部依赖，纯函数式）。
 *
 * <p>支持五种题型（1单选 / 2多选 / 3判断 / 4填空 / 5简答），兼容 Markdown 标题、
 * 中文/数字编号、行内/多行选项、中英文元信息字段（答案/解析/难度/标签/分值）。
 * 单题缺失必填项时记录警告并跳过，保证合法题目可顺利入库。
 */
@Component
public class QuestionTextParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 章节/题型大标题：# 一、单选题 / ## Part I 选择题 / 一、多选题 */
    private static final Pattern SECTION_MD = Pattern.compile("^#{1,2}\\s+(?!\\d+[.、)）])(.+)$");
    private static final Pattern SECTION_CN = Pattern.compile(
            "^(?:[一二三四五六七八九十]+\\s*[、.．]|Part\\s+[IVX0-9]+\\b)(.*)$",
            Pattern.CASE_INSENSITIVE);

    /** 题目起始行：支持 ### 1. / 1. / 1、 / (1) / （1） / Q1: / 第1题 */
    private static final Pattern QUESTION_START = Pattern.compile(
            "^(?:#{1,6}\\s+)?(?:(?:Q|q)\\s*\\d+\\s*[.:：、)）]|第\\s*\\d+\\s*题\\s*[.:：、)）]?|[（(]\\s*\\d+\\s*[)）]\\s*[.、:：]?|\\d+\\s*[.、)）])\\s*(.+)$");

    /** 题型前缀标签：[单选] / 【多选题】 / (判断) / [single] */
    private static final Pattern TYPE_TAG = Pattern.compile(
            "^[\\[【(（]\\s*(单选题?|多选题?|判断题?|填空题?|简答题?|问答题?|论述题?|single|multi|judge|blank|short)\\s*[\\]】)）]\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    /** 单个选项起始：A. / A、 / A) / A） / (A) / （A） */
    private static final Pattern OPTION_LINE = Pattern.compile(
            "^(?:[（(]([A-Ha-h])[)）]|([A-Ha-h])\\s*[.、)）:：])\\s*(.+)$");

    /** 行内拆分多选项：如 "A. int   B. boolean   C. String" */
    private static final Pattern INLINE_OPTION_SPLIT = Pattern.compile(
            "(?<=\\s)(?=(?:[（(][A-Ha-h][)）]|[A-Ha-h][.、)）])\\s*\\S)");

    /** 元信息字段行 */
    private static final Pattern FIELD_ANSWER = Pattern.compile(
            "^(?:\\*\\*)?(?:参考答案|正确答案|标准答案|答案|Answer)(?:\\*\\*)?\\s*[:：]\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD_ANALYSIS = Pattern.compile(
            "^(?:\\*\\*)?(?:答案解析|试题解析|解析|Explanation|Analysis)(?:\\*\\*)?\\s*[:：]\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD_DIFFICULTY = Pattern.compile(
            "^(?:\\*\\*)?(?:难度|Difficulty)(?:\\*\\*)?\\s*[:：]\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD_TAGS = Pattern.compile(
            "^(?:\\*\\*)?(?:标签|知识点|Tags?)(?:\\*\\*)?\\s*[:：]\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD_SCORE = Pattern.compile(
            "^(?:\\*\\*)?(?:分值|分数|Score)(?:\\*\\*)?\\s*[:：]\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BLANK_SLOT = Pattern.compile("_{2,}|（\\s*）|\\(\\s*\\)|【\\s*】");

    private enum FieldMode {
        TITLE, OPTION, ANSWER, ANALYSIS
    }

    private static final class RawBlock {
        final int rawIndex;
        final String sectionTitle;
        final Integer sectionTypeHint;
        Integer explicitType;
        final StringBuilder title = new StringBuilder();
        final LinkedHashMap<String, StringBuilder> options = new LinkedHashMap<>();
        String lastOptionKey;
        final StringBuilder answer = new StringBuilder();
        final StringBuilder analysis = new StringBuilder();
        String difficultyRaw;
        String tagsRaw;
        String scoreRaw;
        FieldMode mode = FieldMode.TITLE;

        RawBlock(int rawIndex, String sectionTitle, Integer sectionTypeHint) {
            this.rawIndex = rawIndex;
            this.sectionTitle = sectionTitle;
            this.sectionTypeHint = sectionTypeHint;
        }
    }

    public ParseResult parse(String text) {
        if (text == null || text.isBlank()) {
            return new ParseResult(List.of(), List.of("文档内容为空"), 0);
        }

        List<RawBlock> blocks = segmentBlocks(text);
        if (blocks.isEmpty()) {
            return new ParseResult(List.of(),
                    List.of("未识别到符合格式的题目（请以数字编号如 1. 开头，或下载示例模板参考格式）"), 0);
        }

        List<ParsedQuestion> questions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int seq = 1;
        for (RawBlock block : blocks) {
            ParsedQuestion parsed = convertBlock(block, seq, warnings);
            if (parsed != null) {
                questions.add(parsed);
                seq++;
            }
        }
        return new ParseResult(questions, warnings, blocks.size());
    }

    private List<RawBlock> segmentBlocks(String text) {
        List<RawBlock> blocks = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        String currentSection = null;
        Integer sectionHint = null;
        RawBlock current = null;
        int rawIndex = 0;

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || trimmed.equals("---") || trimmed.equals("***")) {
                continue;
            }

            // 1. 先判断是否是题目起始行
            Matcher qm = QUESTION_START.matcher(trimmed);
            if (qm.matches()) {
                rawIndex++;
                current = new RawBlock(rawIndex, currentSection, sectionHint);
                blocks.add(current);
                String rest = stripMarkdownBold(qm.group(1).trim());
                Matcher tm = TYPE_TAG.matcher(rest);
                if (tm.matches()) {
                    current.explicitType = parseTypeName(tm.group(1));
                    rest = tm.group(2).trim();
                }
                appendLine(current.title, rest);
                current.mode = FieldMode.TITLE;
                continue;
            }

            // 2. 非题目起始行：检查是否是章节/大题标题
            String detectedSection = detectSectionHeader(trimmed);
            if (detectedSection != null) {
                currentSection = detectedSection;
                sectionHint = inferTypeFromText(detectedSection);
                current = null;
                continue;
            }

            // 如果首行直接以 [单选] 开头但没有题号，也容错识别为一题
            Matcher bareType = TYPE_TAG.matcher(stripMarkdownBold(trimmed));
            if (current == null && bareType.matches()) {
                rawIndex++;
                current = new RawBlock(rawIndex, currentSection, sectionHint);
                blocks.add(current);
                current.explicitType = parseTypeName(bareType.group(1));
                appendLine(current.title, bareType.group(2).trim());
                current.mode = FieldMode.TITLE;
                continue;
            }

            if (current == null) {
                continue;
            }

            // 3. 元信息字段行（答案 / 解析 / 难度 / 标签 / 分值）
            Matcher m;
            if ((m = FIELD_ANSWER.matcher(trimmed)).matches()) {
                appendLine(current.answer, m.group(1).trim());
                current.mode = FieldMode.ANSWER;
                continue;
            }
            if ((m = FIELD_ANALYSIS.matcher(trimmed)).matches()) {
                appendLine(current.analysis, m.group(1).trim());
                current.mode = FieldMode.ANALYSIS;
                continue;
            }
            if ((m = FIELD_DIFFICULTY.matcher(trimmed)).matches()) {
                current.difficultyRaw = m.group(1).trim();
                continue;
            }
            if ((m = FIELD_TAGS.matcher(trimmed)).matches()) {
                current.tagsRaw = m.group(1).trim();
                continue;
            }
            if ((m = FIELD_SCORE.matcher(trimmed)).matches()) {
                current.scoreRaw = m.group(1).trim();
                continue;
            }

            // 4. 选项行（仅在尚未进入答案/解析区时解析选项，避免解析里出现 "A. xxx" 被误当选项）
            if (current.mode == FieldMode.TITLE || current.mode == FieldMode.OPTION) {
                if (tryParseOptions(current, trimmed)) {
                    current.mode = FieldMode.OPTION;
                    continue;
                }
            }

            // 5. 续行：追加到当前所处字段
            switch (current.mode) {
                case TITLE -> appendLine(current.title, trimmed);
                case OPTION -> {
                    if (current.lastOptionKey != null) {
                        appendLine(current.options.get(current.lastOptionKey), trimmed);
                    }
                }
                case ANSWER -> appendLine(current.answer, trimmed);
                case ANALYSIS -> appendLine(current.analysis, trimmed);
            }
        }
        return blocks;
    }

    private boolean tryParseOptions(RawBlock block, String trimmed) {
        if (!OPTION_LINE.matcher(trimmed).matches()) {
            return false;
        }
        // 支持一行多个选项（如 "A. int  B. boolean  C. String  D. double"）
        String[] segments = INLINE_OPTION_SPLIT.split(trimmed);
        boolean matchedAny = false;
        for (String seg : segments) {
            Matcher om = OPTION_LINE.matcher(seg.trim());
            if (om.matches()) {
                String key = (om.group(1) != null ? om.group(1) : om.group(2)).toUpperCase(Locale.ROOT);
                String content = om.group(3).trim();
                block.options.put(key, new StringBuilder(content));
                block.lastOptionKey = key;
                matchedAny = true;
            }
        }
        return matchedAny;
    }

    private ParsedQuestion convertBlock(RawBlock block, int seqNo, List<String> warnings) {
        String title = block.title.toString().trim();
        String brief = briefOf(title, block.rawIndex);
        if (title.isEmpty()) {
            warnings.add("第 " + block.rawIndex + " 题：题干为空，已跳过");
            return null;
        }

        String rawAnswer = block.answer.toString().trim();
        if (rawAnswer.isEmpty()) {
            warnings.add("第 " + block.rawIndex + " 题（" + brief + "）：缺少答案，已跳过");
            return null;
        }

        int type = resolveType(block, title, rawAnswer);

        List<ParsedQuestion.ParsedOption> options = List.of();
        String answerJson;
        switch (type) {
            case 1 -> {
                if (block.options.size() < 2) {
                    warnings.add("第 " + block.rawIndex + " 题（" + brief + "）：单选题至少需要 2 个选项，已跳过");
                    return null;
                }
                List<String> letters = extractOptionLetters(rawAnswer);
                if (letters.size() != 1) {
                    warnings.add("第 " + block.rawIndex + " 题（" + brief + "）：单选题答案应为单个选项字母（实得 \""
                            + rawAnswer + "\"），已跳过");
                    return null;
                }
                String correctKey = letters.get(0);
                if (!block.options.containsKey(correctKey)) {
                    warnings.add("第 " + block.rawIndex + " 题（" + brief + "）：答案 " + correctKey
                            + " 不在选项列表 " + block.options.keySet() + " 中，已跳过");
                    return null;
                }
                options = buildOptions(block.options, Set.of(correctKey));
                answerJson = toJson(correctKey);
            }
            case 2 -> {
                if (block.options.size() < 2) {
                    warnings.add("第 " + block.rawIndex + " 题（" + brief + "）：多选题至少需要 2 个选项，已跳过");
                    return null;
                }
                Set<String> letterSet = new TreeSet<>(extractOptionLetters(rawAnswer));
                if (letterSet.isEmpty()) {
                    warnings.add("第 " + block.rawIndex + " 题（" + brief + "）：多选题未识别出有效选项字母（实得 \""
                            + rawAnswer + "\"），已跳过");
                    return null;
                }
                for (String key : letterSet) {
                    if (!block.options.containsKey(key)) {
                        warnings.add("第 " + block.rawIndex + " 题（" + brief + "）：答案 " + key
                                + " 不在选项列表 " + block.options.keySet() + " 中，已跳过");
                        return null;
                    }
                }
                options = buildOptions(block.options, letterSet);
                answerJson = toJson(new ArrayList<>(letterSet));
            }
            case 3 -> {
                Boolean judge = parseJudgeAnswer(rawAnswer);
                if (judge == null) {
                    warnings.add("第 " + block.rawIndex + " 题（" + brief + "）：判断题答案无法识别为对/错（实得 \""
                            + rawAnswer + "\"），已跳过");
                    return null;
                }
                answerJson = judge ? "true" : "false";
            }
            case 4 -> {
                List<Object> blanks = parseBlankAnswer(rawAnswer);
                if (blanks.isEmpty()) {
                    warnings.add("第 " + block.rawIndex + " 题（" + brief + "）：填空题答案为空，已跳过");
                    return null;
                }
                answerJson = toJson(blanks);
            }
            case 5 -> answerJson = toJson(rawAnswer);
            default -> {
                warnings.add("第 " + block.rawIndex + " 题（" + brief + "）：无法识别的题型，已跳过");
                return null;
            }
        }

        int difficulty = parseDifficulty(block.difficultyRaw);
        List<String> tags = parseTags(block.tagsRaw);
        BigDecimal score = parseScore(block.scoreRaw, block.rawIndex, brief, warnings);
        String analysis = block.analysis.toString().trim();

        return new ParsedQuestion(
                seqNo,
                block.sectionTitle,
                type,
                title,
                options,
                answerJson,
                analysis,
                difficulty,
                tags,
                score
        );
    }

    private int resolveType(RawBlock block, String title, String rawAnswer) {
        if (block.explicitType != null) {
            return block.explicitType;
        }
        // 有选项优先按选项 + 答案字母个数判定单选/多选
        if (block.options.size() >= 2) {
            List<String> letters = extractOptionLetters(rawAnswer);
            if (letters.size() > 1) {
                return 2;
            }
            if (letters.size() == 1) {
                return 1;
            }
        }
        // 其次沿用所属章节大标题的题型提示（如 "# 三、判断题"）
        if (block.sectionTypeHint != null) {
            return block.sectionTypeHint;
        }
        // 自动推断无选项题型
        if (parseJudgeAnswer(rawAnswer) != null) {
            return 3;
        }
        if (BLANK_SLOT.matcher(title).find() || rawAnswer.contains("|") || rawAnswer.contains("；")) {
            return 4;
        }
        return 5;
    }

    private static List<ParsedQuestion.ParsedOption> buildOptions(
            LinkedHashMap<String, StringBuilder> rawOptions, Set<String> correctKeys) {
        List<ParsedQuestion.ParsedOption> out = new ArrayList<>(rawOptions.size());
        for (Map.Entry<String, StringBuilder> entry : rawOptions.entrySet()) {
            String key = entry.getKey();
            out.add(new ParsedQuestion.ParsedOption(key, entry.getValue().toString().trim(),
                    correctKeys.contains(key)));
        }
        return out;
    }

    private static List<String> extractOptionLetters(String raw) {
        String cleaned = raw.trim().toUpperCase(Locale.ROOT);
        // 去掉常见分隔符后若全为 A-H 字母，则逐字母提取（支持 "AC" / "A, C" / "A、C" / "A C"）
        String compact = cleaned.replaceAll("[\\s,，、;；./|()（）\\[\\]【】]+", "");
        if (!compact.isEmpty() && compact.matches("[A-H]+")) {
            List<String> letters = new ArrayList<>(compact.length());
            Set<String> seen = new LinkedHashSet<>();
            for (char c : compact.toCharArray()) {
                String k = String.valueOf(c);
                if (seen.add(k)) {
                    letters.add(k);
                }
            }
            return letters;
        }
        return List.of();
    }

    static Boolean parseJudgeAnswer(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "true", "t", "对", "正确", "是", "√", "yes", "y", "1" -> Boolean.TRUE;
            case "false", "f", "错", "错误", "否", "×", "x", "no", "n", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static List<Object> parseBlankAnswer(String raw) {
        String[] parts;
        if (raw.contains("|") || raw.contains("；") || raw.contains(";")) {
            parts = raw.split("\\s*[|；;]\\s*");
        } else {
            parts = new String[]{raw};
        }
        List<Object> slots = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.contains("/")) {
                List<String> alts = new ArrayList<>();
                for (String alt : trimmed.split("/")) {
                    String a = alt.trim();
                    if (!a.isEmpty()) {
                        alts.add(a);
                    }
                }
                if (alts.size() > 1) {
                    slots.add(alts);
                } else if (alts.size() == 1) {
                    slots.add(alts.get(0));
                }
            } else {
                slots.add(trimmed);
            }
        }
        return slots;
    }

    private static int parseDifficulty(String raw) {
        if (raw == null || raw.isBlank()) {
            return 2;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (t.equals("1") || t.contains("简单") || t.contains("易") || t.equals("easy")) {
            return 1;
        }
        if (t.equals("3") || t.contains("困难") || t.contains("难") || t.equals("hard")) {
            return 3;
        }
        return 2;
    }

    private static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String token : raw.split("[,，、;；\\s]+")) {
            String t = token.trim();
            if (!t.isEmpty()) {
                tags.add(t.length() > 32 ? t.substring(0, 32) : t);
            }
        }
        return new ArrayList<>(tags);
    }

    private static BigDecimal parseScore(String raw, int rawIndex, String brief, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.replace("分", "").trim();
        try {
            BigDecimal val = new BigDecimal(cleaned);
            if (val.compareTo(BigDecimal.ZERO) > 0 && val.compareTo(new BigDecimal("1000")) <= 0) {
                return val;
            }
            warnings.add("第 " + rawIndex + " 题（" + brief + "）：分值 \"" + raw + "\" 超出范围，已忽略并按默认分值处理");
            return null;
        } catch (NumberFormatException e) {
            warnings.add("第 " + rawIndex + " 题（" + brief + "）：分值 \"" + raw + "\" 格式非法，已忽略");
            return null;
        }
    }

    private static String detectSectionHeader(String trimmed) {
        Matcher md = SECTION_MD.matcher(trimmed);
        if (md.matches()) {
            return stripMarkdownBold(md.group(1).trim());
        }
        Matcher cn = SECTION_CN.matcher(trimmed);
        if (cn.matches() && trimmed.length() <= 40) {
            return stripMarkdownBold(trimmed);
        }
        return null;
    }

    private static Integer parseTypeName(String raw) {
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("单选") || t.equals("single")) {
            return 1;
        }
        if (t.startsWith("多选") || t.equals("multi")) {
            return 2;
        }
        if (t.startsWith("判断") || t.equals("judge")) {
            return 3;
        }
        if (t.startsWith("填空") || t.equals("blank")) {
            return 4;
        }
        if (t.startsWith("简答") || t.startsWith("问答") || t.startsWith("论述") || t.equals("short")) {
            return 5;
        }
        return null;
    }

    private static Integer inferTypeFromText(String sectionTitle) {
        if (sectionTitle == null) {
            return null;
        }
        String t = sectionTitle.toLowerCase(Locale.ROOT);
        if (t.contains("单选")) {
            return 1;
        }
        if (t.contains("多选")) {
            return 2;
        }
        if (t.contains("判断")) {
            return 3;
        }
        if (t.contains("填空")) {
            return 4;
        }
        if (t.contains("简答") || t.contains("问答") || t.contains("论述")) {
            return 5;
        }
        return null;
    }

    private static String stripMarkdownBold(String s) {
        return s.replaceAll("^\\*\\*(.+)\\*\\*$", "$1").trim();
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(line);
    }

    private static String briefOf(String title, int rawIndex) {
        if (title == null || title.isBlank()) {
            return "无题干#" + rawIndex;
        }
        String flat = title.replaceAll("\\s+", " ").trim();
        return flat.length() <= 16 ? flat : flat.substring(0, 15) + "…";
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化题目答案失败", e);
        }
    }
}
