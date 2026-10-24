package org.leon.practicemodule.service.impl;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.FavoritePageRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserFavoriteMapper;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.vo.FavoriteItemVO;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.service.FavoriteService;
import org.leon.practicemodule.service.LatestAnswers;
import org.leon.practicemodule.service.PracticeMasker;
import org.leon.practicemodule.service.PracticePages;
import org.leon.practicemodule.service.SubjectGuards;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 收藏本。写只有一个动作：开关。行只落在 qb_user_favorite，不联动错题或统计。
 * uk_user_question(user_id, question_id) 实测存在，所以"一人一题一收藏"由数据库保证，
 * 代码不需要先查后插（那是并发下会出双行的 check-then-act）。
 */
@Service
public class FavoriteServiceImpl implements FavoriteService {

    private final QbUserFavoriteMapper favoriteMapper;
    private final QbQuestionMapper questionMapper;
    private final QbSubjectMapper subjectMapper;

    public FavoriteServiceImpl(QbUserFavoriteMapper favoriteMapper,
                               QbQuestionMapper questionMapper,
                               QbSubjectMapper subjectMapper) {
        this.favoriteMapper = favoriteMapper;
        this.questionMapper = questionMapper;
        this.subjectMapper = subjectMapper;
    }

    @Override
    public PageVO<FavoriteItemVO> page(Long userId, Long subjectId, Long pageNum, Long pageSize) {
        PracticePages.PageParam param = PracticePages.of(pageNum, pageSize);
        SubjectGuards.requireEnabled(subjectMapper, subjectId);

        long total = favoriteMapper.countFavorites(userId, subjectId);
        if (total == 0 || param.offset() >= total) {
            return PageVO.of(List.of(), total, param.pageNum(), param.pageSize());
        }
        List<FavoritePageRow> rows =
                favoriteMapper.selectFavoritePage(userId, subjectId, param.offset(), param.pageSize());
        List<Long> ids = rows.stream().map(FavoritePageRow::questionId).toList();
        Map<Long, Long> positions = LatestAnswers.positions(questionMapper, ids);

        List<FavoriteItemVO> items = new ArrayList<>(rows.size());
        for (FavoritePageRow row : rows) {
            FavoriteItemVO item = new FavoriteItemVO();
            item.setQuestionId(row.questionId());
            Long pos = positions.get(row.questionId());
            item.setIndex(pos == null ? null : pos.intValue());
            item.setType(row.type());
            item.setTitleBrief(PracticeMasker.titleBrief(row.title()));
            item.setCreatedAt(row.createdAt() == null ? null : row.createdAt().getTime());
            items.add(item);
        }
        return PageVO.of(items, total, param.pageNum(), param.pageSize());
    }

    @Override
    @Transactional
    public boolean toggle(Long userId, Long subjectId, Long questionId) {
        if (subjectId == null || questionId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        if (favoriteMapper.deleteByUserAndQuestion(userId, questionId) > 0) {
            // 取消收藏不核对题目状态（裁定五）：下架题的收藏若还要 status=1 才能删，就永远删不掉
            return false;
        }
        QbQuestion question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "题目不存在");
        }
        if (question.getStatus() == null || question.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "题目已下架");
        }
        if (!subjectId.equals(question.getSubjectId())) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "题目不属于该科目");
        }
        // 插入返回值一律不看：实测 Connector/J 默认 useAffectedRows=false，
        // ON DUPLICATE KEY UPDATE id=id 这种空更新照样回 1（Task 3.1 的 db-it 记了这条）。
        // 唯一可信的判据是回读存在性——并发下这一下也可能没落下任何行。
        favoriteMapper.insertIgnoreDuplicate(userId, question.getSubjectId(), questionId);
        return favoriteMapper.countByUserAndQuestion(userId, questionId) > 0;
    }
}
