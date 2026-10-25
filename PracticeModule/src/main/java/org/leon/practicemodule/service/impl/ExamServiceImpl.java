package org.leon.practicemodule.service.impl;

import org.leon.practicemodule.config.PracticeProperties;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbExamAttemptMapper;
import org.leon.practicemodule.mapper.QbPaperMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.vo.ExamStartVO;
import org.leon.practicemodule.service.ExamAnswers;
import org.leon.practicemodule.service.ExamBriefs;
import org.leon.practicemodule.service.ExamDeadline;
import org.leon.practicemodule.service.ExamFinalizer;
import org.leon.practicemodule.service.ExamResults;
import org.leon.practicemodule.service.ExamService;
import org.leon.practicemodule.service.PaperLoad;
import org.leon.practicemodule.service.PaperSkeleton;
import org.leon.practicemodule.service.PaperStructure;
import org.leon.practicemodule.service.PaperStructureParser;
import org.leon.practicemodule.service.PaperStructureValidator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 套卷考试的状态机（spec 4.4）。
 *
 * <p>一次请求里只取<b>一次</b> {@code now = System.currentTimeMillis()} 并往下传：
 * 判过期、算 {@code deadline_at}、算 {@code duration_used_ms} 若各取一次，就会出现在"边界那一毫秒"上
 * 三个结论互相矛盾的结果（判过期说没过期、used 却为负）。这一条不值得为它引一个 Clock bean。
 */
@Service
public class ExamServiceImpl implements ExamService {

    /** 终态：2 已提交、3 超时或放弃自动归档（spec 4.4） */
    private static final int STATUS_SUBMITTED = 2;
    private static final int STATUS_ARCHIVED = 3;

    private final QbPaperMapper paperMapper;
    private final QbExamAttemptMapper attemptMapper;
    private final QbUserAnswerRecordMapper recordMapper;
    private final ExamBriefs briefs;
    private final ExamFinalizer finalizer;
    private final PracticeProperties props;

    public ExamServiceImpl(QbPaperMapper paperMapper,
                           QbExamAttemptMapper attemptMapper,
                           QbUserAnswerRecordMapper recordMapper,
                           ExamBriefs briefs, ExamFinalizer finalizer, PracticeProperties props) {
        this.paperMapper = paperMapper;
        this.attemptMapper = attemptMapper;
        this.recordMapper = recordMapper;
        this.briefs = briefs;
        this.finalizer = finalizer;
        this.props = props;
    }

    @Override
    @Transactional
    public ExamStartVO start(Long userId, Long paperId, Boolean forceNew) {
        QbPaper paper = PaperLoad.loadVisible(paperMapper, userId, paperId);
        PaperStructure structure = PaperStructureParser.parse(paper.getStructureJson());
        // 裁定四：exam/start 是 validateStructure 的第二个调用者（第一个是段 4 的 db-it）。
        // 也就是说"卷面在开考时仍然自洽"这件事在这里才真的对用户生效——题目被删/被下架、
        // 分值和题数被手改过，都该在点进考试的那一刻报 40000，而不是交卷时算出一份错乱成绩。
        PaperStructureValidator.validate(structure,
                paper.getQuestionCount() == null ? 0 : paper.getQuestionCount(),
                paper.getTotalScore(),
                paper.getDurationMinutes() == null ? 0 : paper.getDurationMinutes(),
                paper.getSubjectId(), validationBriefs(structure));

        long now = System.currentTimeMillis();
        QbExamAttempt draft = attemptMapper.selectDraftByUserAndPaper(userId, paperId);

        if (draft != null && (Boolean.TRUE.equals(forceNew)
                || ExamDeadline.expired(draft.getDeadlineAt(), now, graceSeconds()))) {
            // 裁定三：归档即判分，走与 submit 完全相同的那个出口
            finalizer.finalizeAttempt(draft, structure, paper, STATUS_ARCHIVED, now);
            draft = null;
        }
        if (draft != null) {
            return toStartVO(paper, structure, draft, now);
        }

        QbExamAttempt created = createAttempt(userId, paper, now);
        return toStartVO(paper, structure, created, now);
    }

