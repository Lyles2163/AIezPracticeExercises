package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
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

    /** 逐题作答累加。只在 answer_mode IN (1,3) 调用（spec 7.1）——考试交卷不走这里 */
    @Insert("INSERT INTO qb_user_subject_stat (user_id, subject_id, total_count, correct_count, wrong_count, last_answer_at, created_at, updated_at) "
            + "VALUES (#{userId}, #{subjectId}, 1, #{correct}, #{wrong}, NOW(), NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE total_count = total_count + 1, "
            + "correct_count = correct_count + #{correct}, wrong_count = wrong_count + #{wrong}, "
            + "last_answer_at = NOW(), updated_at = NOW()")
    int upsertAccumulate(@Param("userId") Long userId,
                         @Param("subjectId") Long subjectId,
                         @Param("correct") int correct,
                         @Param("wrong") int wrong);

    @Select("SELECT id, user_id, subject_id, total_count, correct_count, wrong_count, last_answer_at, created_at, updated_at "
            + "FROM qb_user_subject_stat WHERE user_id = #{userId} AND subject_id = #{subjectId}")
    QbUserSubjectStat selectByUserAndSubject(@Param("userId") Long userId, @Param("subjectId") Long subjectId);
}
