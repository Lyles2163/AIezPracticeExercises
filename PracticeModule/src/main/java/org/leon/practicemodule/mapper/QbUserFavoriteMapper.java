package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leon.practicemodule.pojo.QbUserFavorite;

import java.util.Collection;
import java.util.List;

@Mapper
public interface QbUserFavoriteMapper extends BaseMapper<QbUserFavorite> {

    /** 收藏本计数。JOIN qb_question 只为过滤已下架题（裁定五），收藏行本身不删 */
    @Select("SELECT COUNT(*) FROM qb_user_favorite f JOIN qb_question q ON q.id = f.question_id "
            + "WHERE f.user_id = #{userId} AND f.subject_id = #{subjectId} AND q.status = 1")
    long countFavorites(@Param("userId") Long userId, @Param("subjectId") Long subjectId);

    /** 收藏本按收藏时间倒序（刚收藏的排前面），并列用 id 兜底 */
    @Select("SELECT f.question_id AS questionId, f.created_at AS createdAt, q.type AS type, q.title AS title "
            + "FROM qb_user_favorite f JOIN qb_question q ON q.id = f.question_id "
            + "WHERE f.user_id = #{userId} AND f.subject_id = #{subjectId} AND q.status = 1 "
            + "ORDER BY f.created_at DESC, f.id DESC LIMIT #{offset}, #{pageSize}")
    List<FavoritePageRow> selectFavoritePage(@Param("userId") Long userId, @Param("subjectId") Long subjectId,
                                             @Param("offset") long offset, @Param("pageSize") long pageSize);

    /** 题目列表的 favorited 位：一条 IN 批量，零 N+1。空集合由调用方挡在门外 */
    @Select("<script>SELECT question_id FROM qb_user_favorite WHERE user_id = #{userId} AND question_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach></script>")
    List<Long> selectFavoritedQuestionIds(@Param("userId") Long userId, @Param("ids") Collection<Long> ids);

    @Delete("DELETE FROM qb_user_favorite WHERE user_id = #{userId} AND question_id = #{questionId}")
    int deleteByUserAndQuestion(@Param("userId") Long userId, @Param("questionId") Long questionId);

    /**
     * 并发双击的兜底：uk_user_question 已存在时 {@code id = id} 是合法的空更新，
     * 不报错、不重复建行——所以 toggle 不需要 catch DuplicateKeyException。
     */
    @Insert("INSERT INTO qb_user_favorite (user_id, subject_id, question_id, created_at) "
            + "VALUES (#{userId}, #{subjectId}, #{questionId}, NOW()) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insertIgnoreDuplicate(@Param("userId") Long userId, @Param("subjectId") Long subjectId,
                              @Param("questionId") Long questionId);

    /** toggle 之后回读真实状态：并发的另一手可能刚删掉，硬编 true 就是在说谎 */
    @Select("SELECT COUNT(*) FROM qb_user_favorite WHERE user_id = #{userId} AND question_id = #{questionId}")
    long countByUserAndQuestion(@Param("userId") Long userId, @Param("questionId") Long questionId);
}
