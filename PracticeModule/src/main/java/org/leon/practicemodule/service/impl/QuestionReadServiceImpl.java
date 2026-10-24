package org.leon.practicemodule.service.impl;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.LatestAnswerRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserFavoriteMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.mapper.QuestionPageRow;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.QuestionCurrentVO;
import org.leon.practicemodule.pojo.vo.QuestionItemVO;
import org.leon.practicemodule.pojo.vo.QuestionVO;
import org.leon.practicemodule.service.AnswerStatus;
import org.leon.practicemodule.service.LatestAnswers;
import org.leon.practicemodule.service.PracticeMasker;
import org.leon.practicemodule.service.PracticePages;
import org.leon.practicemodule.service.QuestionReadService;
import org.leon.practicemodule.service.SubjectGuards;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 题库读侧。三段式：计数 → 一页行 → 批量补齐，任何列表都不在循环里查库。
 * pageSize 上限 100（PracticePages），所以每次批量补齐的 IN 列表最多 100 项。
 */
@Service
public class QuestionReadServiceImpl implements QuestionReadService {

    private final QbQuestionMapper questionMapper;
    private final QbUserAnswerRecordMapper recordMapper;
    private final QbUserFavoriteMapper favoriteMapper;
    private final QbSubjectMapper subjectMapper;
    private final QbUserWrongMapper wrongMapper;
    private final QbQuestionOptionMapper optionMapper;
    private final PracticeMasker masker;

    public QuestionReadServiceImpl(QbQuestionMapper questionMapper,
                                   QbUserAnswerRecordMapper recordMapper,
                                   QbUserFavoriteMapper favoriteMapper,
                                   QbSubjectMapper subjectMapper,
                                   QbUserWrongMapper wrongMapper,
                                   QbQuestionOptionMapper optionMapper,
                                   PracticeMasker masker) {
        this.questionMapper = questionMapper;
        this.recordMapper = recordMapper;
        this.favoriteMapper = favoriteMapper;
        this.subjectMapper = subjectMapper;
        this.wrongMapper = wrongMapper;
        this.optionMapper = optionMapper;
        this.masker = masker;
    }

    @Override
    public PageVO<QuestionItemVO> page(Long userId, Long subjectId, Long pageNum, Long pageSize) {
        PracticePages.PageParam param = PracticePages.of(pageNum, pageSize);
        requireEnabledSubject(subjectId);

        long total = questionMapper.countEnabledBySubject(subjectId);
        if (total == 0 || param.offset() >= total) {
            // 越界页也照回真 total：前端要用它算总页数，给 0 就是把"还有题"说成"没题"
            return PageVO.of(List.of(), total, param.pageNum(), param.pageSize());
        }
        List<QuestionPageRow> rows =
                questionMapper.selectEnabledPage(subjectId, param.offset(), param.pageSize());
        return PageVO.of(toItems(userId, param.offset(), rows), total, param.pageNum(), param.pageSize());
    }

    private List<QuestionItemVO> toItems(Long userId, long offset, List<QuestionPageRow> rows) {
        List<Long> ids = rows.stream().map(QuestionPageRow::questionId).toList();
        Map<Long, LatestAnswerRow> latest = LatestAnswers.byQuestion(recordMapper, userId, ids);
        Set<Long> favorited = ids.isEmpty()
                ? Set.of()
                : new HashSet<>(favoriteMapper.selectFavoritedQuestionIds(userId, ids));

        List<QuestionItemVO> items = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            QuestionPageRow row = rows.get(i);
            QuestionItemVO item = new QuestionItemVO();
            // 页内序号可直接算：本页顺序就是 (sort_order,id) 全序，offset 上限 1e8 落在 int 内
            item.setIndex((int) (offset + i + 1));
            item.setQuestionId(row.questionId());
            item.setType(row.type());
            item.setDifficulty(row.difficulty());
            item.setTags(PracticeMasker.parseTags(row.tagsJson()));
            item.setAnswerStatus(AnswerStatus.of(latest.get(row.questionId())));
            item.setFavorited(favorited.contains(row.questionId()));
            items.add(item);
        }
        return items;
    }

    private void requireEnabledSubject(Long subjectId) {
        SubjectGuards.requireEnabled(subjectMapper, subjectId);
    }

    @Override
    public QuestionCurrentVO current(Long userId, Long subjectId, Integer mode, Long questionId) {
        if (subjectId == null && questionId == null) {
            throw badRequest("subjectId 与 questionId 至少给一个");
        }
        if (subjectId != null && questionId != null) {
            throw badRequest("subjectId 与 questionId 只能给一个");
        }
        if (questionId != null) {
            return buildCurrent(userId, loadVisibleQuestion(questionId));
        }

        requireEnabledSubject(subjectId);
        if (mode == null || (mode != 1 && mode != 3)) {
            throw badRequest("答题模式只能是 1顺序 或 3智能练习");
        }
        Long nextId = mode == 3 ? wrongMapper.selectSmartCandidateQuestionId(userId, subjectId) : null;
        if (nextId == null) {
            // 智能练习没有未掌握错题就退化成顺序，与"继续刷题"的直觉一致
            nextId = questionMapper.selectFirstUnansweredQuestionId(subjectId, userId);
        }
        if (nextId == null) {
            QuestionCurrentVO done = new QuestionCurrentVO();
            done.setFinished(true);
            return done;
        }
        // 断点 SQL 已过滤 status=1，这里再核对一次：题目在上一次查询与本次之间被下架也不能漏
        return buildCurrent(userId, loadVisibleQuestion(nextId));
    }

    /** 唯一的取题出口：题目字段一律经 PracticeMasker 复制，服务不自己拼任何题干字段 */
    private QuestionCurrentVO buildCurrent(Long userId, QbQuestion question) {
        QuestionVO vo = masker.toQuestionVO(question,
                optionMapper.selectDisplayOptions(question.getId()));
        Long pos = LatestAnswers.positions(questionMapper, List.of(question.getId()))
                .get(question.getId());
        vo.setIndex(pos == null ? null : pos.intValue());

        LatestAnswerRow latest = LatestAnswers.byQuestion(
                recordMapper, userId, List.of(question.getId())).get(question.getId());
        QuestionCurrentVO out = new QuestionCurrentVO();
        out.setQuestion(vo);
        out.setAnswerStatus(AnswerStatus.of(latest));
        out.setLastUserAnswerJson(latest == null ? null : latest.userAnswerJson());
        out.setPendingRecordId(AnswerStatus.selfJudgeable(latest) ? latest.recordId() : null);
        return out;
    }

    private QbQuestion loadVisibleQuestion(Long questionId) {
        QbQuestion question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "题目不存在");
        }
        if (question.getStatus() == null || question.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "题目已下架");
        }
        return question;
    }

    private static PracticeException badRequest(String msg) {
        return new PracticeException(PracticeErrorCode.BAD_REQUEST, msg);
    }
}
