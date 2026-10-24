package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leon.practicemodule.pojo.QbPaper;

import java.util.List;

/**
 * 试卷表的只读查询。本模块永不写它：没有组卷管理端（spec 11 的第一行边界），
 * 样例卷由仓库外的 seed 脚本灌入，所以 BaseMapper 的 insert/update 在这里
 * 是"存在但无人调用"的能力，不再额外封方法。
 */
@Mapper
public interface QbPaperMapper extends BaseMapper<QbPaper> {

    /**
     * 卡片列表的计数。过滤条件必须与 {@link #selectPage} 逐字一致，
     * 否则 total 与 list 对不上——PageVO 的 pages 就是错的，前端翻页会翻到不存在的页。
     *
     * <p>可见性口径：status=1 且 owner_id 是公共（1）或本人。个人题库本期不实现，
     * 但把 owner 过滤现在就写进来，免得将来加私有卷时改的是查询而不是加查询。
     */
    @Select("<script>SELECT COUNT(*) FROM qb_paper p"
            + " WHERE p.subject_id = #{subjectId} AND p.status = 1"
            + " AND (p.owner_id = 1 OR p.owner_id = #{userId})"
            + "<if test='examYear != null'> AND p.exam_year = #{examYear}</if>"
            + "<if test='examMonth != null'> AND p.exam_month = #{examMonth}</if>"
            + "<if test='setNo != null'> AND p.set_no = #{setNo}</if>"
            + "</script>")
    long countForList(@Param("subjectId") Long subjectId,
                      @Param("examYear") Integer examYear,
                      @Param("examMonth") Integer examMonth,
                      @Param("setNo") Integer setNo,
                      @Param("userId") Long userId);

    /**
     * 卡片列表的一页。排序键必须是 (sort_order, id)：段 3 实测 qb_question.sort_order
     * 在科目内会重复（roadmap 第 17 条），试卷表沿用同一纪律——只按 sort_order 排不是全序，
     * 翻页会在并列行之间重行或漏行。裸 LIMIT，本模块没有分页插件。
     */
    @Select("<script>SELECT p.id AS paperId, p.subject_id AS subjectId, s.name AS subjectName,"
            + " p.title, p.exam_year AS examYear, p.exam_month AS examMonth, p.set_no AS setNo,"
            + " p.duration_minutes AS durationMinutes, p.total_score AS totalScore,"
            + " p.question_count AS questionCount"
            + " FROM qb_paper p JOIN qb_subject s ON s.id = p.subject_id"
            + " WHERE p.subject_id = #{subjectId} AND p.status = 1"
            + " AND (p.owner_id = 1 OR p.owner_id = #{userId})"
            + "<if test='examYear != null'> AND p.exam_year = #{examYear}</if>"
            + "<if test='examMonth != null'> AND p.exam_month = #{examMonth}</if>"
            + "<if test='setNo != null'> AND p.set_no = #{setNo}</if>"
            + " ORDER BY p.sort_order, p.id LIMIT #{offset}, #{pageSize}</script>")
    List<PaperCardRow> selectPage(@Param("subjectId") Long subjectId,
                                  @Param("examYear") Integer examYear,
                                  @Param("examMonth") Integer examMonth,
                                  @Param("setNo") Integer setNo,
                                  @Param("userId") Long userId,
                                  @Param("offset") long offset,
                                  @Param("pageSize") long pageSize);
}
