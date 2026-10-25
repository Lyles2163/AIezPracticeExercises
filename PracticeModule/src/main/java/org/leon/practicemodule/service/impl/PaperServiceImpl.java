package org.leon.practicemodule.service.impl;

import org.leon.practicemodule.mapper.PaperCardRow;
import org.leon.practicemodule.mapper.QbExamAttemptMapper;
import org.leon.practicemodule.mapper.QbPaperMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.PaperCardVO;
import org.leon.practicemodule.pojo.vo.PaperDetailVO;
import org.leon.practicemodule.service.PaperAttempts;
import org.leon.practicemodule.service.PaperCards;
import org.leon.practicemodule.service.PaperLoad;
import org.leon.practicemodule.service.PaperSkeleton;
import org.leon.practicemodule.service.PaperService;
import org.leon.practicemodule.service.PaperStructure;
import org.leon.practicemodule.service.PaperStructureParser;
import org.leon.practicemodule.service.PracticePages;
import org.leon.practicemodule.service.SubjectGuards;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 套卷读侧（段 4）。两个用例都是只读，所以本类不加 {@code @Transactional}，
 * 也不写任何表：qb_paper 由仓库外的 seed 脚本灌，qb_exam_attempt 由段 5 写。
 */
@Service
public class PaperServiceImpl implements PaperService {

    private final QbPaperMapper paperMapper;
    private final QbSubjectMapper subjectMapper;
    private final QbExamAttemptMapper attemptMapper;
    private final QbUserAnswerRecordMapper recordMapper;

    public PaperServiceImpl(QbPaperMapper paperMapper,
                            QbSubjectMapper subjectMapper,
                            QbExamAttemptMapper attemptMapper,
                            QbUserAnswerRecordMapper recordMapper) {
        this.paperMapper = paperMapper;
        this.subjectMapper = subjectMapper;
        this.attemptMapper = attemptMapper;
        this.recordMapper = recordMapper;
    }

    @Override
    public PageVO<PaperCardVO> page(Long userId, Long subjectId, Integer examYear, Integer examMonth,
                                    Integer setNo, Long pageNum, Long pageSize) {
        SubjectGuards.requireEnabled(subjectMapper, subjectId);
        PracticePages.PageParam page = PracticePages.of(pageNum, pageSize);

        long total = paperMapper.countForList(subjectId, examYear, examMonth, setNo, userId);
        if (total == 0L) {
            return PageVO.of(List.of(), 0L, page.pageNum(), page.pageSize());
        }
        List<PaperCardRow> rows = paperMapper.selectPage(subjectId, examYear, examMonth, setNo, userId,
                page.offset(), page.pageSize());
        if (rows.isEmpty()) {
            // 计数与取行之间被并发禁用：total 照回真值，list 空，不去打聚合 SQL
            return PageVO.of(List.of(), total, page.pageNum(), page.pageSize());
        }

        List<Long> paperIds = rows.stream().map(PaperCardRow::paperId).toList();
        Map<Long, PaperAttempts.Progress> progress =
                PaperAttempts.of(attemptMapper, recordMapper, userId, paperIds);

        List<PaperCardVO> list = new ArrayList<>(rows.size());
        for (PaperCardRow row : rows) {
            PaperAttempts.Progress p = progress.getOrDefault(row.paperId(), PaperAttempts.Progress.NONE);
            int questionCount = row.questionCount() == null ? 0 : row.questionCount();
            PaperCardVO vo = new PaperCardVO();
            vo.setPaperId(row.paperId());
            vo.setTitle(row.title());
            vo.setSubjectId(row.subjectId());
            vo.setSubjectName(row.subjectName());
            vo.setExamYear(row.examYear());
            vo.setExamMonth(row.examMonth());
            vo.setSetNo(row.setNo());
            vo.setBadges(PaperCards.badges(row.examYear(), row.examMonth(), row.setNo(), row.durationMinutes()));
            vo.setDurationMinutes(row.durationMinutes());
            vo.setTotalScore(row.totalScore());
            vo.setQuestionCount(questionCount);
            vo.setSubmitCount(p.submitCount());
            vo.setAnsweredCount(p.answeredCount());
            vo.setProgressPercent(PaperCards.progressPercent(p.answeredCount(), questionCount));
            vo.setLatestAttemptId(p.latestAttemptId());
            vo.setLatestAttemptStatus(p.latestStatus());
            list.add(vo);
        }
        return PageVO.of(list, total, page.pageNum(), page.pageSize());
    }

    @Override
    public PaperDetailVO detail(Long userId, Long paperId) {
        QbPaper paper = PaperLoad.loadVisible(paperMapper, userId, paperId);

        PaperStructure structure = PaperStructureParser.parse(paper.getStructureJson());
        PaperAttempts.Progress progress = PaperAttempts
                .of(attemptMapper, recordMapper, userId, List.of(paperId))
                .getOrDefault(paperId, PaperAttempts.Progress.NONE);
        // 裁定二：交过卷才给原文。判据直接用聚合的 submitCount（终态 attempt 计数），不多发一条 SQL。
        boolean originalTextVisible = PaperSkeleton.originalTextVisible(progress.submitCount());

        int questionCount = paper.getQuestionCount() == null ? 0 : paper.getQuestionCount();
        PaperDetailVO vo = new PaperDetailVO();
        vo.setPaperId(paper.getId());
        vo.setTitle(paper.getTitle());
        vo.setBadges(PaperCards.badges(paper.getExamYear(), paper.getExamMonth(),
                paper.getSetNo(), paper.getDurationMinutes()));
        vo.setDurationMinutes(paper.getDurationMinutes());
        vo.setTotalScore(paper.getTotalScore());
        vo.setQuestionCount(questionCount);
        vo.setDescription(paper.getDescription());
        vo.setSubmitCount(progress.submitCount());
        vo.setAnsweredCount(progress.answeredCount());
        vo.setProgressPercent(PaperCards.progressPercent(progress.answeredCount(), questionCount));
        vo.setLatestAttemptId(progress.latestAttemptId());
        vo.setLatestAttemptStatus(progress.latestStatus());
        vo.setParts(PaperSkeleton.toPartVOs(structure, originalTextVisible));
        return vo;
    }
}
