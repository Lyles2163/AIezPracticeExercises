package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.AnswerSubmitReq;
import org.leon.practicemodule.pojo.SelfJudgeReq;
import org.leon.practicemodule.pojo.vo.AnswerSubmitVO;
import org.leon.practicemodule.pojo.vo.SelfJudgeVO;

public interface AnswerService {

    AnswerSubmitVO submit(Long userId, AnswerSubmitReq req);

    SelfJudgeVO selfJudge(Long userId, SelfJudgeReq req);
}