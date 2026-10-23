package org.leon.practicemodule.service.impl;

import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserSubjectStatMapper;
import org.leon.practicemodule.mapper.StatCountRow;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.pojo.QbUserSubjectStat;
import org.leon.practicemodule.pojo.vo.SubjectItemVO;
import org.leon.practicemodule.service.SubjectService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 固定三次查询：科目列表、我的统计、题数聚合。
 * 绝不在科目循环里查库——题库入口页一屏十几格，N+1 在这个接口最容易复现。
 */
@Service
public class SubjectServiceImpl implements SubjectService {

    private final QbSubjectMapper subjectMapper;
    private final QbUserSubjectStatMapper statMapper;

    public SubjectServiceImpl(QbSubjectMapper subjectMapper, QbUserSubjectStatMapper statMapper) {
        this.subjectMapper = subjectMapper;
        this.statMapper = statMapper;
    }

    @Override
    public List<SubjectItemVO> listSubjects(Long userId) {
        List<QbSubject> subjects = subjectMapper.selectEnabledSubjects();
        if (subjects.isEmpty()) {
            return List.of();
        }
        List<Long> ids = subjects.stream().map(QbSubject::getId).toList();

        Map<Long, QbUserSubjectStat> statBySubject = new HashMap<>();
        for (QbUserSubjectStat stat : statMapper.selectByUserAndSubjects(userId, ids)) {
            statBySubject.put(stat.getSubjectId(), stat);
        }

        Map<Long, Long> countBySubject = new HashMap<>();
        for (StatCountRow row : subjectMapper.countEnabledQuestionRows(ids)) {
            countBySubject.put(row.subjectId(), row.cnt());
        }

        return subjects.stream()
                .map(s -> toVo(s, statBySubject.get(s.getId()),
                        countBySubject.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    private SubjectItemVO toVo(QbSubject subject, QbUserSubjectStat stat, long questionCount) {
        SubjectItemVO vo = new SubjectItemVO();
        vo.setId(subject.getId());
        vo.setName(subject.getName());
        vo.setSubjectKey(subject.getSubjectKey());
        vo.setIcon(subject.getIcon());
        vo.setDescription(subject.getDescription());
        vo.setQuestionCount(questionCount);
        if (stat == null) {
            vo.setMyTotal(0);
            vo.setMyCorrect(0);
            vo.setMyAccuracy(0.0);
            vo.setLastAnswerAt(null);
            return vo;
        }
        vo.setMyTotal(stat.getTotalCount() == null ? 0 : stat.getTotalCount());
        vo.setMyCorrect(stat.getCorrectCount() == null ? 0 : stat.getCorrectCount());
        vo.setMyAccuracy(accuracy(vo.getMyCorrect(), vo.getMyTotal()));
        vo.setLastAnswerAt(stat.getLastAnswerAt() == null ? null : stat.getLastAnswerAt().getTime());
        return vo;
    }

    /** 除零守卫：total=0 返回 0 而不是 NaN —— NaN 序列化进 JSON 会直接抛异常 */
    static double accuracy(long correct, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(correct)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
