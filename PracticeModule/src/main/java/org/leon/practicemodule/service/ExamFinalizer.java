package org.leon.practicemodule.service;

import org.leon.practicemodule.mapper.QbExamAttemptMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbUserAnswerRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * <b>整段唯一把 attempt 推成终态的地方。</b>{@code submit}、{@code start(forceNew)}、{@code start}
 * 发现的过期草稿三条路径全部走它（裁定三），所以"终态 ⇒ 有分数快照 ⇒ answered_json 已置 NULL"
 * 这条不变式只需要在一处维护。
 *
 * <p>交卷事务的八步（spec 5.6）里，第 7 步"<b>不更新 qb_user_subject_stat</b>"是刻意的
 * （spec 7.1：刷一套 25 题的真题会按题量权重压弯科目正确率）。这里只动错题本，
 * 一次都不碰统计 mapper——{@code ExamSubmitTest} 用 {@code verifyNoInteractions(statMapper)} 钉住，
 * {@code ExamFlowDbIT} 与端到端再各钉一层。
 *
 * <p>未答的题<b>不落记录行</b>：{@code qb_user_answer_record.user_answer_json} 是 {@code json NOT NULL}
 * （事实 1），写 NULL 会被 1364 拒。未答只进 {@code unanswered_count}。
 */
@Service
public class ExamFinalizer {

    private static final int ANSWER_MODE_EXAM = 2;
    private static final int PENDING_SELF_JUDGE = -1;

    private final QbExamAttemptMapper attemptMapper;
    private final QbUserAnswerRecordMapper recordMapper;
    private final QbUserWrongMapper wrongMapper;
    private final ExamBriefs briefs;
    private final AnswerGrader grader;

    public ExamFinalizer(QbExamAttemptMapper attemptMapper,
                         QbUserAnswerRecordMapper recordMapper,
                         QbUserWrongMapper wrongMapper,
                         ExamBriefs briefs, AnswerGrader grader) {
        this.attemptMapper = attemptMapper;
        this.recordMapper = recordMapper;
        this.wrongMapper = wrongMapper;
        this.briefs = briefs;
        this.grader = grader;
    }

    /** 交完之后的样子：attempt 是终态行（重读过），grade 是成绩单原料，justFinalized=false 表示"本来就已交过" */
    public record Outcome(QbExamAttempt attempt, ExamGrading.PaperGrade grade, boolean justFinalized) {
    }

    @Transactional
    public Outcome finalizeAttempt(QbExamAttempt draft, PaperStructure structure, QbPaper paper,
                                   int terminalStatus, long nowMillis) {
        Map<Long, String> answers = ExamAnswers.decode(draft.getAnsweredJson());
        // 判分路径 requireEnabled=true：题被删/下架时交卷必须报错，不能算出一份错乱成绩
        Map<Long, ExamGrading.Brief> brief = briefs.load(structure, true);
        ExamGrading.PaperGrade grade = ExamGrading.grade(structure, answers, brief, grader);

        QbExamAttempt terminal = terminalRow(draft, terminalStatus, grade, nowMillis);
        int updated = attemptMapper.finalizeIfDraft(terminal);
        if (updated == 0) {
            // 幂等闸门关上：有人先交了（并发双击、或前端重试）。不重复展开、不重复计分。
            QbExamAttempt existing = attemptMapper.selectById(draft.getId());
            Map<Long, ExamGrading.Brief> stored = briefs.load(structure, false);
            return new Outcome(existing,
                    ExamGrading.fromStored(structure,
                            recordMapper.selectByAttempt(existing.getUserId(), existing.getId()), stored),
                    false);
        }
        expandRecords(draft, terminal, grade);
        linkWrongBook(draft.getUserId(), paper, grade);
        QbExamAttempt done = attemptMapper.selectById(draft.getId());
        return new Outcome(done, grade, true);
    }

    /** 把已答题展开成 qb_user_answer_record 行（attempt_id 非空、answer_mode=2、用时均摊） */
    private void expandRecords(QbExamAttempt draft, QbExamAttempt terminal,
                               ExamGrading.PaperGrade grade) {
        List<ExamGrading.Item> answeredItems = new ArrayList<>();
        for (ExamGrading.Item item : grade.items()) {
            if (item.userAnswerJson() != null) {
                answeredItems.add(item);
            }
        }
        // 均摊的分母是"已答题数"，而时长取的是刚算出来的终态行（草稿的 duration_used_ms 还是 null）
        Integer per = perQuestionMillis(terminal.getDurationUsedMs(), answeredItems.size());
        // 一条一条 insert 是**故意的**：本模块没有批量 insert 的自定义 SQL，
        // 而一次交卷最多几十题，几十条 INSERT 在同一事务里比拼一条多值 INSERT 更清楚，
        // 也更重要的是——不引入"批量拼 SQL"的注入面。ExamSubmitTest 钉住"行数 = 已答题数"。
        for (ExamGrading.Item item : answeredItems) {
            QbUserAnswerRecord row = new QbUserAnswerRecord();
            row.setUserId(draft.getUserId());
            row.setSubjectId(draft.getSubjectId());
            row.setAttemptId(draft.getId());
            row.setQuestionId(item.questionId());
            row.setUserAnswerJson(item.userAnswerJson());
            row.setIsCorrect(item.needSelfJudge() ? PENDING_SELF_JUDGE
                    : (Boolean.TRUE.equals(item.correct()) ? 1 : 0));
            row.setDurationMs(per);
            row.setAnswerMode(ANSWER_MODE_EXAM);
            recordMapper.insert(row);
        }
    }

    /** 错题本联动（spec 5.6 第 6 步）；<b>不碰 qb_user_subject_stat</b>（第 7 步、spec 7.1） */
    private void linkWrongBook(Long userId, QbPaper paper, ExamGrading.PaperGrade grade) {
        for (ExamGrading.Item item : grade.items()) {
            if (item.needSelfJudge() || item.userAnswerJson() == null) {
                continue;   // 待自评与未答都不进错题本（段 2 的同一口径）
            }
            if (Boolean.TRUE.equals(item.correct())) {
                wrongMapper.masterIfUnmastered(userId, item.questionId());
            } else {
                wrongMapper.upsertWrong(userId, paper.getSubjectId(), item.questionId());
            }
        }
    }

    /** 整场用时均摊到已答题（spec 11：考试里没有逐题计时）。已答题 0 道时给 NULL，不给 0 */
    private static Integer perQuestionMillis(Integer used, int answered) {
        if (used == null || answered <= 0) {
            return null;
        }
        return (int) (used.longValue() / answered);
    }

    private static QbExamAttempt terminalRow(QbExamAttempt draft, int status,
                                             ExamGrading.PaperGrade grade, long nowMillis) {
        QbExamAttempt row = new QbExamAttempt();
        row.setId(draft.getId());
        row.setUserId(draft.getUserId());
        row.setStatus(status);
        row.setSubmitAt(new Date(nowMillis));
        row.setDurationUsedMs((int) Math.max(0L, nowMillis - draft.getStartedAt().getTime()));
        row.setScore(grade.score());
        row.setCorrectCount(grade.correctCount());
        row.setWrongCount(grade.wrongCount());
        row.setUnansweredCount(grade.unansweredCount());
        return row;
    }
}
