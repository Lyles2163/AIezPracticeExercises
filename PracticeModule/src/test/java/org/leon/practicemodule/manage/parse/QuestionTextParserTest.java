package org.leon.practicemodule.manage.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.service.AnswerGrader;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("本地规则题库解析器：五种题型、容错推断、模板闭环与判分口径一致性")
class QuestionTextParserTest {

    private final QuestionTextParser parser = new QuestionTextParser();
    private final DocumentTextExtractor extractor = new DocumentTextExtractor();
    private final ImportTemplateGenerator templateGenerator = new ImportTemplateGenerator();
    private final AnswerGrader grader = new AnswerGrader();

    @Test
    @DisplayName("三种标准模板（md/txt/docx）往返提取与解析均产出 5 种合法题型，且全部通过 AnswerGrader 判分")
    void templateRoundtripAndGraderCompatibility() {
        for (String fmt : List.of("md", "txt", "docx")) {
            ImportTemplateGenerator.TemplateFile tpl = templateGenerator.generate(fmt);
            DocumentTextExtractor.ExtractedDocument extracted = extractor.extract(tpl.bytes(), tpl.fileName());
            ParseResult result = parser.parse(extracted.text());

            assertTrue(result.warnings().isEmpty(), fmt + " 模板不应产生任何警告: " + result.warnings());
            assertEquals(5, result.questions().size(), fmt + " 模板应解析出 5 道题");

            ParsedQuestion q1 = result.questions().get(0);
            assertEquals(1, q1.type());
            assertEquals("\"C\"", q1.answerJson());
            assertEquals(4, q1.options().size());
            assertEquals(1, q1.difficulty());
            assertEquals(List.of("Java", "基础"), q1.tags());
            assertEquals(0, new BigDecimal("5").compareTo(q1.score()));
            assertTrue(grader.grade(1, q1.answerJson(), "\"C\"", List.of("A", "B", "C", "D")).correct());

            ParsedQuestion q2 = result.questions().get(1);
            assertEquals(2, q2.type());
            assertEquals("[\"A\",\"C\"]", q2.answerJson());
            assertTrue(grader.grade(2, q2.answerJson(), "[\"C\",\"A\"]", List.of()).correct());

            ParsedQuestion q3 = result.questions().get(2);
            assertEquals(3, q3.type());
            assertEquals("true", q3.answerJson());
            assertTrue(q3.options().isEmpty());
            assertTrue(grader.grade(3, q3.answerJson(), "true", List.of()).correct());

            ParsedQuestion q4 = result.questions().get(3);
            assertEquals(4, q4.type());
            assertTrue(grader.grade(4, q4.answerJson(), "[\"java虚拟机\",\"gc\"]", List.of()).correct(),
                    "同义词嵌套数组与全半角/大小写归一化应与 AnswerGrader 无缝对接");

            ParsedQuestion q5 = result.questions().get(4);
            assertEquals(5, q5.type());
            assertEquals(3, q5.difficulty());
            assertTrue(grader.grade(5, q5.answerJson(), "\"我的回答\"", List.of()).needSelfJudge());
        }
    }

    @Test
    @DisplayName("不写题型标签时自动推断题型，并支持行内多选项")
    void autoInferTypesAndInlineOptions() {
        String text = """
                1. 以下哪个是质数？
                A. 4   B. 6   C. 7   D. 9
                答案：C

                2. 以下哪些是偶数？
                (A) 2  (B) 3  (C) 4
                正确答案：A、C

                3. 太阳从西边升起。
                答案：×

                4. 中国的首都是 ____。
                答案：北京

                5. 谈谈你对面向对象的理解。
                答案：封装、继承与多态。
                解析：第一行解析
                第二行补充解析
                """;

        ParseResult result = parser.parse(text);

        assertTrue(result.warnings().isEmpty(), () -> "意外警告: " + result.warnings());
        assertEquals(5, result.questions().size());
        assertEquals(1, result.questions().get(0).type());
        assertEquals(4, result.questions().get(0).options().size());
        assertEquals(2, result.questions().get(1).type());
        assertEquals("[\"A\",\"C\"]", result.questions().get(1).answerJson());
        assertEquals(3, result.questions().get(2).type());
        assertEquals("false", result.questions().get(2).answerJson());
        assertEquals(4, result.questions().get(3).type());
        assertEquals("[\"北京\"]", result.questions().get(3).answerJson());
        assertEquals(5, result.questions().get(4).type());
        assertTrue(result.questions().get(4).analysis().contains("第二行补充解析"));
    }

    @Test
    @DisplayName("缺失答案或越界选项字母的异常题块跳过并记录警告，不影响同文档合法题目")
    void skipsInvalidBlocksWithWarnings() {
        String text = """
                1. [单选] 合法题
                A. 对
                B. 错
                答案：A

                2. [单选] 缺答案的题
                A. 1
                B. 2

                3. [单选] 答案越界的题
                A. 1
                B. 2
                答案：D
                """;

        ParseResult result = parser.parse(text);

        assertEquals(3, result.totalBlocks());
        assertEquals(1, result.questions().size());
        assertEquals("合法题", result.questions().get(0).title());
        assertEquals(2, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("缺少答案"));
        assertTrue(result.warnings().get(1).contains("不在选项列表"));
    }
}
