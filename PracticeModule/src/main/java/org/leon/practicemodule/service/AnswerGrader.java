package org.leon.practicemodule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 两个阶段唯一的判分口径，只认 qb_question.answer_json（spec 5.3）。
 * 永不读 qb_question_option.is_correct——它与 answer_json 是同一事实的两份拷贝，
 * 认了后者，前者就是无人校验的死数据（spec 5.5）。
 *
 * 不注入任何 bean，单测直接 new；答案格式与题型不符一律 40000，不落库。
 */
@Service
public class AnswerGrader {

    /** correct=是否答对；needSelfJudge=简答题不自动判分，等 self-judge 回填 */
    public record GradeResult(boolean correct, boolean needSelfJudge) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    public GradeResult grade(int type, String answerJson, String userAnswerJson, List<String> optionKeys) {
        return switch (type) {
            case 1 -> new GradeResult(gradeSingle(answerJson, userAnswerJson, optionKeys), false);
            case 2 -> new GradeResult(gradeMulti(answerJson, userAnswerJson), false);
            case 3 -> new GradeResult(gradeJudge(answerJson, userAnswerJson), false);
            case 4 -> new GradeResult(gradeBlank(answerJson, userAnswerJson), false);
            case 5 -> {
                // 不判内容，但必须验语法：qb_user_answer_record.user_answer_json 是 MySQL json 列，
                // 非法 JSON 落库时直接抛 DataIntegrityViolation（端到端实测），只能在这里挡成 40000
                parse(userAnswerJson);
                yield new GradeResult(false, true);
            }
            default -> throw malformed("未知题型 " + type + "，无法判分");
        };
    }

    private boolean gradeSingle(String answerJson, String userAnswerJson, List<String> optionKeys) {
        String correct = readText(answerJson).trim();
        String user = readText(userAnswerJson).trim();
        if (!optionKeys.isEmpty() && !optionKeys.contains(user.toUpperCase(Locale.ROOT))) {
            throw malformed("答案不在选项范围内");
        }
        return correct.equalsIgnoreCase(user);
    }

    private boolean gradeMulti(String answerJson, String userAnswerJson) {
        Set<String> correct = letterSet(readArray(answerJson));
        Set<String> user = letterSet(readArray(userAnswerJson));
        return !user.isEmpty() && correct.equals(user);
    }

    private boolean gradeJudge(String answerJson, String userAnswerJson) {
        return readBool(answerJson) == readBool(userAnswerJson);
    }

    private boolean gradeBlank(String answerJson, String userAnswerJson) {
        JsonNode correct = parse(answerJson);
        JsonNode user = parse(userAnswerJson);
        if (!correct.isArray() || !user.isArray()) {
            throw malformed("答案格式与题型不匹配");
        }
        if (correct.size() != user.size()) {
            return false;
        }
        for (int i = 0; i < correct.size(); i++) {
            JsonNode userSlot = user.get(i);
            if (!userSlot.isTextual()) {
                throw malformed("答案格式与题型不匹配");
            }
            if (!slotHit(correct.get(i), normalize(userSlot.textValue()))) {
                return false;
            }
        }
        return true;
    }

    /** 正确答案某一空可以是单串，也可以是"任一命中"数组（spec 5.3 type=4） */
    private boolean slotHit(JsonNode correctSlot, String normalizedUser) {
        if (correctSlot.isTextual()) {
            return normalize(correctSlot.textValue()).equals(normalizedUser);
        }
        if (correctSlot.isArray()) {
            for (JsonNode alt : correctSlot) {
                if (alt.isTextual() && normalize(alt.textValue()).equals(normalizedUser)) {
                    return true;
                }
            }
            return false;
        }
        throw malformed("题库答案格式异常");
    }

    private Set<String> letterSet(List<String> letters) {
        Set<String> set = new TreeSet<>();
        for (String letter : letters) {
            set.add(letter.trim().toUpperCase(Locale.ROOT));
        }
        return set;
    }

    private String readText(String json) {
        JsonNode node = parse(json);
        if (!node.isTextual()) {
            throw malformed("答案格式与题型不匹配");
        }
        return node.textValue();
    }

    private boolean readBool(String json) {
        JsonNode node = parse(json);
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            String t = node.textValue().trim();
            if ("true".equalsIgnoreCase(t)) {
                return true;
            }
            if ("false".equalsIgnoreCase(t)) {
                return false;
            }
        }
        throw malformed("答案格式与题型不匹配");
    }

    private List<String> readArray(String json) {
        JsonNode node = parse(json);
        if (!node.isArray()) {
            throw malformed("答案格式与题型不匹配");
        }
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw malformed("答案格式与题型不匹配");
            }
            out.add(item.textValue());
        }
        return out;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            throw malformed("答案不能为空");
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || node.isMissingNode()) {
                throw malformed("答案不是合法 JSON");
            }
            return node;
        } catch (PracticeException e) {
            throw e;
        } catch (Exception e) {
            // answer_json 是 json 列，解析失败只可能来自请求侧的 user_answer_json
            throw malformed("答案不是合法 JSON");
        }
    }

    /** 去首尾空白 + 全角转半角 + 忽略大小写，只用于填空题（spec 5.3 type=4） */
    static String normalize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char ch : raw.toCharArray()) {
            if (ch >= '\uFF01' && ch <= '\uFF5E') {
                sb.append((char) (ch - 0xFEE0));
            } else if (ch == '\u3000') {
                sb.append(' ');
            } else {
                sb.append(ch);
            }
        }
        return sb.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static PracticeException malformed(String msg) {
        return new PracticeException(PracticeErrorCode.BAD_REQUEST, msg);
    }
}