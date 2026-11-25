package org.leon.practicemodule.manage.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbQuestionOption;

import java.util.Collection;
import java.util.List;

/**
 * 个人题库管理专用查询与批量清理 Mapper。
 * 严格按 qb_subject.owner_id = #{ownerId} AND qb_subject.owner_id <> 1 隔离，
 * 避免与答题态只读 Mapper 混用（答题态 Mapper 永远不读 answer_json / is_correct）。
 */
@Mapper
public interface MyQuestionManageMapper {

    @Select("<script>SELECT COUNT(*) FROM qb_question q"
            + " JOIN qb_subject s ON s.id = q.subject_id"
            + "<if test='batchId != null'> JOIN qb_import_batch_item bi ON bi.question_id = q.id AND bi.batch_id = #{batchId}</if>"
            + " WHERE s.owner_id = #{ownerId} AND s.owner_id &lt;&gt; 1"
            + "<if test='subjectId != null'> AND q.subject_id = #{subjectId}</if>"
            + "<if test='type != null'> AND q.type = #{type}</if>"
            + "<if test='difficulty != null'> AND q.difficulty = #{difficulty}</if>"
            + "<if test='status != null'> AND q.status = #{status}</if>"
            + "<if test='keyword != null and keyword != \"\"'> AND (q.title LIKE CONCAT('%', #{keyword}, '%') OR q.analysis LIKE CONCAT('%', #{keyword}, '%'))</if>"
            + "</script>")
    long countMyQuestions(@Param("ownerId") Long ownerId,
                          @Param("subjectId") Long subjectId,
                          @Param("batchId") Long batchId,
                          @Param("type") Integer type,
                          @Param("difficulty") Integer difficulty,
                          @Param("status") Integer status,
                          @Param("keyword") String keyword);

    @Select("<script>SELECT q.id, q.subject_id, q.type, q.title, q.answer_json, q.analysis,"
            + " q.difficulty, q.tags_json, q.status, q.sort_order, q.created_at, q.updated_at"
            + " FROM qb_question q"
            + " JOIN qb_subject s ON s.id = q.subject_id"
            + "<if test='batchId != null'> JOIN qb_import_batch_item bi ON bi.question_id = q.id AND bi.batch_id = #{batchId}</if>"
            + " WHERE s.owner_id = #{ownerId} AND s.owner_id &lt;&gt; 1"
            + "<if test='subjectId != null'> AND q.subject_id = #{subjectId}</if>"
            + "<if test='type != null'> AND q.type = #{type}</if>"
            + "<if test='difficulty != null'> AND q.difficulty = #{difficulty}</if>"
            + "<if test='status != null'> AND q.status = #{status}</if>"
            + "<if test='keyword != null and keyword != \"\"'> AND (q.title LIKE CONCAT('%', #{keyword}, '%') OR q.analysis LIKE CONCAT('%', #{keyword}, '%'))</if>"
            + " ORDER BY q.id DESC LIMIT #{offset}, #{pageSize}</script>")
    List<QbQuestion> selectMyQuestionsPage(@Param("ownerId") Long ownerId,
                                           @Param("subjectId") Long subjectId,
                                           @Param("batchId") Long batchId,
                                           @Param("type") Integer type,
                                           @Param("difficulty") Integer difficulty,
                                           @Param("status") Integer status,
                                           @Param("keyword") String keyword,
                                           @Param("offset") long offset,
                                           @Param("pageSize") long pageSize);

    @Select("SELECT COALESCE(MAX(sort_order), 0) FROM qb_question WHERE subject_id = #{subjectId}")
    int selectMaxSortOrder(@Param("subjectId") Long subjectId);

    @Select("<script>SELECT id, question_id, option_key, content, is_correct, sort_order"
            + " FROM qb_question_option WHERE question_id IN"
            + " <foreach collection='questionIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " ORDER BY question_id, sort_order, id</script>")
    List<QbQuestionOption> selectManageOptionsByQuestions(@Param("questionIds") Collection<Long> questionIds);

    @Delete("<script>DELETE FROM qb_question_option WHERE question_id IN"
            + " <foreach collection='questionIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteOptionsByQuestionIds(@Param("questionIds") Collection<Long> questionIds);

    @Delete("<script>DELETE FROM qb_question WHERE id IN"
            + " <foreach collection='questionIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteQuestionsByIds(@Param("questionIds") Collection<Long> questionIds);

    @Select("SELECT id FROM qb_question WHERE subject_id = #{subjectId}")
    List<Long> selectQuestionIdsBySubject(@Param("subjectId") Long subjectId);

    @Select("<script>SELECT COUNT(*) FROM qb_paper WHERE owner_id = #{ownerId} AND owner_id &lt;&gt; 1"
            + "<if test='subjectId != null'> AND subject_id = #{subjectId}</if>"
            + "</script>")
    long countMyPapers(@Param("ownerId") Long ownerId, @Param("subjectId") Long subjectId);

    @Select("<script>SELECT id, subject_id, owner_id, title, exam_year, exam_month, set_no,"
            + " duration_minutes, total_score, question_count, structure_json, description,"
            + " status, sort_order, created_at, updated_at"
            + " FROM qb_paper WHERE owner_id = #{ownerId} AND owner_id &lt;&gt; 1"
            + "<if test='subjectId != null'> AND subject_id = #{subjectId}</if>"
            + " ORDER BY id DESC LIMIT #{offset}, #{pageSize}</script>")
    List<QbPaper> selectMyPapersPage(@Param("ownerId") Long ownerId,
                                     @Param("subjectId") Long subjectId,
                                     @Param("offset") long offset,
                                     @Param("pageSize") long pageSize);

    @Select("SELECT id, subject_id, owner_id, title, structure_json, status FROM qb_paper"
            + " WHERE owner_id = #{ownerId} AND subject_id = #{subjectId}")
    List<QbPaper> selectMyPapersBySubject(@Param("ownerId") Long ownerId, @Param("subjectId") Long subjectId);

    @Delete("DELETE FROM qb_paper WHERE owner_id = #{ownerId} AND subject_id = #{subjectId}")
    int deleteMyPapersBySubject(@Param("ownerId") Long ownerId, @Param("subjectId") Long subjectId);
}
