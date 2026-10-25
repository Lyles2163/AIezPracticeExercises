package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leon.practicemodule.pojo.QbQuestionOption;

import java.util.Collection;
import java.util.List;

@Mapper
public interface QbQuestionOptionMapper extends BaseMapper<QbQuestionOption> {

    /** 单选判分的"字母必须在选项集内"校验用。只读 option_key，不碰 is_correct（spec 5.5） */
    @Select("SELECT option_key FROM qb_question_option WHERE question_id = #{questionId} ORDER BY sort_order, id")
    List<String> selectOptionKeys(@Param("questionId") Long questionId);

    /**
     * 取题响应要展示的选项：只选 option_key/content/sort_order，**不选 is_correct**（spec 5.5）。
     * 实体里 isCorrect 因此是 null——这不是遗漏，是"后端永不读真值"这条约束的可证形态，
     * QuestionReadSqlDbIT.displayOptionsCarryNoCorrectness 就是把 null 钉成断言。
     */
    @Select("SELECT id, question_id, option_key, content, sort_order FROM qb_question_option "
            + "WHERE question_id = #{questionId} ORDER BY sort_order, id")
    List<QbQuestionOption> selectDisplayOptions(@Param("questionId") Long questionId);

    /**
     * 一批题的选项键，交卷时一次取齐（零 N+1）。判分要它只为"单选题的答案字母在集合内吗"，
     * 仍不选 {@code is_correct}（spec 5.5）。
     *
     * <p>IN 的判空在<b>调用方</b>：本模块的纪律是"空集合先发 SQL = MySQL 语法错
     * = 兜底 handler 谎报服务器繁忙"，{@code ExamBriefs.load} 就是那道闸。
     */
    @Select("<script>SELECT question_id AS questionId, option_key AS optionKey"
            + " FROM qb_question_option WHERE question_id IN"
            + " <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + " ORDER BY question_id, sort_order, id</script>")
    List<QuestionOptionKeyRow> selectOptionKeysByQuestions(@Param("ids") Collection<Long> ids);
}