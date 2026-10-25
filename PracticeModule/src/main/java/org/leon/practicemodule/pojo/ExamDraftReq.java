package org.leon.practicemodule.pojo;

import lombok.Data;

import java.util.Map;

/**
 * 存草稿请求（spec 5.6）。
 *
 * <p>{@code answers} 的形态与 {@code answered_json} 一致：key 是题号的十进制字符串，
 * value 是<b>整份 JSON 答案</b>的文本（{@code "\"A\""}、{@code "[\"A\",\"B\"]"}、{@code "true"}）。
 * 前端把这道题的作答原样交回来，不在客户端做任何包装——包装规则只在
 * {@code ExamAnswers.encode} 一处（裁定五）。
 *
 * <p>{@code answers} 允许 null：当作空 map，即"清空草稿"。
 */
@Data
public class ExamDraftReq {

    private Long attemptId;
    private Map<String, String> answers;
}
