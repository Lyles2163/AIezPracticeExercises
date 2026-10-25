package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.vo.PaperDetailVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 卷面骨架（parts→sections→questions）的出网装配，{@code paper/detail}（段 4）与
 * {@code exam/start}（段 5）共用同一份，所以"答题卡与 Part 标题在两个接口长得不一样"
 * 这种 bug 结构上不可能出现。
 *
 * <p>裁定二（交过卷才给听力原文）的判据也收在这里：{@link #originalTextVisible}。
 * 两个出口都用它，将来加"待自评才能看原文"这类规则时只有一个地方要改。
 */
public final class PaperSkeleton {

    private PaperSkeleton() {
    }

    /** 交过卷才给听力原文（段 4 裁定二）。判据就是聚合出来的 submitCount，不多发一条 SQL */
    public static boolean originalTextVisible(long submitCount) {
        return submitCount > 0L;
    }

    /**
     * 骨架装配。三层都逐字段复制，不整体搬 {@code PaperStructure}：
     * 出网形态与内存形态必须是两个类型，否则将来给 record 加一个内部字段就直接漏到响应里
     * （段 3 的两次伪阳性实测都是这个形状）。
     */
    public static List<PaperDetailVO.PartVO> toPartVOs(PaperStructure structure, boolean originalTextVisible) {
        List<PaperDetailVO.PartVO> parts = new ArrayList<>(structure.parts().size());
        for (PaperStructure.Part part : structure.parts()) {
            PaperDetailVO.PartVO pv = new PaperDetailVO.PartVO();
            pv.setRoman(part.roman());
            pv.setName(part.name());
            pv.setCardLabel(part.cardLabel());
            pv.setScore(part.score());
            pv.setMinutes(part.minutes());
            List<PaperDetailVO.SectionVO> sections = new ArrayList<>(part.sections().size());
            for (PaperStructure.Section section : part.sections()) {
                PaperDetailVO.SectionVO sv = new PaperDetailVO.SectionVO();
                sv.setLabel(section.label());
                sv.setSubTitle(section.subTitle());
                sv.setDirection(section.direction());
                sv.setAudioUrl(section.audioUrl());
                sv.setOriginalText(originalTextVisible ? section.originalText() : null);
                List<PaperDetailVO.QuestionRefVO> questions = new ArrayList<>(section.questions().size());
                for (PaperStructure.QuestionRef ref : section.questions()) {
                    PaperDetailVO.QuestionRefVO qv = new PaperDetailVO.QuestionRefVO();
                    qv.setIndex(ref.index());
                    qv.setQuestionId(ref.questionId());
                    qv.setScore(ref.score());
                    questions.add(qv);
                }
                sv.setQuestions(List.copyOf(questions));
                sections.add(sv);
            }
            pv.setSections(List.copyOf(sections));
            parts.add(pv);
        }
        return List.copyOf(parts);
    }
}
