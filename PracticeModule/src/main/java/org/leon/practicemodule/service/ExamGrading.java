package org.leon.practicemodule.service;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.AttemptRecordRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 判分与三级汇总（题 → section → 卷），<b>不含任何 SQL</b>。
 *
 * <p>两条出口给出<b>同一个</b> {@link PaperGrade} 形状：
 * {@link #grade} 在交卷那一刻判分，{@link #fromStored} 在刷新/重进时按已落库的 is_correct 重建。
 * 成绩单只有一条装配路径（裁定四），所以"刚交完看到的"与"刷新后看到的"必然一致——
 * 这不是额外功能，是把两条路径捏成一个类型的后果。
 *
 * <p>判分本身一律外包给 {@link AnswerGrader}：它已经是 5 个题型与 MySQL 严格度的唯一口径
 * （段 2 的类缺陷就是在"自己再写一遍判分"的地方漏的），这里只负责"哪些题、什么顺序、各值多少分"。
 *
 * <p><b>裁定一在这里落地</b>：{@code type=5} 的题 {@code needSelfJudge=true} →
 * {@code correct} 为 null、{@code scoreGot} 为 0、进 {@code pendingCount}，
 * <b>不进</b> correct/wrong/unanswered，也不进 accuracy 的分母。
 */
public final class ExamGrading {

    /** is_correct 的三态哨兵（段 2 裁定一）。这里重复一次常量而不是跨包引用，
     *  是因为 AnswerServiceImpl 的那个是包级私有，牵出来会把段 2 的类一起拖进依赖。 */
    private static final int PENDING_SELF_JUDGE = -1;

    private ExamGrading() {
    }

    /** 一题的判分原料：来自 qb_question（批量取，零 N+1） */
    public record Brief(long questionId, Integer type, String answerJson, String analysis,
                        List<String> optionKeys) {
    }

    /** 成绩单的一题 */
    public record Item(int index, Long questionId, Integer type, String userAnswerJson,
                       Boolean correct, boolean needSelfJudge,
                       BigDecimal scoreGot, BigDecimal fullScore,
                       String correctAnswerJson, String analysis) {
    }

    /** 成绩单的一节（section 级汇总，spec 5.6 的 sections[]） */
    public record SectionResult(String name, BigDecimal scoreGot, BigDecimal fullScore,
                                int correctCount, int pendingCount, int unansweredCount) {
    }

    public record PaperGrade(List<Item> items, List<SectionResult> sections,
                             BigDecimal score, BigDecimal fullScore,
                             int correctCount, int wrongCount, int unansweredCount, int pendingCount,
                             BigDecimal accuracy) {
    }

    public static PaperGrade grade(PaperStructure structure, Map<Long, String> answers,
                                   Map<Long, Brief> briefs, AnswerGrader grader) {
        List<Item> items = new ArrayList<>();
        for (PaperStructure.QuestionRef ref : structure.questionsInOrder()) {
            Brief brief = requireBrief(briefs, ref.questionId());
            String userAnswer = answers.get(ref.questionId());
            if (userAnswer == null) {
                items.add(new Item(ref.index(), brief.questionId(), brief.type(), null,
                        null, false, BigDecimal.ZERO, ref.score(),
                        brief.answerJson(), brief.analysis()));
                continue;
            }
            AnswerGrader.GradeResult r = grader.grade(
                    brief.type(), brief.answerJson(), userAnswer, brief.optionKeys());
            BigDecimal got = r.needSelfJudge() || !r.correct()
                    ? BigDecimal.ZERO : ref.score();
            items.add(new Item(ref.index(), brief.questionId(), brief.type(), userAnswer,
                    r.needSelfJudge() ? null : r.correct(), r.needSelfJudge(),
                    got, ref.score(), brief.answerJson(), brief.analysis()));
        }
        return assemble(structure, items);
    }

    /** 从已落库的行重建。is_correct 的 -1/0/1 就是真相，不再判分——所以刷新不需要 answer_json 也在 */
    public static PaperGrade fromStored(PaperStructure structure, List<AttemptRecordRow> rows,
                                        Map<Long, Brief> briefs) {
        Map<Long, AttemptRecordRow> byQuestion = new HashMap<>();
        for (AttemptRecordRow row : rows) {
            byQuestion.put(row.questionId(), row);   // 同题多行时取后写的：一次交卷一题只有一行
        }
        List<Item> items = new ArrayList<>();
        for (PaperStructure.QuestionRef ref : structure.questionsInOrder()) {
            Brief brief = requireBrief(briefs, ref.questionId());
            AttemptRecordRow row = byQuestion.get(ref.questionId());
            if (row == null) {
                items.add(new Item(ref.index(), brief.questionId(), brief.type(), null,
                        null, false, BigDecimal.ZERO, ref.score(),
                        brief.answerJson(), brief.analysis()));
                continue;
            }
            Integer isCorrect = row.isCorrect();
            boolean pending = isCorrect != null && isCorrect == PENDING_SELF_JUDGE;
            Boolean correct = isCorrect == null ? null : (pending ? null : isCorrect == 1);
            BigDecimal got = correct != null && correct ? ref.score() : BigDecimal.ZERO;
            items.add(new Item(ref.index(), brief.questionId(), brief.type(), row.userAnswerJson(),
                    correct, pending, got, ref.score(), brief.answerJson(), brief.analysis()));
        }
        return assemble(structure, items);
    }

    /**
     * 唯一的汇总出口：题 → section → 卷的所有计数与求和都在这里，
     * 两条路径共用，所以不存在"判分路径算了一种口径、重建路径算了另一种"的可能。
     */
    private static PaperGrade assemble(PaperStructure structure, List<Item> items) {
        Map<Integer, Item> byIndex = new HashMap<>();
        for (Item item : items) {
            byIndex.put(item.index(), item);
        }

        List<SectionResult> sections = new ArrayList<>();
        int correct = 0, wrong = 0, unanswered = 0, pending = 0;
        BigDecimal score = BigDecimal.ZERO;

        for (PaperStructure.Part part : structure.parts()) {
            for (PaperStructure.Section section : part.sections()) {
                BigDecimal sScore = BigDecimal.ZERO, sFull = BigDecimal.ZERO;
                int sCorrect = 0, sPending = 0, sUnanswered = 0;
                for (PaperStructure.QuestionRef ref : section.questions()) {
                    Item item = byIndex.get(ref.index());
                    sFull = sFull.add(ref.score());
                    sScore = sScore.add(item.scoreGot());
                    score = score.add(item.scoreGot());        // 卷级求和：漏掉这行 score 会永远是 0
                    if (item.userAnswerJson() == null) {
                        sUnanswered++;
                    } else if (item.needSelfJudge()) {
                        sPending++;
                    } else if (Boolean.TRUE.equals(item.correct())) {
                        sCorrect++;
                    } else {
                        wrong++;
                    }
                }
                sections.add(new SectionResult(section.label(), sScore, sFull,
                        sCorrect, sPending, sUnanswered));
                correct += sCorrect;
                pending += sPending;
                unanswered += sUnanswered;
            }
        }

        // fullScore 用 Σ 题目分值而不是 qb_paper.total_score：规则 3 已保证两者相等（段 4 校验），
        // 但成绩单是逐题算出来的，取卷面列会在"题目被改了分"时给出两个对不上的数。
        BigDecimal questionScoreSum = BigDecimal.ZERO;
        for (Item item : items) {
            questionScoreSum = questionScoreSum.add(item.fullScore());
        }

        return new PaperGrade(List.copyOf(items), List.copyOf(sections),
                score.setScale(2, RoundingMode.HALF_UP), questionScoreSum.setScale(2, RoundingMode.HALF_UP),
                correct, wrong, unanswered, pending, accuracy(correct, wrong));
    }

    /** 裁定六：分母只有"判过分的答案"。0/0 → 0.0，不是 NaN 也不是异常 */
    private static BigDecimal accuracy(int correct, int wrong) {
        int graded = correct + wrong;
        if (graded == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(correct)
                .divide(BigDecimal.valueOf(graded), 2, RoundingMode.HALF_UP);
    }

    private static Brief requireBrief(Map<Long, Brief> briefs, Long questionId) {
        Brief brief = briefs.get(questionId);
        if (brief == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "题目不存在");
        }
        return brief;
    }
}
