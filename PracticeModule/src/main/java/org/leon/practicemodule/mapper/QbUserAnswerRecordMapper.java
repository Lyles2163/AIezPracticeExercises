package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.leon.practicemodule.pojo.QbUserAnswerRecord;

@Mapper
public interface QbUserAnswerRecordMapper extends BaseMapper<QbUserAnswerRecord> {

    /**
     * 简答自评的幂等闸门：只有"待自评"（is_correct = -1）的行能被改写一次。
     * 返回 0 表示已自评过（或不是这个用户的行）——统计与错题本据此不再联动。
     */
    @Update("UPDATE qb_user_answer_record SET is_correct = #{isCorrect} "
            + "WHERE id = #{recordId} AND user_id = #{userId} AND is_correct = -1")
    int judgeIfPending(@Param("recordId") Long recordId,
                       @Param("userId") Long userId,
                       @Param("isCorrect") int isCorrect);
}