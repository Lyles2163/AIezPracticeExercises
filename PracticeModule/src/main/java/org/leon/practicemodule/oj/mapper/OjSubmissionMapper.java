package org.leon.practicemodule.oj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leon.practicemodule.oj.pojo.OjSubmission;

@Mapper
public interface OjSubmissionMapper extends BaseMapper<OjSubmission> {

    /** 该题的全部提交次数（含未判完的）。列表侧走 JOIN 聚合，这里是详情页单题用。 */
    @Select("SELECT COUNT(*) FROM oj_submission WHERE problem_id = #{problemId}")
    long countByProblemId(@Param("problemId") Long problemId);

    /** 该题通过的提交次数：多次 AC 各算一次，与列表的"提交通过率"口径一致。 */
    @Select("SELECT COUNT(*) FROM oj_submission WHERE problem_id = #{problemId} AND status = 'AC'")
    long countAcceptedByProblemId(@Param("problemId") Long problemId);

    /**
     * 置为 RUNNING。刻意无条件：判题是幂等的，服务重启导致消息重投时，
     * 允许把"判到一半"的 RUNNING 重判一遍，结果是确定的。
     */
    @Update("UPDATE oj_submission SET status = 'RUNNING', update_time = NOW(3) WHERE id = #{id}")
    int markRunning(@Param("id") Long id);

    /** 判完写回终态 */
    @Update("UPDATE oj_submission SET status = #{status}, score = #{passed}, total = #{total}, "
            + "time_used_ms = #{timeUsedMs}, error_msg = #{errorMsg}, update_time = NOW(3) "
            + "WHERE id = #{id}")
    int markFinished(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("passed") int passed,
                     @Param("total") int total,
                     @Param("timeUsedMs") long timeUsedMs,
                     @Param("errorMsg") String errorMsg);
}
