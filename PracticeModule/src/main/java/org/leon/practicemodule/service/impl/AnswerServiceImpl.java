package org.leon.practicemodule.service.impl;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserSubjectStatMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.pojo.AnswerSubmitReq;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbUserAnswerRecord;
import org.leon.practicemodule.pojo.QbUserSubjectStat;
import org.leon.practicemodule.pojo.QbUserWrong;
import org.leon.practicemodule.pojo.SelfJudgeReq;
import org.leon.practicemodule.pojo.vo.AnswerSubmitVO;
import org.leon.practicemodule.pojo.vo.SelfJudgeVO;
import org.leon.practicemodule.pojo.vo.StatDeltaVO;
import org.leon.practicemodule.service.AnswerGrader;
import org.leon.practicemodule.service.AnswerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 逐题作答的落库与联动，全部收在这一个类里：
 * 记录行 + 错题本 + 科目统计在一个本地事务内完成（单库，不加 @GlobalTransactional，spec 2.3）。
 * 累加口径（spec 7.1）：只有 answer_mode IN (1,3) 的作答进 qb_user_subject_stat，
 * mode=2（考试）在本接口直接 40000；段 5 的交卷自己落库、也不碰这张表。
 */
@Service
public class AnswerServiceImpl implements AnswerService {

    /** 简答"待自评"哨兵：is_correct 是有符号 tinyint，-1 不是"错"（段 2 裁定一） */
    static final int PENDING_SELF_JUDGE = -1;

    private final QbQuestionMapper questionMapper;
    private final QbQuestionOptionMapper optionMapper;
    private final QbUserAnswerRecordMapper recordMapper;
    private final QbUserWrongMapper wrongMapper;
    private final QbUserSubjectStatMapper statMapper;
    private final AnswerGrader grader;

    public AnswerServiceImpl(QbQuestionMapper questionMapper,
                             QbQuestionOptionMapper optionMapper,
                             QbUserAnswerRecordMapper recordMapper,
                             QbUserWrongMapper wrongMapper,
                             QbUserSubjectStatMapper statMapper,
                             AnswerGrader grader) {
        this.questionMapper = questionMapper;
        this.optionMapper = optionMapper;
        this.recordMapper = recordMapper;
        this.wrongMapper = wrongMapper;
        this.statMapper = statMapper;
        this.grader = grader;
    }

    @Override
    @Transactional
    public AnswerSubmitVO submit(Long userId, AnswerSubmitReq req) {
        validateSubmit(req);
        QbQuestion question = loadEnabledQuestion(req.getQuestionId(), req.getSubjectId());

        List<String> optionKeys = question.getType() == 1
                ? optionMapper.selectOptionKeys(question.getId())
                : List.of();
        AnswerGrader.GradeResult result = grader.grade(
                question.getType(), question.getAnswerJson(), req.getUserAnswerJson(), optionKeys);

        QbUserAnswerRecord record = new QbUserAnswerRecord();
        record.setUserId(userId);
        record.setSubjectId(question.getSubjectId());
        record.setQuestionId(question.getId());
        record.setUserAnswerJson(req.getUserAnswerJson());
        record.setIsCorrect(result.needSelfJudge() ? PENDING_SELF_JUDGE : (result.correct() ? 1 : 0));
        record.setDurationMs(req.getDurationMs());
        record.setAnswerMode(req.getMode());
        recordMapper.insert(record);

        if (!result.needSelfJudge()) {
            applyOutcome(userId, question.getSubjectId(), question.getId(), result.correct());
        }

        Outcome outcome = readOutcome(userId, question.getSubjectId(), question.getId());
        AnswerSubmitVO vo = new AnswerSubmitVO();
        vo.setRecordId(record.getId());
        vo.setIsCorrect(result.needSelfJudge() ? null : result.correct());
        vo.setCorrectAnswerJson(question.getAnswerJson());
        vo.setAnalysis(question.getAnalysis());
        vo.setNeedSelfJudge(result.needSelfJudge());
        vo.setStatDelta(outcome.statDelta());
        vo.setWrongCount(outcome.wrongCount());
        vo.setWrongMastered(outcome.wrongMastered());
        return vo;
    }

