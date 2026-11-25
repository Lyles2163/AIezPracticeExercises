package org.leon.practicemodule.manage.parse;

import java.util.List;

/** 整份文档的规则解析结果：合法题目列表 + 被跳过/修正的警告明细。 */
public record ParseResult(
        List<ParsedQuestion> questions,
        List<String> warnings,
        int totalBlocks
) {
}
