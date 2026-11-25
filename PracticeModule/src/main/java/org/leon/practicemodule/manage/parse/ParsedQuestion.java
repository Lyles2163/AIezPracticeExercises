package org.leon.practicemodule.manage.parse;

import java.math.BigDecimal;
import java.util.List;

/**
 * 规则解析出的一道规范化题目。
 * answerJson 已按 AnswerGrader 口径序列化为单值合法 JSON，可直接落库。
 */
public record ParsedQuestion(
        int seqNo,
        String sectionTitle,
        int type,
        String title,
        List<ParsedOption> options,
        String answerJson,
        String analysis,
        int difficulty,
        List<String> tags,
        BigDecimal score
) {
    public record ParsedOption(String optionKey, String content, boolean correct) {
    }
}
