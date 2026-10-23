package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leon.practicemodule.pojo.QbQuestionOption;

import java.util.List;

@Mapper
public interface QbQuestionOptionMapper extends BaseMapper<QbQuestionOption> {

    /** 单选判分的"字母必须在选项集内"校验用。只读 option_key，不碰 is_correct（spec 5.5） */
    @Select("SELECT option_key FROM qb_question_option WHERE question_id = #{questionId} ORDER BY sort_order, id")
    List<String> selectOptionKeys(@Param("questionId") Long questionId);
}