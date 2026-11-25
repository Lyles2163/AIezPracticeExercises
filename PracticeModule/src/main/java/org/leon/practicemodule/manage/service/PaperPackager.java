package org.leon.practicemodule.manage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.service.PaperStructure;
import org.leon.practicemodule.service.PaperStructureValidator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 套卷一键打包器：将一组个人题目组织成合法的 {@link PaperStructure} 并通过
 * {@link PaperStructureValidator} 四条写入校验后构造 {@link QbPaper}。
 */
@Component
public class PaperPackager {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String[] ROMANS = {
            "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
            "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX"
    };
    private static final Map<Integer, String> TYPE_LABELS = Map.of(
            1, "单选题",
            2, "多选题",
            3, "判断题",
            4, "填空题",
            5, "简答题"
    );

    public record PackItem(Long questionId, int type, String sectionTitle, BigDecimal score) {
    }

    public record PackCommand(
            Long ownerId,
            Long subjectId,
            String title,
            Integer examYear,
            Integer examMonth,
            Integer setNo,
            int durationMinutes,
            String description,
            BigDecimal defaultScore,
            List<PackItem> items
    ) {
    }

    public QbPaper buildAndValidate(PackCommand cmd, Map<Long, PaperStructureValidator.QuestionBrief> briefs) {
        if (cmd.title() == null || cmd.title().isBlank()) {
            throw badRequest("套卷名称不能为空");
        }
        String title = cmd.title().trim();
        if (title.length() > 200) {
            throw badRequest("套卷名称不能超过 200 字");
        }
        if (cmd.durationMinutes() <= 0 || cmd.durationMinutes() > 600) {
            throw badRequest("考试时长必须在 1~600 分钟之间");
        }
        if (cmd.examYear() != null && (cmd.examYear() < 1990 || cmd.examYear() > 2100)) {
            throw badRequest("年份范围不合法");
        }
        if (cmd.examMonth() != null && (cmd.examMonth() < 1 || cmd.examMonth() > 12)) {
            throw badRequest("月份必须在 1~12 之间");
        }
        if (cmd.setNo() != null && (cmd.setNo() < 1 || cmd.setNo() > 99)) {
            throw badRequest("套次必须在 1~99 之间");
        }
        if (cmd.items() == null || cmd.items().isEmpty()) {
            throw badRequest("至少需要选择 1 道题目才能打包成套卷");
        }

        BigDecimal fallbackScore = normalizePositiveScore(
                cmd.defaultScore() == null ? new BigDecimal("5.00") : cmd.defaultScore());

        // 按「文档章节标题（若存在）或题型」保持出现顺序分组
        LinkedHashMap<String, List<PackItem>> groups = new LinkedHashMap<>();
        for (PackItem item : cmd.items()) {
            String groupKey = (item.sectionTitle() != null && !item.sectionTitle().isBlank())
                    ? item.sectionTitle().trim()
                    : TYPE_LABELS.getOrDefault(item.type(), "综合题");
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(item);
        }

        int totalQuestions = cmd.items().size();
        int groupCount = groups.size();
        int[] partMinutes = distributeMinutes(cmd.durationMinutes(), groups, totalQuestions);

        List<PaperStructure.Part> parts = new ArrayList<>(groupCount);
        int globalIndex = 1;
        BigDecimal totalScore = BigDecimal.ZERO;
        int groupIdx = 0;

        for (Map.Entry<String, List<PackItem>> entry : groups.entrySet()) {
            String groupName = entry.getKey();
            List<PackItem> groupItems = entry.getValue();
            List<PaperStructure.QuestionRef> refs = new ArrayList<>(groupItems.size());
            BigDecimal partScore = BigDecimal.ZERO;

            for (PackItem item : groupItems) {
                BigDecimal qScore = item.score() != null
                        ? normalizePositiveScore(item.score())
                        : fallbackScore;
                refs.add(new PaperStructure.QuestionRef(globalIndex++, item.questionId(), qScore));
                partScore = partScore.add(qScore);
            }
            totalScore = totalScore.add(partScore);

            String roman = groupIdx < ROMANS.length ? ROMANS[groupIdx] : String.valueOf(groupIdx + 1);
            String cardLabel = groupName.length() > 16 ? groupName.substring(0, 16) : groupName;
            PaperStructure.Section section = new PaperStructure.Section(
                    "Section A",
                    groupName,
                    null,
                    null,
                    null,
                    refs
            );
            parts.add(new PaperStructure.Part(
                    roman,
                    groupName,
                    cardLabel,
                    partScore,
                    partMinutes[groupIdx],
                    List.of(section)
            ));
            groupIdx++;
        }

        if (totalScore.compareTo(new BigDecimal("9999.99")) > 0) {
            throw badRequest("试卷总分超过上限 9999.99");
        }

        PaperStructure structure = new PaperStructure(parts);
        PaperStructureValidator.validate(structure, totalQuestions, totalScore,
                cmd.durationMinutes(), cmd.subjectId(), briefs);

        QbPaper paper = new QbPaper();
        paper.setSubjectId(cmd.subjectId());
        paper.setOwnerId(cmd.ownerId());
        paper.setTitle(title);
        paper.setExamYear(cmd.examYear());
        paper.setExamMonth(cmd.examMonth());
        paper.setSetNo(cmd.setNo());
        paper.setDurationMinutes(cmd.durationMinutes());
        paper.setTotalScore(totalScore);
        paper.setQuestionCount(totalQuestions);
        paper.setStructureJson(serializeStructure(structure));
        paper.setDescription(trimTo(cmd.description(), 500));
        paper.setStatus(1);
        paper.setSortOrder(0);
        return paper;
    }

    /** 按题数比例分配各 Part 时长，确保 Σ minutes <= durationMinutes 且每段 >= 0。 */
    private static int[] distributeMinutes(int durationMinutes,
                                           LinkedHashMap<String, List<PackItem>> groups,
                                           int totalQuestions) {
        int n = groups.size();
        int[] out = new int[n];
        int used = 0;
        int i = 0;
        for (List<PackItem> list : groups.values()) {
            if (i == n - 1) {
                out[i] = Math.max(0, durationMinutes - used);
            } else {
                int share = (int) Math.floor((double) durationMinutes * list.size() / totalQuestions);
                int remaining = durationMinutes - used;
                out[i] = Math.min(Math.max(0, share), remaining);
                used += out[i];
            }
            i++;
        }
        return out;
    }

    private static BigDecimal normalizePositiveScore(BigDecimal score) {
        if (score == null || score.compareTo(BigDecimal.ZERO) <= 0
                || score.compareTo(new BigDecimal("1000")) > 0) {
            throw badRequest("单题分值必须在 (0, 1000] 之间");
        }
        return score.setScale(2, RoundingMode.HALF_UP);
    }

    private static String serializeStructure(PaperStructure structure) {
        try {
            return MAPPER.writeValueAsString(structure);
        } catch (Exception e) {
            throw new IllegalStateException("序列化卷面结构失败", e);
        }
    }

    private static String trimTo(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static PracticeException badRequest(String msg) {
        return new PracticeException(PracticeErrorCode.BAD_REQUEST, msg);
    }
}