    @Override
    @Transactional
    public SelfJudgeVO selfJudge(Long userId, SelfJudgeReq req) {
        if (req.getRecordId() == null || req.getQuestionId() == null
                || req.getSubjectId() == null || req.getIsCorrect() == null) {
            throw badRequest("请求参数不合法");
        }
        QbUserAnswerRecord record = recordMapper.selectById(req.getRecordId());
        if (record == null) {
            throw notFound("作答记录不存在");
        }
        if (!userId.equals(record.getUserId())) {
            throw new PracticeException(PracticeErrorCode.FORBIDDEN, "无权操作该作答");
        }
        Integer answerMode = record.getAnswerMode();
        if (answerMode == null || (answerMode != 1 && answerMode != 3)) {
            // 段 5 若把考试简答也落成 -1，这里不让它绕开 7.1 把整卷写进科目统计
            throw badRequest("仅逐题练习作答支持自评");
        }
        if (!record.getQuestionId().equals(req.getQuestionId())) {
            throw badRequest("记录与题目不匹配");
        }
        if (!record.getSubjectId().equals(req.getSubjectId())) {
            throw badRequest("记录与科目不匹配");
        }
        QbQuestion question = questionMapper.selectById(req.getQuestionId());
        if (question == null) {
            throw notFound("题目不存在");
        }
        if (question.getType() == null || question.getType() != 5) {
            throw badRequest("仅简答题支持自评");
        }

        int judged = recordMapper.judgeIfPending(req.getRecordId(), userId, req.getIsCorrect() ? 1 : 0);
        if (judged == 0) {
            // 条件更新是幂等闸门：并发或重试只有第一次生效，统计与错题本不会被重复计数
            throw new PracticeException(PracticeErrorCode.CONFLICT, "该记录已完成自评");
        }
        applyOutcome(userId, record.getSubjectId(), record.getQuestionId(), req.getIsCorrect());
        SelfJudgeVO vo = new SelfJudgeVO();
        vo.setStatDelta(readOutcome(userId, record.getSubjectId(), record.getQuestionId()).statDelta());
        return vo;
    }

    /** 联动：错题本 upsert + 统计累加。简答待自评时调用方跳过本方法 */
    void applyOutcome(Long userId, Long subjectId, Long questionId, boolean correct) {
        if (correct) {
            wrongMapper.masterIfUnmastered(userId, questionId);
        } else {
            wrongMapper.upsertWrong(userId, subjectId, questionId);
        }
        statMapper.upsertAccumulate(userId, subjectId, correct ? 1 : 0, correct ? 0 : 1);
    }

    /**
     * 读回错题与统计的当前状态组装响应。
     * 不靠内存推算：本事务 upsert 过的行有 X 锁到提交，读回即绝对值；
     * 没写过的行（简答待自评那条路径）读到的是本事务快照下的值，可能偏小但不会偏大。
     */
    Outcome readOutcome(Long userId, Long subjectId, Long questionId) {
        QbUserWrong wrong = wrongMapper.selectByUserAndQuestion(userId, questionId);
        QbUserSubjectStat stat = statMapper.selectByUserAndSubject(userId, subjectId);
        StatDeltaVO delta = new StatDeltaVO();
        delta.setTotal(stat == null ? 0 : nz(stat.getTotalCount()));
        delta.setCorrect(stat == null ? 0 : nz(stat.getCorrectCount()));
        delta.setWrong(stat == null ? 0 : nz(stat.getWrongCount()));
        delta.setAccuracy(SubjectServiceImpl.accuracy(delta.getCorrect(), delta.getTotal()));
        return new Outcome(
                wrong == null ? 0 : nz(wrong.getWrongCount()),
                wrong != null && wrong.getStatus() != null && wrong.getStatus() == 2,
                delta);
    }

    private void validateSubmit(AnswerSubmitReq req) {
        if (req.getSubjectId() == null || req.getQuestionId() == null) {
            throw badRequest("请求参数不合法");
        }
        if (req.getMode() == null || (req.getMode() != 1 && req.getMode() != 3)) {
            throw badRequest("答题模式只能是 1刷题 或 3智能练习");
        }
        if (!StringUtils.hasText(req.getUserAnswerJson())) {
            throw badRequest("答案不能为空");
        }
        if (req.getDurationMs() != null && req.getDurationMs() < 0) {
            throw badRequest("单题用时不能为负");
        }
    }

    private QbQuestion loadEnabledQuestion(Long questionId, Long subjectId) {
        QbQuestion question = questionMapper.selectById(questionId);
        if (question == null) {
            throw notFound("题目不存在");
        }
        if (question.getStatus() == null || question.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "题目已下架");
        }
        if (!question.getSubjectId().equals(subjectId)) {
            throw badRequest("题目不属于该科目");
        }
        return question;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static PracticeException badRequest(String msg) {
        return new PracticeException(PracticeErrorCode.BAD_REQUEST, msg);
    }

    private static PracticeException notFound(String msg) {
        return new PracticeException(PracticeErrorCode.NOT_FOUND, msg);
    }

    /** 响应组装的中间结果，不用 VO 兼职内部结构 */
    record Outcome(Integer wrongCount, boolean wrongMastered, StatDeltaVO statDelta) {
    }
}