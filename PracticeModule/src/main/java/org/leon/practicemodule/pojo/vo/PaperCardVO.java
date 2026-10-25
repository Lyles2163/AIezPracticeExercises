package org.leon.practicemodule.pojo.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 真题套卷卡片（spec 5.6 的 PaperCardVO，字段一个不多一个不少）。
 *
 * <p>三个徽章原值（{@code examYear}/{@code examMonth}/{@code setNo}）与拼好的
 * {@code badges} 同时给：原值让前端能按年份二次排序或做筛选，拼好的让渲染不用造中文序数。
 *
 * <p>本 VO 没有任何答案字段——守卫用例（{@code AnswerStateResponseGuardTest}）
 * 拿真实响应字节盯这一点。
 */
@Data
public class PaperCardVO {

    private Long paperId;
    private String title;
    private Long subjectId;
    private String subjectName;

    private Integer examYear;
    private Integer examMonth;
    private Integer setNo;

    /** 后端拼好的中文徽章，顺序固定：年、套、月、时长 */
    private List<String> badges;

    private Integer durationMinutes;
    private BigDecimal totalScore;
    private Integer questionCount;

    /** 已提交次数：只数终态 attempt（段 4 裁定三） */
    private long submitCount;
    private int answeredCount;
    private double progressPercent;

    /** 最新一次作答（草稿也算最新）——"继续考试"按钮的入口 */
    private Long latestAttemptId;
    private Integer latestAttemptStatus;
}
