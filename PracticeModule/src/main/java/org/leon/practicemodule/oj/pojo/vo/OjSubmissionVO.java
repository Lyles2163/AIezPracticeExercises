package org.leon.practicemodule.oj.pojo.vo;

import org.leon.practicemodule.oj.pojo.OjSubmission;

import java.io.Serializable;
import java.util.Date;

/**
 * 轮询返回体。只回判定结果不回源码：前端列表不需要，回源码反而让响应体变大。
 * status 是 PENDING/RUNNING 时表示还没判完，前端应继续轮询。
 */
public record OjSubmissionVO(Long id,
                             String status,
                             Integer score,
                             Integer total,
                             Integer timeUsedMs,
                             String errorMsg,
                             Date createTime) implements Serializable {

    public static OjSubmissionVO from(OjSubmission s) {
        return new OjSubmissionVO(s.getId(), s.getStatus(), s.getScore(), s.getTotal(),
                s.getTimeUsedMs(), s.getErrorMsg(), s.getCreateTime());
    }
}
