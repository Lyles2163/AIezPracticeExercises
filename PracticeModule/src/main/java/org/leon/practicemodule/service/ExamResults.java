package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.vo.ExamResultVO;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 成绩单的唯一装配出口（裁定四）。
 *
 * <p>它的存在理由：{@code submit} 刚刚判完分、{@code attempt/detail} 从库里重建、
 * {@code start(forceNew)} 归档旧草稿——三处都要给用户同一份东西。写成三个装配函数就一定会漂
 * （一个漏了 pendingSelfJudgeCount、一个把 submitAt 格式化成了字符串）。
 * 这里只做字段搬运，<b>不判分、不查库、不做业务判断</b>。
 */
public final class ExamResults {

    private ExamResults() {
    }

    public static ExamResultVO toVO(QbExamAttempt attempt, QbPaper paper, ExamGrading.PaperGrade grade) {
        ExamResultVO vo = new ExamResultVO();
        vo.setAttemptId(attempt.getId());
        vo.setSeqNo(attempt.getSeqNo());
        vo.setStatus(attempt.getStatus());
        vo.setPaperId(attempt.getPaperId());
        vo.setTitle(paper == null ? null : paper.getTitle());
        vo.setScore(grade.score());
        vo.setFullScore(grade.fullScore());
        vo.setCorrectCount(grade.correctCount());
        vo.setWrongCount(grade.wrongCount());
        vo.setUnansweredCount(grade.unansweredCount());
        vo.setPendingSelfJudgeCount(grade.pendingCount());
        vo.setAccuracy(grade.accuracy());
        vo.setDurationUsedMs(attempt.getDurationUsedMs());
        vo.setSubmitAt(millis(attempt.getSubmitAt()));

        List<ExamResultVO.SectionVO> sections = new ArrayList<>(grade.sections().size());
        for (ExamGrading.SectionResult s : grade.sections()) {
            ExamResultVO.SectionVO sv = new ExamResultVO.SectionVO();
            sv.setName(s.name());
            sv.setScoreGot(s.scoreGot());
            sv.setFullScore(s.fullScore());
            sv.setCorrectCount(s.correctCount());
            sv.setPendingCount(s.pendingCount());
            sv.setUnansweredCount(s.unansweredCount());
            sections.add(sv);
        }
        vo.setSections(List.copyOf(sections));

        List<ExamResultVO.QuestionVO> questions = new ArrayList<>(grade.items().size());
        for (ExamGrading.Item i : grade.items()) {
            ExamResultVO.QuestionVO qv = new ExamResultVO.QuestionVO();
            qv.setIndex(i.index());
            qv.setQuestionId(i.questionId());
            qv.setType(i.type());
            qv.setUserAnswerJson(i.userAnswerJson());
            qv.setCorrectAnswerJson(i.correctAnswerJson());
            qv.setIsCorrect(i.correct());
            qv.setNeedSelfJudge(i.needSelfJudge());
            qv.setScoreGot(i.scoreGot());
            qv.setFullScore(i.fullScore());
            qv.setAnalysis(i.analysis());
            questions.add(qv);
        }
        vo.setQuestions(List.copyOf(questions));
        return vo;
    }

    /** 进度百分比复用段 4 的口径（BigDecimal 2 位 HALF_UP 后转 double），两处必须一致 */
    public static double progressPercentOf(int answeredCount, int questionCount) {
        return PaperCards.progressPercent(answeredCount, questionCount);
    }

    private static Long millis(Date date) {
        return date == null ? null : date.getTime();
    }
}
