package org.leon.practicemodule.manage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.manage.parse.ParsedQuestion;
import org.leon.practicemodule.manage.pojo.ManageRequests;
import org.leon.practicemodule.service.AnswerGrader;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 手动新增/编辑个人题目时的严格校验与规范化器：
 * 确保写入 qb_question.answer_json 的值 100% 符合 {@link AnswerGrader} 判分口径，
 * 并同步计算每个选项的 is_correct 标记。
 */
@Component
public class QuestionPayloadValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record NormalizedQuestion(
            int type,
            String title,
            List<ParsedQuestion.ParsedOption> options,
            String answerJson,
            String analysis,
            int difficulty,
            String tagsJson,
            int status
    ) {
    }

    private final AnswerGrader grader;

    public QuestionPayloadValidator(AnswerGrader grader) {
        this.grader = grader;
    }

    public NormalizedQuestion validateAndNormalize(ManageRequests.QuestionSaveReq req) {
        if (req == null) {
            throw badRequest("请求体不能为空");
        }
        Integer type = req.getType();
        if (type == null || type < 1 || type > 5) {
            throw badRequest("题型必须是 1单选 / 2多选 / 3判断 / 4填空 / 5简答");
        }
        String title = req.getTitle() == null ? "" : req.getTitle().trim();
        if (title.isEmpty()) {
            throw badRequest("题干不能为空");
        }
        String answerJson = req.getAnswerJson() == null ? "" : req.getAnswerJson().trim();
        if (answerJson.isEmpty()) {
            throw badRequest("正确答案不能为空");
        }

        List<ParsedQuestion.ParsedOption> normalizedOptions = List.of();
        String canonicalAnswerJson;

        if (type == 1 || type == 2) {
            LinkedHashMap<String, String> optMap = normalizeOptions(req.getOptions());
            if (optMap.size() < 2) {
                throw badRequest("选择题至少需要 2 个有效选项");
            }
            Set<String> correctKeys = extractAndVerifyChoiceAnswers(type, answerJson, optMap.keySet());
            List<ParsedQuestion.ParsedOption> opts = new ArrayList<>(optMap.size());
            for (Map.Entry<String, String> entry : optMap.entrySet()) {
                opts.add(new ParsedQuestion.ParsedOption(
                        entry.getKey(), entry.getValue(), correctKeys.contains(entry.getKey())));
            }
            normalizedOptions = opts;
            canonicalAnswerJson = type == 1
                    ? toJson(correctKeys.iterator().next())
                    : toJson(new ArrayList<>(correctKeys));
        } else {
            canonicalAnswerJson = verifyNonChoiceAnswer(type, answerJson);
        }

        int difficulty = req.getDifficulty() == null ? 2 : req.getDifficulty();
        if (difficulty < 1 || difficulty > 3) {
            throw badRequest("难度只能是 1简单 / 2中等 / 3困难");
        }
        int status = req.getStatus() == null ? 1 : req.getStatus();
        if (status != 0 && status != 1) {
            throw badRequest("状态只能是 1启用 或 0停用");
        }

        List<String> cleanTags = new ArrayList<>();
        if (req.getTags() != null) {
            Set<String> seen = new LinkedHashSet<>();
            for (String tag : req.getTags()) {
                if (tag != null && !tag.isBlank()) {
                    String t = tag.trim();
                    seen.add(t.length() > 32 ? t.substring(0, 32) : t);
                }
            }
            cleanTags.addAll(seen);
        }

        String analysis = req.getAnalysis() == null ? "" : req.getAnalysis().trim();
        return new NormalizedQuestion(
                type,
                title,
                normalizedOptions,
                canonicalAnswerJson,
                analysis,
                difficulty,
                toJson(cleanTags),
                status
        );
    }

    private LinkedHashMap<String, String> normalizeOptions(List<ManageRequests.OptionReq> options) {
        if (options == null || options.isEmpty()) {
            throw badRequest("选择题必须提供选项");
        }
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (ManageRequests.OptionReq opt : options) {
            if (opt == null || opt.getOptionKey() == null || opt.getOptionKey().isBlank()) {
                throw badRequest("选项标号不能为空");
            }
            String key = opt.getOptionKey().trim().toUpperCase(Locale.ROOT);
            if (!key.matches("[A-H]")) {
                throw badRequest("选项标号只允许 A~H，收到：" + key);
            }
            String content = opt.getContent() == null ? "" : opt.getContent().trim();
            if (content.isEmpty()) {
                throw badRequest("选项 " + key + " 的内容不能为空");
            }
            if (map.containsKey(key)) {
                throw badRequest("存在重复的选项标号：" + key);
            }
            map.put(key, content);
        }
        return map;
    }

    private Set<String> extractAndVerifyChoiceAnswers(int type, String answerJson, Set<String> validKeys) {
        JsonNode node = parseStrictJson(answerJson);
        if (type == 1) {
            if (!node.isTextual()) {
                throw badRequest("单选题答案必须是选项字母 JSON 字符串，如 \"A\"");
            }
            String key = node.textValue().trim().toUpperCase(Locale.ROOT);
            if (!validKeys.contains(key)) {
                throw badRequest("单选题答案 " + key + " 不在选项列表 " + validKeys + " 中");
            }
            // 用 AnswerGrader 再跑一次自检
            grader.grade(1, toJson(key), toJson(key), new ArrayList<>(validKeys));
            return Set.of(key);
        } else {
            if (!node.isArray() || node.isEmpty()) {
                throw badRequest("多选题答案必须是至少含 1 个字母的 JSON 数组，如 [\"A\",\"C\"]");
            }
            Set<String> keys = new LinkedHashSet<>();
            for (JsonNode item : node) {
                if (!item.isTextual()) {
                    throw badRequest("多选题答案元素必须是选项字母字符串");
                }
                String k = item.textValue().trim().toUpperCase(Locale.ROOT);
                if (!validKeys.contains(k)) {
                    throw badRequest("多选题答案 " + k + " 不在选项列表 " + validKeys + " 中");
                }
                keys.add(k);
            }
            List<String> sorted = keys.stream().sorted().toList();
            String json = toJson(sorted);
            grader.grade(2, json, json, List.of());
            return new LinkedHashSet<>(sorted);
        }
    }

    private String verifyNonChoiceAnswer(int type, String answerJson) {
        JsonNode node = parseStrictJson(answerJson);
        return switch (type) {
            case 3 -> {
                if (node.isBoolean()) {
                    yield node.booleanValue() ? "true" : "false";
                }
                throw badRequest("判断题答案必须是 JSON 布尔值 true 或 false");
            }
            case 4 -> {
                if (!node.isArray() || node.isEmpty()) {
                    throw badRequest("填空题答案必须是非空 JSON 数组，如 [\"JVM\",\"GC\"]");
                }
                for (JsonNode slot : node) {
                    if (slot.isTextual()) {
                        if (slot.textValue().isBlank()) {
                            throw badRequest("填空题答案不能含空白项");
                        }
                    } else if (slot.isArray() && !slot.isEmpty()) {
                        for (JsonNode alt : slot) {
                            if (!alt.isTextual() || alt.textValue().isBlank()) {
                                throw badRequest("填空题同义答案必须为非空字符串");
                            }
                        }
                    } else {
                        throw badRequest("填空题每个空必须是字符串或同义词字符串数组");
                    }
                }
                yield toJson(node);
            }
            case 5 -> {
                if (!node.isTextual() || node.textValue().isBlank()) {
                    throw badRequest("简答题答案必须是非空 JSON 字符串");
                }
                yield toJson(node.textValue().trim());
            }
            default -> throw badRequest("不支持的题型");
        };
    }

    private JsonNode parseStrictJson(String json) {
        try {
            // 借用 AnswerGrader 的单值 JSON 语法校验（通过简答题分支验证整份单值 + 有限数）
            grader.grade(5, "\"ok\"", json, List.of());
            return MAPPER.readTree(json);
        } catch (PracticeException e) {
            throw e;
        } catch (Exception e) {
            throw badRequest("答案不是合法 JSON");
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private static PracticeException badRequest(String msg) {
        return new PracticeException(PracticeErrorCode.BAD_REQUEST, msg);
    }
}
