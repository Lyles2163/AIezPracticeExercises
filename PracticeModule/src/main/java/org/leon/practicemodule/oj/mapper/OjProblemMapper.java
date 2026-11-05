package org.leon.practicemodule.oj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leon.practicemodule.oj.pojo.OjProblem;
import org.leon.practicemodule.oj.pojo.vo.OjProblemItemVO;

import java.util.List;

@Mapper
public interface OjProblemMapper extends BaseMapper<OjProblem> {

    /**
     * 题目列表：本模块没有分页插件，裸 LIMIT，调用方自己算 offset。
     * <p>
     * 提交统计走 LEFT JOIN 聚合子查询而不是逐题再查一次：一次列表请求 20 题就是 20 条
     * 额外 SQL（N+1）。没有提交记录的题目由 COALESCE 补 0，不会因为 LEFT JOIN 丢行。
     * <p>
     * 通过率在 SQL 里算：accepted / total 的除法要在数据库侧转成小数，
     * 1.0 * 是为了避免整数相除被截断成 0。
     */
    @Select("SELECT p.id, p.title, p.difficulty, "
            + "p.time_limit_ms AS timeLimitMs, p.memory_limit_mb AS memoryLimitMb, "
            + "COALESCE(s.total_count, 0) AS submitCount, "
            + "COALESCE(s.ac_count, 0) AS acceptedCount, "
            + "COALESCE(s.accept_rate, 0) AS acceptRate "
            + "FROM oj_problem p "
            + "LEFT JOIN (SELECT problem_id, "
            + "                  COUNT(*) AS total_count, "
            + "                  SUM(CASE WHEN status = 'AC' THEN 1 ELSE 0 END) AS ac_count, "
            + "                  ROUND(SUM(CASE WHEN status = 'AC' THEN 1 ELSE 0 END) * 1.0 / COUNT(*), 2) "
            + "                      AS accept_rate "
            + "           FROM oj_submission GROUP BY problem_id) s "
            + "  ON s.problem_id = p.id "
            + "WHERE p.status = 1 ORDER BY p.id LIMIT #{offset}, #{pageSize}")
    List<OjProblemItemVO> selectEnabledPage(@Param("offset") long offset,
                                            @Param("pageSize") long pageSize);

    /** 该题的全部提交次数（含未判完的）。列表侧走 JOIN 聚合，这里是详情页单题用。 */
    @Select("SELECT COUNT(*) FROM oj_submission WHERE problem_id = #{problemId}")
    long countByProblemId(@Param("problemId") Long problemId);

    /** 该题通过的提交次数：多次 AC 各算一次，与列表的"提交通过率"口径一致。 */
    @Select("SELECT COUNT(*) FROM oj_submission WHERE problem_id = #{problemId} AND status = 'AC'")
    long countAcceptedByProblemId(@Param("problemId") Long problemId);

}
