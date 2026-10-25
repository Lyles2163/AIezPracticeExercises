package org.leon.practicemodule.pojo.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 开考响应（spec 5.6 的 {@code exam/start}）：三样东西一次给全，答题页拿到就能渲染——
 * ① 倒计时原料（serverNow / deadlineAt / remainingMs，只信服务端）；
 * ② 卷面骨架（答题卡网格与 Part 标题）；
 * ③ 已存草稿（刷新后接着写）。
 *
 * <p>{@code answeredMap} 的 key 是<b>十进制题号字符串</b>（裁定五）：JSON 对象没有数字 key，
 * 在这里把它原样交给前端比"转成 Long 再指望 Jackson 写出数字 key"诚实。
 *
 * <p>{@code structure} 复用 {@link PaperDetailVO.PartVO} 而不是再造一个类型：
 * 答题卡骨架在 {@code paper/detail} 与 {@code exam/start} 必须长得一模一样，
 * 两个类型就有两份字段清单，迟早漂。共用的是出网形态，不是可变实体。
 */
@Data
public class ExamStartVO {

    private Long attemptId;
    private Integer seqNo;
    private Long paperId;
    private String title;

    /** epoch 毫秒 */
    private long serverNow;
    private long deadlineAt;
    private long remainingMs;
    private Integer durationMinutes;

    private Integer questionCount;
    private Integer answeredCount;
    private double progressPercent;
    private BigDecimal totalScore;

    private List<PaperDetailVO.PartVO> structure;
    private Map<String, String> answeredMap;
}
