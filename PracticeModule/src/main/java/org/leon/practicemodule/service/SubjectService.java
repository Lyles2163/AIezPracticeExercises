package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.vo.SubjectItemVO;

import java.util.List;

public interface SubjectService {

    /** 启用中的科目 + 当前用户在每个科目下的进度 */
    List<SubjectItemVO> listSubjects(Long userId);
}
