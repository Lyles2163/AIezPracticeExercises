package org.leon.practicemodule.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbQuestionOption;
import org.leon.practicemodule.pojo.vo.QuestionVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 答题态唯一的出口（spec 5.4）。实体 → VO 逐字段白名单复制：
 * 不复制 answerJson/analysis，也绝不读 QbQuestionOption.isCorrect（spec 5.5）。
 * 不注入任何 bean；取选项与拼 answerStatus 都是调用方的事（段 3/段 5）。
 */
@Slf4j
@Service
public class PracticeMasker {

    /**
     * 答题态禁止外泄的字段名（spec 5.4）。段 3 的 question/current、question/page 与段 5 的
     * exam/question 对**真实响应体**跑同一份清单——不要再各写一套，那样防线会随分段散落。
     * camelCase 与 snake_case 都列进来，防的是将来给 spring.jackson 配命名策略后的形态漂移。
     */
    public static final List<String> FORBIDDEN_IN_ANSWER_STATE = List.of(
            "answerJson", "answer_json", "analysis", "isCorrect", "is_correct", "correctAnswerJson");

    private static final Map<Integer, String> TYPE_NAMES = Map.of(
            1, "单选题",
            2, "多选题",
            3, "判断题",
            4, "填空题",
            5, "简答题");

    /** 列表页摘要长度：实测题干长度 10~21 码点，20 既铺得开又能逼出一例真截断（qid 6） */
    public static final int TITLE_BRIEF_MAX = 20;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public QuestionVO toQuestionVO(QbQuestion question, List<QbQuestionOption> options) {
        QuestionVO vo = new QuestionVO();
        vo.setQuestionId(question.getId());
        vo.setSubjectId(question.getSubjectId());
        vo.setType(question.getType());
        vo.setTypeName(question.getType() == null
                ? "未知题型"
                : TYPE_NAMES.getOrDefault(question.getType(), "未知题型"));
        vo.setTitle(question.getTitle());
        vo.setDifficulty(question.getDifficulty());
        vo.setTags(parseTags(question.getTagsJson()));
        vo.setOptions(toOptions(options));
        return vo;
    }

    private List<QuestionVO.OptionVO> toOptions(List<QbQuestionOption> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        List<QuestionVO.OptionVO> out = new ArrayList<>(options.size());
        for (QbQuestionOption option : options) {
            QuestionVO.OptionVO ov = new QuestionVO.OptionVO();
            ov.setOptionKey(option.getOptionKey());
            ov.setContent(option.getContent());
            out.add(ov);
        }
        return out;
    }

    /** 标签解析的唯一出口：题目列表的行也用它，不在服务里再写一遍 readValue */
    public static List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(tagsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            // 标签是展示性数据，解析失败不能连累取题；写入方是 AIModule，改坏它不需要刷题页陪葬
            log.warn("tags_json 解析失败，按无标签返回: {}", tagsJson);
            return List.of();
        }
    }

    /**
     * 列表页标题摘要（spec 5.2 的 titleBrief）。
     * 先折叠空白再截断：错题本与收藏本的卡片是单行，串里的换行或多空格会把布局顶歪。
     * （实测 2026-10-24 七道题干没有换行，折叠是针对将来录题数据的防御，不是修现有 bug。）
     */
    public static String titleBrief(String title) {
        if (title == null) {
            return null;
        }
        String flat = title.replaceAll("\\s+", " ").trim();
        if (flat.length() <= TITLE_BRIEF_MAX) {
            return flat;
        }
        return flat.substring(0, TITLE_BRIEF_MAX - 1) + "…";
    }
}