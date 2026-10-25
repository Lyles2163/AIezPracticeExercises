package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
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

    /**
     * 该用户该卷的当前草稿。业务上最多一条（status=1 的行只由 start 建，交卷即终态），
     * 但 {@code ORDER BY id DESC LIMIT 1} 不是多余的：万一有人手改库留下两条草稿，
     * 返回最新那条也符合"继续考试"的语义，而 selectOne 会直接抛 TooManyResultsException。
     */
    @Select("SELECT * FROM qb_exam_attempt WHERE user_id = #{userId} AND paper_id = #{paperId}"
            + " AND status = 1 ORDER BY id DESC LIMIT 1")
    QbExamAttempt selectDraftByUserAndPaper(@Param("userId") Long userId,
                                            @Param("paperId") Long paperId);

    /** 第几次作答 = MAX(seq_no)+1。没有行时回 0，调用方 +1 得 1 */
    @Select("SELECT COALESCE(MAX(seq_no), 0) FROM qb_exam_attempt"
            + " WHERE user_id = #{userId} AND paper_id = #{paperId}")
    int selectMaxSeqNo(@Param("userId") Long userId, @Param("paperId") Long paperId);

    /**
     * 幂等闸门（裁定四）：<b>只有草稿能被推成终态</b>，一条 UPDATE 同时写完分数快照并把
     * {@code answered_json} 置 NULL。回 0 行就是"已经交过了"，不靠先查后判——那是 check-then-act，
     * 并发双击会双重计分。
     *
     * <p>{@code user_id} 也在 WHERE 里：越权 attemptId 于是与"已交过"同样回 0 行，
     * 归属检查不需要另发一条 SQL（service 层仍先读一次做 40300/40400 的区分）。
     */
    @Update("UPDATE qb_exam_attempt SET status = #{status}, submit_at = #{submitAt},"
            + " duration_used_ms = #{durationUsedMs}, score = #{score},"
            + " correct_count = #{correctCount}, wrong_count = #{wrongCount},"
            + " unanswered_count = #{unansweredCount}, answered_json = NULL"
            + " WHERE id = #{id} AND user_id = #{userId} AND status = 1")
    int finalizeIfDraft(QbExamAttempt attempt);

    /** 该用户该卷的作答次数（历次成绩单分页的 total；草稿也算，与 MAX(id) 那条口径一致） */
    @Select("SELECT COUNT(*) FROM qb_exam_attempt WHERE user_id = #{userId} AND paper_id = #{paperId}")
    long countForPaper(@Param("userId") Long userId, @Param("paperId") Long paperId);

    /**
     * 某张卷子的历次作答（卡片上"已提交次数"点进去的那页）。按 id 倒序：新一次在前，
     * 与段 4 的 {@code MAX(id)} 口径一致。
     */
    @Select("SELECT a.id AS attemptId, a.seq_no AS seqNo, a.status AS status, a.score AS score,"
            + " a.submit_at AS submitAt, a.duration_used_ms AS durationUsedMs,"
            + " a.correct_count AS correctCount, a.wrong_count AS wrongCount,"
            + " a.unanswered_count AS unansweredCount"
            + " FROM qb_exam_attempt a WHERE a.user_id = #{userId} AND a.paper_id = #{paperId}"
            + " ORDER BY a.id DESC LIMIT #{offset}, #{pageSize}")
    List<AttemptPageRow> selectAttemptPage(@Param("userId") Long userId, @Param("paperId") Long paperId,
                                           @Param("offset") long offset, @Param("pageSize") long pageSize);
}
