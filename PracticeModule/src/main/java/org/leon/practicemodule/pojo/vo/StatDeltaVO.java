package org.leon.practicemodule.pojo.vo;

import lombok.Data;

/**
 * 提交后的累计快照（不是增量）：前端一次调用即可渲染"本科目共答 X 题、正确率 Y%"。
 * accuracy 复用 SubjectServiceImpl.accuracy 的两位小数口径，与 subject/list 的 myAccuracy 同源。
 */
@Data
public class StatDeltaVO {
    private Integer total;
    private Integer correct;
    private Integer wrong;
    private Double accuracy;
}