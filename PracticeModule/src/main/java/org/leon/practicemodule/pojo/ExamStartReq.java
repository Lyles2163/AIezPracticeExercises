package org.leon.practicemodule.pojo;

import lombok.Data;

/**
 * 开考请求（spec 5.6）。
 *
 * <p>{@code forceNew} 用包装 Boolean：缺省（null）的语义是"续做当前草稿"，
 * 用 {@code boolean} 就无法区分"没传"与"传了 false"——而后者是前端明确的"不要新开"。
 */
@Data
public class ExamStartReq {

    private Long paperId;
    private Boolean forceNew;
}
