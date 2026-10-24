package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leon.practicemodule.pojo.QbUserWrong;

import java.util.List;

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

    /** 错题本计数。JOIN qb_question 只为过滤已下架题（裁定五：列表一律只含 status=1） */
    @Select("<script>SELECT COUNT(*) FROM qb_user_wrong w JOIN qb_question q ON q.id = w.question_id "
            + "WHERE w.user_id = #{userId} AND w.subject_id = #{subjectId} AND q.status = 1 "
            + "<if test='status != null'>AND w.status = #{status}</if></script>")
    long countWrong(@Param("userId") Long userId, @Param("subjectId") Long subjectId,
                    @Param("status") Integer status);

    /** 错题本按"最近犯错时间"倒序（最新踩坑的排前面），并列用 id 兜底保证翻页稳定 */
    @Select("<script>SELECT w.question_id AS questionId, w.wrong_count AS wrongCount, "
            + "w.last_wrong_at AS lastWrongAt, w.status AS status, q.type AS type, q.title AS title "
            + "FROM qb_user_wrong w JOIN qb_question q ON q.id = w.question_id "
            + "WHERE w.user_id = #{userId} AND w.subject_id = #{subjectId} AND q.status = 1 "
            + "<if test='status != null'>AND w.status = #{status}</if> "
            + "ORDER BY w.last_wrong_at DESC, w.id DESC LIMIT #{offset}, #{pageSize}</script>")
    List<WrongPageRow> selectWrongPage(@Param("userId") Long userId, @Param("subjectId") Long subjectId,
                                       @Param("status") Integer status,
                                       @Param("offset") long offset, @Param("pageSize") long pageSize);

    /**
     * 智能练习（mode=3）的优先题：本科目未掌握错题里 last_wrong_at 最早的一题——
     * "最早"就是最久没被复习过的那道，比"最新错的"更符合"先补旧账"的直觉。
     * 无候选返回 null，由调用方回落到顺序断点。
     */
    @Select("SELECT w.question_id FROM qb_user_wrong w JOIN qb_question q ON q.id = w.question_id "
            + "WHERE w.user_id = #{userId} AND w.subject_id = #{subjectId} AND w.status = 1 AND q.status = 1 "
            + "ORDER BY w.last_wrong_at, w.id LIMIT 1")
    Long selectSmartCandidateQuestionId(@Param("userId") Long userId, @Param("subjectId") Long subjectId);
}