package org.leon.practicemodule.oj.pojo.vo;

import java.io.Serializable;

/** 提交后立刻返回的编号，前端据此轮询结果。 */
public record OjSubmitVO(Long submissionId) implements Serializable {
}
