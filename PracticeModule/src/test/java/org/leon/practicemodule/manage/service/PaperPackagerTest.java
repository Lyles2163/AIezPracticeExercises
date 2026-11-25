package org.leon.practicemodule.manage.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.service.PaperStructure;
import org.leon.practicemodule.service.PaperStructureParser;
import org.leon.practicemodule.service.PaperStructureValidator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PaperPackager：自动分组、连续题号、分值时长汇总与 PaperStructureParser 往返一致")
class PaperPackagerTest {

    private final PaperPackager packager = new PaperPackager();

    @Test
    @DisplayName("混合题型打包生成的 structure_json 可被 PaperStructureParser 完整反序列化并通过四条校验")
    void buildsValidPaperStructure() {
        List<PaperPackager.PackItem> items = List.of(
                new PaperPackager.PackItem(101L, 1, "一、单选题", new BigDecimal("5")),
                new PaperPackager.PackItem(102L, 1, "一、单选题", new BigDecimal("5")),
                new PaperPackager.PackItem(103L, 2, "二、多选题", new BigDecimal("10")),
                new PaperPackager.PackItem(104L, 5, null, null)
        );
        Map<Long, PaperStructureValidator.QuestionBrief> briefs = Map.of(
                101L, new PaperStructureValidator.QuestionBrief(101L, 9L, 1),
                102L, new PaperStructureValidator.QuestionBrief(102L, 9L, 1),
                103L, new PaperStructureValidator.QuestionBrief(103L, 9L, 1),
                104L, new PaperStructureValidator.QuestionBrief(104L, 9L, 1)
        );

        QbPaper paper = packager.buildAndValidate(new PaperPackager.PackCommand(
                20L, 9L, "Java 专项测验", 2026, 11, 1, 60, "说明", new BigDecimal("15"), items), briefs);

        assertEquals(4, paper.getQuestionCount());
        assertEquals(0, new BigDecimal("35.00").compareTo(paper.getTotalScore()));
        assertEquals(20L, paper.getOwnerId());

        PaperStructure parsed = PaperStructureParser.parse(paper.getStructureJson());
        assertEquals(3, parsed.parts().size(), "按章节/题型分成 3 个 Part");
        assertEquals(4, parsed.questionsInOrder().size());
        assertEquals(1, parsed.questionsInOrder().get(0).index());
        assertEquals(4, parsed.questionsInOrder().get(3).index());
        assertTrue(parsed.sumPartMinutes() <= 60);
        assertDoesNotThrow(() -> PaperStructureValidator.validate(
                parsed, paper.getQuestionCount(), paper.getTotalScore(), paper.getDurationMinutes(), 9L, briefs));
    }
}
