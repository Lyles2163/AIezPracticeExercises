package org.leon.practicemodule.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 卷面结构（spec 4.3）的内存形态：parts → sections → questions 三层。
 *
 * <p>这里只有数据与三个聚合便捷方法，**不含校验**（校验在
 * {@link PaperStructureValidator}）、也不含解析（解析在
 * {@link PaperStructureParser}）。分开的理由是段 5 的 {@code exam/start} 要拿这份结构
 * 逐题取题，那时四条校验已在写入时做过，不该把校验逻辑缠进读取路径的每个访问器里。
 *
 * <p>record 的访问器是 {@code roman()}/{@code minutes()} 而不是 {@code getXxx()}——
 * 刻意与响应契约区分开：这是内部数据形态，出网的是 {@code pojo/vo/PaperDetailVO}。
 */
public record PaperStructure(List<Part> parts) {

    /**
     * @param score   该部分的展示分值（卷面上 "248.5分" 那枚徽章）
     * @param minutes 该部分的用时，四条校验的规则 4 累加它
     */
    public record Part(String roman, String name, String cardLabel, BigDecimal score,
                       long minutes, List<Section> sections) {
    }

    /**
     * direction / audioUrl / originalText 三个字段可为 null（阅读部分的 section 就没有音频与原文）。
     * originalText 是"听力原文即答案"，读侧要受段 4 裁定二的交卷闸门约束。
     */
    public record Section(String label, String subTitle, String direction, String audioUrl,
                          String originalText, List<QuestionRef> questions) {
    }

    /**
     * @param index 卷面题号，跨 part 全局从 1 连续（规则 1 保证）
     */
    public record QuestionRef(int index, Long questionId, BigDecimal score) {
    }

    /** 按 parts→sections→questions 的书写顺序展平：题号连续性的判据就是它 */
    public List<QuestionRef> questionsInOrder() {
        List<QuestionRef> out = new ArrayList<>();
        for (Part part : parts) {
            for (Section section : part.sections()) {
                out.addAll(section.questions());
            }
        }
        return out;
    }

    public BigDecimal sumQuestionScore() {
        BigDecimal sum = BigDecimal.ZERO;
        for (QuestionRef ref : questionsInOrder()) {
            sum = sum.add(ref.score());
        }
        return sum;
    }

    public long sumPartMinutes() {
        long sum = 0L;
        for (Part part : parts) {
            sum += part.minutes();
        }
        return sum;
    }
}
