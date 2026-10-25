package org.leon.practicemodule.service;

import org.leon.practicemodule.mapper.AttemptRecordCountRow;
import org.leon.practicemodule.mapper.AttemptSubmitCountRow;
import org.leon.practicemodule.mapper.LatestAttemptRow;
import org.leon.practicemodule.mapper.QbExamAttemptMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一批试卷的作答聚合：三条 SQL 取齐，一 paper 一条 {@link Progress}。
 *
 * <p>与段 3 的 {@code LatestAnswers} 同一写法：静态工具、无状态、**先挡空集合**
 * （{@code IN ()} 在 MySQL 是语法错，会掉进兜底 handler 的"服务器繁忙"）。
 * 做成 bean 会让两个服务的构造器各多一个参数，而单测里传 mock 反而更直。
 *
 * <p>三条而不是一条三段 JOIN：JOIN 三次会放大行数且难读，而一批最多 100 张卷，
 * 三条 SQL 在 dev 库上是毫秒级（{@code PaperSqlDbIT} 用真数据实测过翻页与聚合）。
 */
public final class PaperAttempts {

    /**  attempt 状态机里唯一的草稿态（1 草稿 / 2 已提交 / 3 超时自动提交） */
    private static final int STATUS_DRAFT = 1;

    /**
     * 一张卡片的四个作答数字。
     *
     * @param submitCount     只数终态（裁定三）
     * @param latestAttemptId MAX(id) 那次，草稿也算——"继续考试"要的就是它
     * @param latestStatus    与 latestAttemptId 同源；null 表示从没作答
     * @param answeredCount   草稿走 answered_json 的 key 数，终态走展开出的答题行数
     */
    public record Progress(long submitCount, Long latestAttemptId, Integer latestStatus, int answeredCount) {

        public static final Progress NONE = new Progress(0L, null, null, 0);
    }

    private PaperAttempts() {
    }

    public static Map<Long, Progress> of(QbExamAttemptMapper attemptMapper,
                                        QbUserAnswerRecordMapper recordMapper,
                                        Long userId,
                                        Collection<Long> paperIds) {
        if (paperIds == null || paperIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> submitCounts = new HashMap<>();
        for (AttemptSubmitCountRow row : attemptMapper.countSubmitted(userId, paperIds)) {
            submitCounts.put(row.paperId(), row.submitCount());
        }

        Map<Long, LatestAttemptRow> latest = new HashMap<>();
        List<Long> terminalAttemptIds = new ArrayList<>();
        for (LatestAttemptRow row : attemptMapper.selectLatestByPapers(userId, paperIds)) {
            latest.put(row.paperId(), row);
            if (row.status() != null && row.status() != STATUS_DRAFT) {
                terminalAttemptIds.add(row.attemptId());
            }
        }

        // 第三条 SQL 只在真有终态 attempt 时发：本段库里没有 attempt 行，卡片页就是两条 SQL
        Map<Long, Long> recordCounts = new HashMap<>();
        if (!terminalAttemptIds.isEmpty()) {
            for (AttemptRecordCountRow row : recordMapper.countByAttempts(userId, terminalAttemptIds)) {
                recordCounts.put(row.attemptId(), row.answeredCount());
            }
        }

        Map<Long, Progress> out = new HashMap<>();
        for (Long paperId : paperIds) {
            LatestAttemptRow row = latest.get(paperId);
            int answered;
            if (row == null || row.status() == null) {
                answered = 0;
            } else if (row.status() == STATUS_DRAFT) {
                answered = PaperCards.answeredJsonKeyCount(row.answeredJson());
            } else {
                answered = (int) (long) recordCounts.getOrDefault(row.attemptId(), 0L);
            }
            out.put(paperId, new Progress(submitCounts.getOrDefault(paperId, 0L),
                    row == null ? null : row.attemptId(),
                    row == null ? null : row.status(), answered));
        }
        return Map.copyOf(out);
    }
}
