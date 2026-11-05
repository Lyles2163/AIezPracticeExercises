package org.leon.practicemodule.oj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leon.practicemodule.oj.pojo.OjTestcase;

import java.util.List;

@Mapper
public interface OjTestcaseMapper extends BaseMapper<OjTestcase> {

    /** 判题用：判题器要的是全部用例（含隐藏），走 uk_problem_seq */
    @Select("SELECT * FROM oj_testcase WHERE problem_id = #{problemId} ORDER BY seq")
    List<OjTestcase> selectByProblemId(@Param("problemId") Long problemId);

    /** 展示用：只取示例用例，隐藏用例绝不出接口 */
    @Select("SELECT * FROM oj_testcase WHERE problem_id = #{problemId} AND is_sample = 1 ORDER BY seq")
    List<OjTestcase> selectSamples(@Param("problemId") Long problemId);
}
