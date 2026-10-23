package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leon.practicemodule.pojo.QbUserSubjectStat;

import java.util.Collection;
import java.util.List;

@Mapper
public interface QbUserSubjectStatMapper extends BaseMapper<QbUserSubjectStat> {

    /** 一次查出某用户在多个科目下的统计，避免科目列表里的 N+1 */
    @Select("<script>SELECT id, user_id, subject_id, total_count, correct_count, wrong_count, "
            + "last_answer_at, created_at, updated_at FROM qb_user_subject_stat "
            + "WHERE user_id = #{userId} AND subject_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<QbUserSubjectStat> selectByUserAndSubjects(@Param("userId") Long userId,
                                                    @Param("ids") Collection<Long> ids);
}
