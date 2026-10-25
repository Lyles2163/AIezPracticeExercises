package org.leon.practicemodule.service;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * spec 4.3 的四条写入校验，唯一实现点。
 *
 * <p>纯函数：题目信息由调用方查好传进来，所以四条规则都能脱库测
 * （{@code PaperStructureValidatorTest} 14 条用例一条都不连服务）。
 *
 * <p>这四条不是洁癖，缺一条就有一个具体的坏页面：
 * 规则 1 断了，答题卡出现无主格子、题号跳号；规则 2 断了，别科或已下架的题混进卷面，
 * 错题本与统计跟着串科；规则 3 断了，成绩单的总分与逐题分之和对不上；
 * 规则 4 断了，卷面上排不出来的时间（各部分用时之和超过考试时长）会被用户当成承诺。
 *
 * <p>本段的调用者只有测试（裁定四）：模块没有组卷接口，样例卷由仓库外脚本灌入，
 * 于是 db-it 把校验架在真数据上跑一遍就是"写入时校验"这句承诺在当前段唯一能兑现的形式。
 * 段 5 的 {@code exam/start} 是第二个（也是生产）调用点。
 */
public final class PaperStructureValidator {

    /** 题目能否被本卷引用的判据原料：科目归属 + 上下架状态 */
    public record QuestionBrief(Long questionId, Long subjectId, int status) {
    }

    private PaperStructureValidator() {
    }

    public static void validate(PaperStructure structure, int questionCount, BigDecimal totalScore,
                                int durationMinutes, Long subjectId,
                                Map<Long, QuestionBrief> questions) {
        checkIndexAndCount(structure, questionCount);
        checkQuestionReferences(structure, subjectId, questions);
        checkScoreSum(structure, totalScore);
        checkMinutes(structure, durationMinutes);
    }

    /**
     * 规则 1：index 从 1 起逐位连续（按 parts→sections→questions 的书写顺序，跨 part 全局），
     * 顺带排除重复；且冗余列 question_count 必须等于实际题数。
     */
    private static void checkIndexAndCount(PaperStructure structure, int questionCount) {
        List<PaperStructure.QuestionRef> refs = structure.questionsInOrder();
        if (refs.size() != questionCount) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST,
                    "题目总数与卷面不符：question_count=" + questionCount + "，实际 " + refs.size() + " 题");
        }
        for (int i = 0; i < refs.size(); i++) {
            int index = refs.get(i).index();
            if (index != i + 1) {
                throw new PracticeException(PracticeErrorCode.BAD_REQUEST,
                        "第 " + (i + 1) + " 题的 index=" + index + " 不连续，题号必须从 1 起逐个递增且不重复");
            }
        }
    }

    /** 规则 2：每题都存在、启用、且属于试卷的科目 */
    private static void checkQuestionReferences(PaperStructure structure, Long subjectId,
                                                Map<Long, QuestionBrief> questions) {
        List<PaperStructure.QuestionRef> refs = structure.questionsInOrder();
        for (int i = 0; i < refs.size(); i++) {
            Long questionId = refs.get(i).questionId();
            QuestionBrief brief = questions.get(questionId);
            if (brief == null) {
                throw new PracticeException(PracticeErrorCode.BAD_REQUEST,
                        "第 " + (i + 1) + " 题（questionId=" + questionId + "）不存在");
            }
            if (brief.status() != 1) {
                throw new PracticeException(PracticeErrorCode.BAD_REQUEST,
                        "第 " + (i + 1) + " 题（questionId=" + questionId + "）已下架，不能入卷");
            }
            if (!subjectId.equals(brief.subjectId())) {
                throw new PracticeException(PracticeErrorCode.BAD_REQUEST,
                        "第 " + (i + 1) + " 题（questionId=" + questionId + "）不属于本科目，不能入卷");
            }
        }
    }

    /** 规则 3：Σ 题目分值 == total_score，容差 0，且必须 compareTo（14.0 与 14.00 是同一个数） */
    private static void checkScoreSum(PaperStructure structure, BigDecimal totalScore) {
        BigDecimal sum = structure.sumQuestionScore();
        if (sum.compareTo(totalScore) != 0) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST,
                    "分值之和不等于总分：题目合计 " + sum.toPlainString()
                            + "，total_score " + totalScore.toPlainString());
        }
    }

    /** 规则 4：Σ 部分时长 <= 考试时长（小于也合法，收卷与涂卡要留余量） */
    private static void checkMinutes(PaperStructure structure, int durationMinutes) {
        long minutes = structure.sumPartMinutes();
        if (minutes > durationMinutes) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST,
                    "各部分时长之和 " + minutes + " 分钟超过考试时长 " + durationMinutes + " 分钟");
        }
    }
}
