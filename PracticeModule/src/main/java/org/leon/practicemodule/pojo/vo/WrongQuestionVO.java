package org.leon.practicemodule.pojo.vo;

import lombok.Data;

import java.util.List;

/**
 * 给 AI 用的错题详情（IncorrectlyQuestionsController.listForAi）。
 *
 * 与 {@link WrongItemVO} 的差别：那份是给前端错题本卡片用的，只给题干摘要、不泄漏答案真值
 * （spec 5.4）；而这份是给 AI 答疑用的——AI 需要看到题干、选项、我的上次作答、正确答案和解析，
 * 才能基于真实错题讲解。所以字段是全开的，接口也不挂在 /practice/** 下（那条路径走网关注入
 * X-User-Name 给浏览器），而是独立的 /incorrectlyQuestions/list，由调用方显式传 X-User-Name。
 *
 * 题型 type 与题面答案 answerJson 的语义见 {@code QbQuestion} 顶部注释。
 */
@Data
public class WrongQuestionVO {

    private Long questionId;
    private Long subjectId;
    private String subjectName;
    /** 题型：1单选 2多选 3判断 4填空 5简答 */
    private Integer type;
    /** 难度：1简单 2中等 3困难 */
    private Integer difficulty;
    private String title;
    /** 选项，仅单选/多选有值；按 sort_order,id 升序 */
    private List<Option> options;
    /** 标签，可能为空列表 */
    private List<String> tags;
    /** 答错次数 */
    private Integer wrongCount;
    /** 最近一次答错时间，epoch 毫秒 */
    private Long lastWrongAt;
    /** 1未掌握 2已掌握 */
    private Integer status;
    /** 我自己上次的作答 JSON（来自 qb_user_answer_record.user_answer_json），可能为 null */
    private String lastUserAnswerJson;
    /** 题目真值答案 JSON（来自 qb_question.answer_json） */
    private String answerJson;
    /** 题目解析 */
    private String analysis;

    @Data
    public static class Option {
        private String optionKey;
        private String content;
    }
}
