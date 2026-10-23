package org.leon.practicemodule.mapper;

import org.leon.practicemodule.pojo.QbQuestion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
* @author Administrator
* @description 针对表【qb_question(题库-题目表)】的数据库操作Mapper
* @createDate 2026-09-22 09:43:25
* @Entity generator.pojo.QbQuestion
*/
@Mapper
public interface QbQuestionMapper extends BaseMapper<QbQuestion> {

}




