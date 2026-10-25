package org.leon.practicemodule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * structure_json → {@link PaperStructure} 的唯一入口。
 *
 * <p>为什么不用 {@code ObjectMapper.readValue(json, PaperStructure.class)}：record 绑不上时
 * 会把我本来就想要发现的"少写了一个字段"变成 {@code null}，而 null 的 score 到了
 * {@link PaperStructure#sumQuestionScore()} 就是 NPE，null 的 minutes 到了规则 4 会被拆箱炸掉。
 * 段 2 已经为"解析器比存储宽松"栽过一次（Jackson 认 {@code "A" "B"} 与 {@code 1e999}，
 * MySQL 的 json 列都不认）。所以这里逐字段显式取：缺什么、类型错什么、在第几个
 * part/section/题，全部写进消息。
 *
 * <p>错误码一律 40000：structure_json 是人手写的组卷数据，出错属于"输入非法"，不是服务器故障。
 */
public final class PaperStructureParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PaperStructureParser() {
    }

    public static PaperStructure parse(String structureJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(structureJson);
        } catch (Exception e) {
            throw bad("structure_json 不是合法 JSON：" + firstLine(e.getMessage()));
        }
        if (root == null || !root.isObject()) {
            throw bad("structure_json 必须是 JSON 对象，顶层含非空 parts 数组");
        }
        JsonNode parts = root.get("parts");
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            throw bad("structure_json 的 parts 必须是非空数组");
        }
        List<PaperStructure.Part> outParts = new ArrayList<>(parts.size());
        for (int pi = 0; pi < parts.size(); pi++) {
            String pLoc = "parts[" + pi + "]";
            JsonNode pNode = parts.get(pi);
            outParts.add(new PaperStructure.Part(
                    requireText(pNode, "roman", pLoc),
                    requireText(pNode, "name", pLoc),
                    requireText(pNode, "cardLabel", pLoc),
                    requireDecimal(pNode, "score", pLoc),
                    requireLong(pNode, "minutes", pLoc),
                    parseSections(pNode, pLoc)));
        }
        return new PaperStructure(List.copyOf(outParts));
    }

    private static List<PaperStructure.Section> parseSections(JsonNode pNode, String pLoc) {
        JsonNode sections = pNode.get("sections");
        if (sections == null || !sections.isArray() || sections.isEmpty()) {
            throw bad(pLoc + " 的 sections 必须是非空数组");
        }
        List<PaperStructure.Section> out = new ArrayList<>(sections.size());
        for (int si = 0; si < sections.size(); si++) {
            String sLoc = pLoc + ".sections[" + si + "]";
            JsonNode sNode = sections.get(si);
            out.add(new PaperStructure.Section(
                    requireText(sNode, "label", sLoc),
                    requireText(sNode, "subTitle", sLoc),
                    optionalText(sNode, "direction", sLoc),
                    optionalText(sNode, "audioUrl", sLoc),
                    optionalText(sNode, "originalText", sLoc),
                    parseQuestions(sNode, sLoc)));
        }
        return List.copyOf(out);
    }

    private static List<PaperStructure.QuestionRef> parseQuestions(JsonNode sNode, String sLoc) {
        JsonNode questions = sNode.get("questions");
        if (questions == null || !questions.isArray() || questions.isEmpty()) {
            throw bad(sLoc + " 的 questions 必须是非空数组（不带题的 section 渲染不出答题卡格子）");
        }
        List<PaperStructure.QuestionRef> out = new ArrayList<>(questions.size());
        for (int qi = 0; qi < questions.size(); qi++) {
            String qLoc = sLoc + ".questions[" + qi + "]";
            JsonNode qNode = questions.get(qi);
            out.add(new PaperStructure.QuestionRef(
                    (int) requireLong(qNode, "index", qLoc),
                    requireLong(qNode, "questionId", qLoc),
                    requireDecimal(qNode, "score", qLoc)));
        }
        return List.copyOf(out);
    }

    private static String requireText(JsonNode node, String field, String loc) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw bad(loc + " 缺 " + field + "（必须是非空字符串）");
        }
        return v.asText();
    }

    /** 可空字段：缺键或显式 null 都接受；给了别的类型（数字、数组）是写错了，要拦 */
    private static String optionalText(JsonNode node, String field, String loc) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw bad(loc + " 的 " + field + " 只能是 null 或字符串");
        }
        return v.asText();
    }

    private static long requireLong(JsonNode node, String field, String loc) {
        JsonNode v = node.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong()) {
            throw bad(loc + " 的 " + field + " 必须是整数");
        }
        return v.asLong();
    }

    /**
     * 分值必须是 JSON 数字。取 {@code new BigDecimal(v.asText())} 而不是 {@code v.decimalValue()}：
     * 数字节点的 asText 就是它写在 JSON 里的那段字面量，转成 BigDecimal 不经过二进制浮点，
     * 7.00 就是 7.00。规则 3 是 compareTo 且容差 0，靠的就是这条精确性。
     * （本仓库的 Jackson 版本没有 {@code isBigDecimalNode()}，实测编译不到；
     * 分"整数/小数"支路在这里也没意义，故只判 isNumber。）
     */
    private static BigDecimal requireDecimal(JsonNode node, String field, String loc) {
        JsonNode v = node.get(field);
        if (v == null || !v.isNumber()) {
            throw bad(loc + " 的 " + field + " 必须是 JSON 数字");
        }
        try {
            return new BigDecimal(v.asText());
        } catch (NumberFormatException e) {
            throw bad(loc + " 的 " + field + " 不是可用的十进制数字：" + v.asText());
        }
    }

    private static PracticeException bad(String msg) {
        return new PracticeException(PracticeErrorCode.BAD_REQUEST, msg);
    }

    private static String firstLine(String msg) {
        if (msg == null) {
            return "（无消息）";
        }
        int nl = msg.indexOf('\n');
        return nl < 0 ? msg : msg.substring(0, nl);
    }
}
