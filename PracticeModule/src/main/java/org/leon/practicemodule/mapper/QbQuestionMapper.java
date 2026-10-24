package org.leon.practicemodule.mapper;

import org.leon.practicemodule.pojo.QbQuestion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
* @author Administrator
* @description 针对表【qb_question(题库-题目表)】的数据库操作Mapper
* @createDate 2026-09-22 09:43:25
* @Entity generator.pojo.QbQuestion
*/
@Mapper
public interface QbQuestionMapper extends BaseMapper<QbQuestion> {

    /** 科目内启用题总数：分页 total 与"这道科目还剩题吗"共用，走 idx_subject_status */
    @Select("SELECT COUNT(*) FROM qb_question WHERE subject_id = #{subjectId} AND status = 1")
    long countEnabledBySubject(@Param("subjectId") Long subjectId);

    /**
     * 题目列表的一页。排序键必须是 (sort_order, id)：实测同科目内 sort_order 会重复
     * （科目 1 的 qid 1 与 qid 6 都是 1），只按 sort_order 排会让翻页在并列行之间
     * 返回重复题或漏题。裸 LIMIT 不用分页插件——本模块没有 PaginationInnerInterceptor。
     */
    @Select("SELECT id AS questionId, type, difficulty, tags_json AS tagsJson, sort_order AS sortOrder "
            + "FROM qb_question WHERE subject_id = #{subjectId} AND status = 1 "
            + "ORDER BY sort_order, id LIMIT #{offset}, #{pageSize}")
    List<QuestionPageRow> selectEnabledPage(@Param("subjectId") Long subjectId,
                                            @Param("offset") long offset,
                                            @Param("pageSize") long pageSize);

    /**
     * 一批题各自在本科目内的位置序号（裁定四的 index）。
     * 关联子查询每行一次，调用方一批最多 100 题，且内层命中 idx_owner_subject_status_sort。
     * 注意 <script> 里 '<' 必须写成 &lt;，否则在 XML 解析阶段就炸（不是 SQL 语法错）。
     */
    @Select("<script>SELECT q.id AS questionId, "
            + "(SELECT COUNT(*) FROM qb_question b WHERE b.subject_id = q.subject_id AND b.status = 1 "
            + "AND (b.sort_order &lt; q.sort_order OR (b.sort_order = q.sort_order AND b.id &lt;= q.id))) AS posNo "
            + "FROM qb_question q WHERE q.id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<QuestionPositionRow> selectPositions(@Param("ids") Collection<Long> ids);

    /**
     * 顺序练习的下一题：本科目启用中、且该用户没有练习类作答记录（answer_mode IN (1,3)）的第一题。
     *
     * 不用 spec §6.2 的 MAX(sort_order) 反推——实测证伪（裁定二）：只答过 qid 6 时 MAX=1，
     * "sort_order > 1" 会跳过未作答的 qid 1。QuestionReadSqlDbIT 里有新旧算法对照实验。
     * mode=2（考试）的记录不算进度：7.1 已决定考试不吃科目统计，同理也不该消耗练习进度，
     * 否则刷完一套 25 题的卷子回来这科的断点会被整段跳掉。
     */
    @Select("SELECT q.id FROM qb_question q "
            + "WHERE q.subject_id = #{subjectId} AND q.status = 1 "
            + "AND NOT EXISTS (SELECT 1 FROM qb_user_answer_record r WHERE r.user_id = #{userId} "
            + "AND r.subject_id = #{subjectId} AND r.question_id = q.id AND r.answer_mode IN (1,3)) "
            + "ORDER BY q.sort_order, q.id LIMIT 1")
    Long selectFirstUnansweredQuestionId(@Param("subjectId") Long subjectId,
                                         @Param("userId") Long userId);
}
