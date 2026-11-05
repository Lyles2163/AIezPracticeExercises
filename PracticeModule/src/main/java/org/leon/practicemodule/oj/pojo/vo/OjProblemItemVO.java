package org.leon.practicemodule.oj.pojo.vo;

import java.io.Serializable;

/**
 * 题目列表的一行。
 * <p>
 * 提交/通过统计是"提交通过率"口径：acceptedCount / submitCount，
 * 和参考站点一致（同一人多次 AC 各算一次），不是"通过的独立人数"。
 */
public record OjProblemItemVO(Long id,
                              String title,
                              Integer difficulty,
                              Integer timeLimitMs,
                              Integer memoryLimitMb,
                              Long submitCount,
                              Long acceptedCount,
                              Double acceptRate) implements Serializable {
}
