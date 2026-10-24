package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leon.practicemodule.pojo.QbExamAttempt;

import java.util.Collection;
import java.util.List;

/**
 * 作答表的读侧聚合（段 4 只有读）：卡片与详情上的三个进度字段都从这里来。
 * 写侧在段 5 的考试状态机。
 *
 * <p>三个查询都必须"一批一个 SQL"、调用方在循环外（段 3 的零 N+1 纪律）。
 * 空集合由调用方挡（{@code PaperAttempts} 是那个唯一挡点）——
 * {@code IN ()} 在 MySQL 是语法错，会掉进兜底 handler 的"服务器繁忙"。
 */
@Mapper
public interface QbExamAttemptMapper extends BaseMapper<QbExamAttempt> {

    /** 已提交次数：只数终态（2 已提交 / 3 超时自动提交），草稿不占次数（段 4 裁定三） */
    @Select("<script>SELECT a.paper_id AS paperId, COUNT(*) AS submitCount"
            + " FROM qb_exam_attempt a"
            + " WHERE a.user_id = #{userId} AND a.status IN (2,3)"
            + " AND a.paper_id IN"
            + " <foreach collection='paperIds' item='p' open='(' separator=',' close=')'>#{p}</foreach>"
            + " GROUP BY a.paper_id</script>")
    List<AttemptSubmitCountRow> countSubmitted(@Param("userId") Long userId,
                                               @Param("paperIds") Collection<Long> paperIds);

    /** 每份试卷各自的最新一次作答（MAX(id)，草稿也算）——"继续考试"的入口 */
    @Select("<script>SELECT a.paper_id AS paperId, a.id AS attemptId, a.status AS status,"
            + " a.answered_json AS answeredJson"
            + " FROM qb_exam_attempt a"
            + " JOIN (SELECT paper_id, MAX(id) AS mid FROM qb_exam_attempt"
            + " WHERE user_id = #{userId} AND paper_id IN"
            + " <foreach collection='paperIds' item='p' open='(' separator=',' close=')'>#{p}</foreach>"
            + " GROUP BY paper_id) t ON t.mid = a.id</script>")
    List<LatestAttemptRow> selectLatestByPapers(@Param("userId") Long userId,
                                                @Param("paperIds") Collection<Long> paperIds);
}
