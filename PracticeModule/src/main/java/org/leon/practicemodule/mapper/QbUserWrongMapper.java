package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leon.practicemodule.pojo.QbUserWrong;

@Mapper
public interface QbUserWrongMapper extends BaseMapper<QbUserWrong> {

    /**
     * 答错：有行则计数 +1 且 status 置回 1（已掌握再答错要回退，spec 7.3），无行则新建。
     * uk_user_question(user_id, question_id) 保证全程只有一行——实测该唯一键存在。
     */
    @Insert("INSERT INTO qb_user_wrong (user_id, subject_id, question_id, wrong_count, last_wrong_at, status, created_at, updated_at) "
            + "VALUES (#{userId}, #{subjectId}, #{questionId}, 1, NOW(), 1, NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE wrong_count = wrong_count + 1, last_wrong_at = NOW(), "
            + "status = 1, updated_at = NOW()")
    int upsertWrong(@Param("userId") Long userId,
                    @Param("subjectId") Long subjectId,
                    @Param("questionId") Long questionId);

    /** 答对：只把"未掌握"置为已掌握；从未错过的题不建行，已掌握的不动 */
    @Update("UPDATE qb_user_wrong SET status = 2, updated_at = NOW() "
            + "WHERE user_id = #{userId} AND question_id = #{questionId} AND status = 1")
    int masterIfUnmastered(@Param("userId") Long userId, @Param("questionId") Long questionId);

    @Select("SELECT id, user_id, subject_id, question_id, wrong_count, last_wrong_at, status, created_at, updated_at "
            + "FROM qb_user_wrong WHERE user_id = #{userId} AND question_id = #{questionId}")
    QbUserWrong selectByUserAndQuestion(@Param("userId") Long userId, @Param("questionId") Long questionId);
}