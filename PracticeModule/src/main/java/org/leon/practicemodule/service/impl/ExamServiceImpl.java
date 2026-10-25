package org.leon.practicemodule.service.impl;

import org.leon.practicemodule.config.PracticeProperties;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.AttemptPageRow;
import org.leon.practicemodule.mapper.AttemptRecordRow;
import org.leon.practicemodule.mapper.QbExamAttemptMapper;
import org.leon.practicemodule.mapper.QbPaperMapper;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbQuestionOption;
import org.leon.practicemodule.pojo.vo.ExamAttemptItemVO;
import org.leon.practicemodule.pojo.vo.ExamDraftVO;
import org.leon.practicemodule.pojo.vo.ExamQuestionVO;
import org.leon.practicemodule.pojo.vo.ExamResultVO;
import org.leon.practicemodule.pojo.vo.ExamStartVO;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.QuestionVO;
import org.leon.practicemodule.service.ExamAnswers;
import org.leon.practicemodule.service.ExamBriefs;
import org.leon.practicemodule.service.ExamDeadline;
import org.leon.practicemodule.service.ExamFinalizer;
import org.leon.practicemodule.service.ExamGrading;
import org.leon.practicemodule.service.ExamResults;
import org.leon.practicemodule.service.ExamService;
import org.leon.practicemodule.service.PaperLoad;
import org.leon.practicemodule.service.PracticeMasker;
import org.leon.practicemodule.service.PaperSkeleton;
import org.leon.practicemodule.service.PaperStructure;
import org.leon.practicemodule.service.PaperStructureParser;
import org.leon.practicemodule.service.PaperStructureValidator;
import org.leon.practicemodule.service.PracticePages;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
    private final QbQuestionMapper questionMapper;
    private final QbQuestionOptionMapper optionMapper;
    private final ExamBriefs briefs;
    private final ExamFinalizer finalizer;
    private final PracticeProperties props;
    private final PracticeMasker masker;

    /**
     * 九个依赖里有三个（questionMapper / optionMapper / masker）是 {@code exam/question} 带进来的：
     * 那条路径一次只要一道题，所以它走单题查询而不是 {@link ExamBriefs} 的批量取数——
     * 用批量取整卷原料换一道题是浪费，两条路径各自的成本要各算。
     */
    public ExamServiceImpl(QbPaperMapper paperMapper,
                           QbExamAttemptMapper attemptMapper,
                           QbUserAnswerRecordMapper recordMapper,
                           QbQuestionMapper questionMapper,
                           QbQuestionOptionMapper optionMapper,
                           ExamBriefs briefs, ExamFinalizer finalizer,
                           PracticeProperties props, PracticeMasker masker) {
        this.paperMapper = paperMapper;
        this.attemptMapper = attemptMapper;
        this.recordMapper = recordMapper;
        this.questionMapper = questionMapper;
        this.optionMapper = optionMapper;
        this.briefs = briefs;
        this.finalizer = finalizer;
        this.props = props;
        this.masker = masker;
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

    /** 成绩单之外的读题路径。不加 @Transactional：一条 SELECT 都不写。 */
    @Override
    public ExamQuestionVO question(Long userId, Long attemptId, Long questionId) {
        if (attemptId == null || questionId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        QbExamAttempt attempt = loadOwnAttempt(userId, attemptId);
        if (attempt.getStatus() == null || attempt.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.EXAM_SUBMITTED, "该次作答已提交");
        }
        QbPaper paper = PaperLoad.loadVisible(paperMapper, userId, attempt.getPaperId());
        PaperStructure structure = PaperStructureParser.parse(paper.getStructureJson());
        PaperStructure.QuestionRef ref = findRef(structure, questionId);   // 不在卷面上 → 40000

        QbQuestion question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "题目不存在");
        }
        if (question.getStatus() == null || question.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "题目已下架");
        }
        // 只有选择题需要选项行；判断/填空/简答压根没有选项（段 3 的同一判据）
        List<QbQuestionOption> options =
                question.getType() != null && (question.getType() == 1 || question.getType() == 2)
                        ? optionMapper.selectDisplayOptions(questionId)   // 既有查询就不选 is_correct
                        : List.of();

        QuestionVO masked = masker.toQuestionVO(question, options);
        masked.setIndex(ref.index());
        masked.setScore(ref.score());      // QuestionVO 的预留字段，段 3 恒 null，这里第一次填上

        ExamQuestionVO vo = new ExamQuestionVO();
        vo.setAttemptId(attempt.getId());
        vo.setQuestionId(questionId);
        vo.setIndex(ref.index());
        vo.setScore(ref.score());
        vo.setQuestion(masked);
        vo.setUserAnswerJson(ExamAnswers.decode(attempt.getAnsweredJson()).get(questionId));
        return vo;
    }

    /**
     * 存草稿：<b>整包覆盖</b>，这次没带的题号就等于删掉——不做增量合并，
     * 否则前端"清空重来"永远传不出"删掉某题"这个动作。
     *
     * <p>三条闸门的顺序就是响应码的顺序：归属（40400/40300）→ 终态（41002）→ 超时（41001）。
     * 超时这一条<b>只拒绝写入、不归档</b>：§6.4 说到点之后只有 submit 能收这次作答，
     * 在这里顺手归档会把"用户看一眼屏幕发现超时"变成"已经被判了分"，那是两种产品事实。
     */
    @Override
    @Transactional
    public ExamDraftVO draft(Long userId, Long attemptId, Map<String, String> answers) {
        if (attemptId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        QbExamAttempt attempt = loadOwnAttempt(userId, attemptId);
        if (attempt.getStatus() == null || attempt.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.EXAM_SUBMITTED, "该次作答已提交");
        }
        long now = System.currentTimeMillis();
        if (ExamDeadline.expired(attempt.getDeadlineAt(), now, graceSeconds())) {
            throw new PracticeException(PracticeErrorCode.EXAM_EXPIRED, "考试已结束，请交卷");
        }
        Map<Long, String> normalized = toLongKeyed(answers);

        // 题号归属与题数都要读卷面，读的还是同一张卷子：一次 loadVisible + parse，两处传引用
        QbPaper paper = PaperLoad.loadVisible(paperMapper, userId, attempt.getPaperId());
        PaperStructure structure = PaperStructureParser.parse(paper.getStructureJson());
        requireKeysOnPaper(normalized.keySet(), structure);
        String encoded = ExamAnswers.encode(normalized);

        QbExamAttempt patch = new QbExamAttempt();
        patch.setId(attempt.getId());
        patch.setAnsweredJson(encoded);
        attemptMapper.updateById(patch);

        ExamDraftVO vo = new ExamDraftVO();
        vo.setSavedAt(now);
        vo.setAnsweredCount(normalized.size());
        vo.setProgressPercent(ExamResults.progressPercentOf(normalized.size(),
                paper.getQuestionCount() == null ? 0 : paper.getQuestionCount()));
        return vo;
    }

    /** 题号字符串 → Long：非数字、≤0 与"两个写法指向同一题"（{@code "1"} 与 {@code "01"}）都是 40000 */
    private static Map<Long, String> toLongKeyed(Map<String, String> answers) {
        if (answers == null || answers.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new LinkedHashMap<>(answers.size());
        for (Map.Entry<String, String> e : answers.entrySet()) {
            long questionId;
            try {
                questionId = Long.parseLong(e.getKey());
            } catch (NumberFormatException | NullPointerException ex) {
                throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "答案的题号不合法：" + e.getKey());
            }
            if (questionId <= 0L) {
                throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "答案的题号不合法：" + e.getKey());
            }
            if (out.containsKey(questionId)) {
                throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "题号重复：" + questionId);
            }
            out.put(questionId, e.getValue());
        }
        return out;
    }

    /** 草稿里不许长出这张卷子没有的答案：键必须全是卷面上的题号 */
    private static void requireKeysOnPaper(Set<Long> keys, PaperStructure structure) {
        if (keys.isEmpty()) {
            return;
        }
        Set<Long> onPaper = new HashSet<>();
        for (PaperStructure.QuestionRef ref : structure.questionsInOrder()) {
            onPaper.add(ref.questionId());
        }
        for (Long key : keys) {
            if (!onPaper.contains(key)) {
                throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "该题不在这张卷子里：" + key);
            }
        }
    }

    /**
     * 交卷。终态判定只有两种：<b>没过期 → 2，过期 → 3</b>，两者都照常判分（§6.4 的"超时提交仍给成绩"）。
     *
     * <p>已经是终态的 attempt 走 {@link #rebuildResult} 而不是闸门：双击的第二次几乎零成本，
     * 而且<b>不可能重复展开行</b>——重复展开会让错题本 {@code wrong_count} 与成绩单行数一起失真。
     */
    @Override
    @Transactional
    public ExamResultVO submit(Long userId, Long attemptId) {
        if (attemptId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        QbExamAttempt attempt = loadOwnAttempt(userId, attemptId);
        QbPaper paper = PaperLoad.loadVisible(paperMapper, userId, attempt.getPaperId());
        PaperStructure structure = PaperStructureParser.parse(paper.getStructureJson());

        if (attempt.getStatus() == null || attempt.getStatus() != 1) {
            return rebuildResult(attempt, paper, structure);
        }
        if (attempt.getStartedAt() == null) {
            // 算不出用时，也定不出"这条草稿是哪一次开考"。这种行只能来自手改库。
            throw new PracticeException(PracticeErrorCode.CONFLICT, "作答数据异常，请重新进入考试");
        }
        long now = System.currentTimeMillis();
        int terminal = ExamDeadline.expired(attempt.getDeadlineAt(), now, graceSeconds())
                ? STATUS_ARCHIVED : STATUS_SUBMITTED;
        ExamFinalizer.Outcome outcome = finalizer.finalizeAttempt(attempt, structure, paper, terminal, now);
        return ExamResults.toVO(outcome.attempt(), paper, outcome.grade());
    }

    /**
     * 成绩单的<b>重建</b>路径：{@code attempt/detail} 与"闸门已关上/本来就终态"的 {@code submit} 共用（裁定四）。
     *
     * <p>{@code briefs.load(structure, false)} 里那个 {@code false} 是本方法的要点：
     * 已交卷的成绩单不能因为运营下架一道题就看不见。判分路径传的是 {@code true}（在
     * {@link ExamFinalizer#finalizeAttempt} 里），两条时机共用同一个取数函数、只差一次 status 判断。
     */
    private ExamResultVO rebuildResult(QbExamAttempt attempt, QbPaper paper, PaperStructure structure) {
        Map<Long, ExamGrading.Brief> brief = briefs.load(structure, false);
        List<AttemptRecordRow> rows = recordMapper.selectByAttempt(attempt.getUserId(), attempt.getId());
        return ExamResults.toVO(attempt, paper, ExamGrading.fromStored(structure, rows, brief));
    }

    /** 历次作答：两条 SQL（count + 页），行内字段全来自投影行，零 N+1 */
    @Override
    public PageVO<ExamAttemptItemVO> attemptPage(Long userId, Long paperId,
                                                 Long pageNum, Long pageSize) {
        if (paperId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        PracticePages.PageParam page = PracticePages.of(pageNum, pageSize);
        QbPaper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "试卷不存在");
        }
        long total = attemptMapper.countForPaper(userId, paperId);
        if (total == 0L) {
            return PageVO.of(List.of(), 0L, page.pageNum(), page.pageSize());
        }
        List<AttemptPageRow> rows = attemptMapper.selectAttemptPage(userId, paperId,
                page.offset(), page.pageSize());
        int questionCount = paper.getQuestionCount() == null ? 0 : paper.getQuestionCount();
        List<ExamAttemptItemVO> list = new ArrayList<>(rows.size());
        for (AttemptPageRow row : rows) {
            ExamAttemptItemVO vo = new ExamAttemptItemVO();
            vo.setAttemptId(row.attemptId());
            vo.setSeqNo(row.seqNo());
            vo.setStatus(row.status());
            vo.setScore(row.score());
            vo.setSubmitAt(row.submitAt() == null ? null : row.submitAt().getTime());
            vo.setDurationUsedMs(row.durationUsedMs());
            vo.setCorrectCount(row.correctCount());
            vo.setWrongCount(row.wrongCount());
            vo.setUnansweredCount(row.unansweredCount());
            // 待自评数不存列（qb_exam_attempt 只有 17 列，段 4 实测），用减法：
            // 三个快照计数都是可空的，草稿态全 NULL，所以先判空再减，不给 0 假装"没有待自评"。
            if (row.correctCount() != null || row.wrongCount() != null || row.unansweredCount() != null) {
                vo.setPendingSelfJudgeCount(questionCount - nz(row.correctCount())
                        - nz(row.wrongCount()) - nz(row.unansweredCount()));
            }
            list.add(vo);
        }
        return PageVO.of(list, total, page.pageNum(), page.pageSize());
    }

    @Override
    public ExamResultVO attemptDetail(Long userId, Long attemptId) {
        if (attemptId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        QbExamAttempt attempt = loadOwnAttempt(userId, attemptId);
        if (attempt.getStatus() == null || attempt.getStatus() == 1) {
            throw new PracticeException(PracticeErrorCode.EXAM_SUBMITTED, "该次作答尚未提交");
        }
        QbPaper paper = paperMapper.selectById(attempt.getPaperId());
        if (paper == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "试卷不存在");
        }
        return rebuildResult(attempt, paper, PaperStructureParser.parse(paper.getStructureJson()));
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    /** attempt 归属判据：不存在 40400，不是本人 40300（spec 5.7 的两码必须在同一处分支上分开） */
    private QbExamAttempt loadOwnAttempt(Long userId, Long attemptId) {
        QbExamAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "作答不存在");
        }
        if (!userId.equals(attempt.getUserId())) {
            throw new PracticeException(PracticeErrorCode.FORBIDDEN, "无权操作该作答");
        }
        return attempt;
    }

    private static PaperStructure.QuestionRef findRef(PaperStructure structure, Long questionId) {
        for (PaperStructure.QuestionRef ref : structure.questionsInOrder()) {
            if (ref.questionId().equals(questionId)) {
                return ref;
            }
        }
        throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "该题不在这张卷子里");
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
