package org.leon.practicemodule.service;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.pojo.QbSubject;

/**
 * 科目存在且启用的校验。第三个读接口出现时才抽出来（规则三次）——
 * 之前两处各写一遍是巧合，三遍就是复制，而复制的校验迟早分叉。
 */
public final class SubjectGuards {

    private SubjectGuards() {
    }

    public static void requireEnabled(QbSubjectMapper mapper, Long subjectId) {
        if (subjectId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        QbSubject subject = mapper.selectById(subjectId);
        if (subject == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "科目不存在");
        }
        if (subject.getStatus() == null || subject.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "科目已停用");
        }
    }
}
