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

    public static QbSubject requireEnabled(QbSubjectMapper mapper, Long subjectId) {
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
        return subject;
    }

    /**
     * 读路径科目守卫：存在 + 启用 + 可见（公共科目 owner_id=1 或当前用户本人科目）。
     * 与 PaperLoad.loadVisible 同口径：ownerId 为 null（仅旧单测夹具）时不误杀。
     */
    public static QbSubject requireVisible(QbSubjectMapper mapper, Long userId, Long subjectId) {
        QbSubject subject = requireEnabled(mapper, subjectId);
        if (subject.getOwnerId() != null
                && subject.getOwnerId() != PaperLoad.PUBLIC_OWNER
                && !subject.getOwnerId().equals(userId)) {
            throw new PracticeException(PracticeErrorCode.FORBIDDEN, "无权访问该科目");
        }
        return subject;
    }

    /**
     * 写路径科目守卫：存在 + 必须归属当前用户且不能是公共科目（owner_id=1）。
     * 停用中的个人科目仍允许管理页编辑/启用/删除，因此不限制 status=1。
     */
    public static QbSubject requireOwnedPersonal(QbSubjectMapper mapper, Long userId, Long subjectId) {
        if (subjectId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        QbSubject subject = mapper.selectById(subjectId);
        if (subject == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "科目不存在");
        }
        if (subject.getOwnerId() == null
                || subject.getOwnerId() == PaperLoad.PUBLIC_OWNER
                || !subject.getOwnerId().equals(userId)) {
            throw new PracticeException(PracticeErrorCode.FORBIDDEN, "只能操作自己的个人科目");
        }
        return subject;
    }
}
