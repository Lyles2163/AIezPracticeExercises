package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leon.practicemodule.pojo.QbUserAnswerRecord;

import java.util.Collection;
import java.util.List;

@Mapper
public interface QbUserAnswerRecordMapper extends BaseMapper<QbUserAnswerRecord> {

    /**
     * 简答自评的幂等闸门：只有"待自评"（is_correct = -1）的行能被改写一次。
     * 返回 0 表示已自评过（或不是这个用户的行）——统计与错题本据此不再联动。
     */
    @Update("UPDATE qb_user_answer_record SET is_correct = #{isCorrect} "
            + "WHERE id = #{recordId} AND user_id = #{userId} AND is_correct = -1")
    int judgeIfPending(@Param("recordId") Long recordId,
                       @Param("userId") Long userId,
                       @Param("isCorrect") int isCorrect);

    /**
     * 一批题各自的最近一次作答：一题一行，取 MAX(id)。
     * 用 id 不用 created_at——实测该表同秒并发写入无法靠时间区分，而 id 是 AUTO_INCREMENT。
     * 不带 mode 过滤（与断点 SQL 的差别是刻意的，见段 3 裁定二）：answerStatus 的语义是
     * "我最近一次对这道题的作答"，考试作答同样是真作答；是否可自评另由 AnswerStatus 判。
     */
    @Select("<script>SELECT r.id AS recordId, r.question_id AS questionId, "
            + "r.user_answer_json AS userAnswerJson, r.is_correct AS isCorrect, r.answer_mode AS answerMode "
            + "FROM qb_user_answer_record r "
            + "JOIN (SELECT question_id, MAX(id) AS mid FROM qb_user_answer_record "
            + "WHERE user_id = #{userId} AND question_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> "
            + "GROUP BY question_id) t ON t.mid = r.id</script>")
    List<LatestAnswerRow> selectLatestByQuestions(@Param("userId") Long userId,
                                                  @Param("ids") Collection<Long> ids);

    /**
     * 一批 attempt 各自展开出的答题行数（= 终态作答的已答题数，段 4 裁定三的后半边）。
     *
     * <p>它挂在记录表的 mapper 上而不是 attempt 的 mapper 上：本模块的纪律是
     * "一个 mapper 管一张表"（{@code QbQuestionMapper.selectPositions} 也只查自己的表），
     * 查哪张表就归谁。段 4 只有读，段 5 的交卷展开才产生这些行。
     */
    @Select("<script>SELECT r.attempt_id AS attemptId, COUNT(*) AS answeredCount"
            + " FROM qb_user_answer_record r WHERE r.user_id = #{userId}"
            + " AND r.attempt_id IN"
            + " <foreach collection='attemptIds' item='a' open='(' separator=',' close=')'>#{a}</foreach>"
            + " GROUP BY r.attempt_id</script>")
    List<AttemptRecordCountRow> countByAttempts(@Param("userId") Long userId,
                                                @Param("attemptIds") Collection<Long> attemptIds);
}