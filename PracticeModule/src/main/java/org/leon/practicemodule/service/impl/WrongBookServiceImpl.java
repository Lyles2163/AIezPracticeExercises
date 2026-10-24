package org.leon.practicemodule.service.impl;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.LatestAnswerRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.mapper.WrongPageRow;
import org.leon.practicemodule.pojo.QbUserWrong;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.WrongItemVO;
import org.leon.practicemodule.service.LatestAnswers;
import org.leon.practicemodule.service.PracticeMasker;
import org.leon.practicemodule.service.PracticePages;
import org.leon.practicemodule.service.SubjectGuards;
import org.leon.practicemodule.service.WrongBookService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 错题本读侧与"已掌握"写。
 * 错题行的产生（答错 upsert、答对置掌握）是段 2 的 applyOutcome，本类只多一个动作：
 * 用户手动点"已掌握"，用的还是段 2 那条条件更新——所以错题本的写口径全仓只有一份 SQL。
 * 错题行不因题目下架而删（裁定五）：重新上架就恢复，页面上看不见而已。
 */
@Service
public class WrongBookServiceImpl implements WrongBookService {

    private final QbUserWrongMapper wrongMapper;
    private final QbQuestionMapper questionMapper;
    private final QbUserAnswerRecordMapper recordMapper;
    private final QbSubjectMapper subjectMapper;

    public WrongBookServiceImpl(QbUserWrongMapper wrongMapper,
                                QbQuestionMapper questionMapper,
                                QbUserAnswerRecordMapper recordMapper,
                                QbSubjectMapper subjectMapper) {
        this.wrongMapper = wrongMapper;
        this.questionMapper = questionMapper;
        this.recordMapper = recordMapper;
        this.subjectMapper = subjectMapper;
    }

    @Override
    public PageVO<WrongItemVO> page(Long userId, Long subjectId, Integer status, Long pageNum, Long pageSize) {
        PracticePages.PageParam param = PracticePages.of(pageNum, pageSize);
        if (status != null && status != 1 && status != 2) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "掌握状态只能是 1未掌握 或 2已掌握");
        }
        SubjectGuards.requireEnabled(subjectMapper, subjectId);

        long total = wrongMapper.countWrong(userId, subjectId, status);
        if (total == 0 || param.offset() >= total) {
            return PageVO.of(List.of(), total, param.pageNum(), param.pageSize());
        }
        List<WrongPageRow> rows =
                wrongMapper.selectWrongPage(userId, subjectId, status, param.offset(), param.pageSize());
        List<Long> ids = rows.stream().map(WrongPageRow::questionId).toList();
        Map<Long, Long> positions = LatestAnswers.positions(questionMapper, ids);
        Map<Long, LatestAnswerRow> latest = LatestAnswers.byQuestion(recordMapper, userId, ids);

        List<WrongItemVO> items = new ArrayList<>(rows.size());
        for (WrongPageRow row : rows) {
            WrongItemVO item = new WrongItemVO();
            item.setQuestionId(row.questionId());
            Long pos = positions.get(row.questionId());
            item.setIndex(pos == null ? null : pos.intValue());
            item.setType(row.type());
            item.setTitleBrief(PracticeMasker.titleBrief(row.title()));
            item.setWrongCount(row.wrongCount());
            item.setLastWrongAt(row.lastWrongAt() == null ? null : row.lastWrongAt().getTime());
            item.setStatus(row.status());
            LatestAnswerRow last = latest.get(row.questionId());
            item.setLastUserAnswerJson(last == null ? null : last.userAnswerJson());
            items.add(item);
        }
        return PageVO.of(items, total, param.pageNum(), param.pageSize());
    }

    @Override
    public boolean master(Long userId, Long questionId) {
        if (questionId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        if (wrongMapper.masterIfUnmastered(userId, questionId) == 1) {
            return true;
        }
        QbUserWrong row = wrongMapper.selectByUserAndQuestion(userId, questionId);
        if (row == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "错题记录不存在");
        }
        if (row.getStatus() != null && row.getStatus() == 2) {
            return true;   // 已经掌握过：幂等
        }
        // 行在、status 既不是 1 也不是 2 —— 库里出现了我们没定义过的值，别假装成功
        throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "错题状态数据异常");
    }
}
