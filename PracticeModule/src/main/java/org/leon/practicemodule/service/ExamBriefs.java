package org.leon.practicemodule.service;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QuestionOptionKeyRow;
import org.leon.practicemodule.pojo.QbQuestion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张卷子的判分原料：一次取题目、一次取选项键（<b>零 N+1</b>）。
 *
 * <p>选项键只给 {@code type==1} 的题取（其余题型的判分不需要字母集合，段 2 就是这么传的），
 * 且只 {@code SELECT question_id, option_key}——{@code is_correct} 一个字节都不读（spec 5.4）。
 *
 * <p>做成 bean 而不是静态工具，是因为它要两个 mapper；{@code ExamFinalizer}、
 * {@code exam/start} 的卷面校验与 {@code attempt/detail} 的重建都调它，
 * 判分路径与重建路径因此共用同一份取数逻辑。
 *
 * <p>{@code requireEnabled} 是<b>两条时机的差异</b>，不是开关式的"以后可能用"：
 * 开考与交卷时卷面必须完整（题被删 → 40400，题下架 → 40900）；
 * 而看已交卷的成绩单时<b>题目可能已经下架</b>，这时候必须照样能看——所以重建路径传 false。
 * 把这条差异写成参数而不是两个方法，是因为它只差一次 status 判断，分成两个方法一定会有一个忘了同步。
 */
@Component
public class ExamBriefs {

    private final QbQuestionMapper questionMapper;
    private final QbQuestionOptionMapper optionMapper;

    public ExamBriefs(QbQuestionMapper questionMapper, QbQuestionOptionMapper optionMapper) {
        this.questionMapper = questionMapper;
        this.optionMapper = optionMapper;
    }

    /** 卷面涉及的全部题目（去重后一次批量取）。空 ids 挡在前面，不发 SQL */
    public Map<Long, QbQuestion> loadQuestions(PaperStructure structure) {
        List<Long> ids = questionIds(structure);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, QbQuestion> out = new LinkedHashMap<>();
        for (QbQuestion q : questionMapper.selectBatchIds(ids)) {
            out.put(q.getId(), q);
        }
        return Map.copyOf(out);
    }

    /**
     * 判分原料。注意题目那条批量查询在 {@link #loadQuestions} 里，
     * 本方法不复述它——{@code times(1)} 那条断言（{@code ExamBriefsTest}）钉的就是这个复用。
     */
    public Map<Long, ExamGrading.Brief> load(PaperStructure structure, boolean requireEnabled) {
        Map<Long, QbQuestion> questions = loadQuestions(structure);
        List<Long> ids = questionIds(structure);
        if (requireEnabled) {
            for (Long id : ids) {
                QbQuestion q = questions.get(id);
                if (q == null) {
                    throw new PracticeException(PracticeErrorCode.NOT_FOUND, "题目不存在");
                }
                if (q.getStatus() == null || q.getStatus() != 1) {
                    throw new PracticeException(PracticeErrorCode.CONFLICT, "题目已下架");
                }
            }
        }
        // 只有单选题需要字母集合：判分用得到；重建时这道题可能已经下架，取不到也不报错
        List<Long> singleChoice = new ArrayList<>();
        for (Long id : ids) {
            QbQuestion q = questions.get(id);
            if (q != null && q.getType() != null && q.getType() == 1) {
                singleChoice.add(id);
            }
        }
        Map<Long, List<String>> keysByQuestion = new HashMap<>();
        if (!singleChoice.isEmpty()) {
            for (QuestionOptionKeyRow row : optionMapper.selectOptionKeysByQuestions(singleChoice)) {
                keysByQuestion.computeIfAbsent(row.questionId(), k -> new ArrayList<>())
                        .add(row.optionKey());
            }
        }
        Map<Long, ExamGrading.Brief> out = new LinkedHashMap<>();
        for (Map.Entry<Long, QbQuestion> e : questions.entrySet()) {
            QbQuestion q = e.getValue();
            out.put(e.getKey(), new ExamGrading.Brief(q.getId(), q.getType(), q.getAnswerJson(),
                    q.getAnalysis(), List.copyOf(keysByQuestion.getOrDefault(e.getKey(), List.of()))));
        }
        return Map.copyOf(out);
    }

    private static List<Long> questionIds(PaperStructure structure) {
        return structure.questionsInOrder().stream()
                .map(PaperStructure.QuestionRef::questionId).distinct().toList();
    }
}
