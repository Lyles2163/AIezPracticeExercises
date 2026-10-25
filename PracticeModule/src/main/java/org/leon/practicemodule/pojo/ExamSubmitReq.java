package org.leon.practicemodule.pojo;

import lombok.Data;

/**
 * 交卷请求（spec 5.6）。
 *
 * <p>只有一个字段是刻意的：交卷的内容<b>早就在 {@code answered_json} 里</b>（由 {@code exam/draft}
 * 整包覆盖写好），提交动作本身不再携带答案。若这里再收一份答案，就会出现"草稿与提交内容不一致"
 * 这种没人能解释的状态——以哪份为准都得重新设计。
 */
@Data
public class ExamSubmitReq {

    private Long attemptId;
}
