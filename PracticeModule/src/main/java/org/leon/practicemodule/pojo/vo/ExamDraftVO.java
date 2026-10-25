package org.leon.practicemodule.pojo.vo;

import lombok.Data;

/**
 * 存草稿的响应（spec 5.6 的 {@code exam/draft}）。
 *
 * <p>{@code savedAt} 是服务端 epoch 毫秒（与 {@code ExamStartVO.serverNow} 同一个口径）：
 * 前端每 30 秒自动存一次，靠它对齐"最后一次成功保存"，而不是靠请求发出时间——
 * 网络慢的时候两者差一整个轮次。
 */
@Data
public class ExamDraftVO {

    private long savedAt;
    private Integer answeredCount;
    private double progressPercent;
}
