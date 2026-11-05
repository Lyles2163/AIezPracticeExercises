package org.leon.practicemodule.oj.pojo.vo;

import java.io.Serializable;
import java.util.List;

/** 题目详情。samples 只含示例用例，统计是全站提交次数口径。 */
public record OjProblemVO(Long id,
                          Long subjectId,
                          String title,
                          String description,
                          String templateCode,
                          Integer difficulty,
                          Integer timeLimitMs,
                          Integer memoryLimitMb,
                          Integer maxSourceBytes,
                          Long submitCount,
                          Long acceptedCount,
                          Double acceptRate,
                          List<OjTestcaseSampleVO> samples) implements Serializable {
}