    /** 新建一次草稿。撞唯一键（并发双击）时重读别人建好的那条返回（裁定二） */
    private QbExamAttempt createAttempt(Long userId, QbPaper paper, long now) {
        int seqNo = attemptMapper.selectMaxSeqNo(userId, paper.getId()) + 1;
        QbExamAttempt attempt = new QbExamAttempt();
        attempt.setUserId(userId);
        attempt.setPaperId(paper.getId());
        attempt.setSubjectId(paper.getSubjectId());
        attempt.setSeqNo(seqNo);
        attempt.setStatus(1);
        attempt.setStartedAt(new Date(now));
        attempt.setDeadlineAt(new Date(now + paper.getDurationMinutes() * 60_000L));
        try {
            attemptMapper.insert(attempt);
        } catch (DuplicateKeyException e) {
            QbExamAttempt raced = attemptMapper.selectDraftByUserAndPaper(userId, paper.getId());
            if (raced == null) {
                // 对方回滚了：这一刻确实没有可用草稿，别再退回去新建（会二次撞键）
                throw new PracticeException(PracticeErrorCode.CONFLICT, "该试卷正在被打开，请重试");
            }
            return raced;
        }
        return attempt;
    }

    /**
     * 响应的唯一装配点。<b>草稿的 originalText 恒 null</b>（裁定二：submitCount>0 才给原文，
     * 而"正在考、还没交"的那次作答不是 submitCount——所以这里传 false 就对了）。
     */
    private ExamStartVO toStartVO(QbPaper paper, PaperStructure structure,
                                  QbExamAttempt attempt, long now) {
        Map<String, String> answeredMap = new LinkedHashMap<>();
        for (Map.Entry<Long, String> e : ExamAnswers.decode(attempt.getAnsweredJson()).entrySet()) {
            answeredMap.put(String.valueOf(e.getKey()), e.getValue());
        }
        int questionCount = paper.getQuestionCount() == null ? 0 : paper.getQuestionCount();

        ExamStartVO vo = new ExamStartVO();
        vo.setAttemptId(attempt.getId());
        vo.setSeqNo(attempt.getSeqNo());
        vo.setPaperId(paper.getId());
        vo.setTitle(paper.getTitle());
        vo.setServerNow(now);
        vo.setDeadlineAt(attempt.getDeadlineAt().getTime());
        vo.setRemainingMs(Math.max(0L, attempt.getDeadlineAt().getTime() - now));
        vo.setDurationMinutes(paper.getDurationMinutes());
        vo.setQuestionCount(questionCount);
        vo.setAnsweredCount(answeredMap.size());
        vo.setProgressPercent(ExamResults.progressPercentOf(answeredMap.size(), questionCount));
        vo.setTotalScore(paper.getTotalScore());
        vo.setStructure(PaperSkeleton.toPartVOs(structure, false));
        vo.setAnsweredMap(Map.copyOf(answeredMap));
        return vo;
    }

    /**
     * 卷面自洽校验要的只是 {@code (id, subjectId, status)}，但后面判分还要整卷原料，
     * 所以这里复用 {@link ExamBriefs#loadQuestions} 那一条批量查询，而不是 service 自己再发一条
     * {@code selectBatchIds}（同一个 mapper、同一次请求、同一批 id——那就是本段要防的重复 SQL 形态）。
     */
    private Map<Long, PaperStructureValidator.QuestionBrief> validationBriefs(PaperStructure structure) {
        Map<Long, PaperStructureValidator.QuestionBrief> out = new LinkedHashMap<>();
        for (QbQuestion q : briefs.loadQuestions(structure).values()) {
            out.put(q.getId(), new PaperStructureValidator.QuestionBrief(
                    q.getId(), q.getSubjectId(), q.getStatus()));
        }
        return Map.copyOf(out);
    }

    private long graceSeconds() {
        return props.getExam().getGraceSeconds();
    }
}
