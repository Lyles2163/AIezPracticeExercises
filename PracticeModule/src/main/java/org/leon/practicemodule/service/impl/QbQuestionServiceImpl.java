package org.leon.practicemodule.service.impl;


import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.service.QbQuestionService;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.springframework.stereotype.Service;

/**
* @author Administrator
* @description 针对表【qb_question(题库-题目表)】的数据库操作Service实现
* @createDate 2026-09-22 09:43:25
*/
@Service
public class QbQuestionServiceImpl extends ServiceImpl<QbQuestionMapper, QbQuestion>
    implements QbQuestionService{

}




