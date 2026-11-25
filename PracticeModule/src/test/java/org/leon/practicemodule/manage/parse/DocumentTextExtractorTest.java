package org.leon.practicemodule.manage.parse;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("文档文本提取器：UTF-8/BOM/GBK、docx段落+表格与非法文件拦截")
class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    @DisplayName("Markdown 带 UTF-8 BOM 正确剥离并保留正文")
    void markdownWithBom() {
        byte[] content = ("# 一、单选题\n1. 测试题干\n答案：A").getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[content.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(content, 0, withBom, 3, content.length);

        DocumentTextExtractor.ExtractedDocument doc = extractor.extract(withBom, "dir/test.md");

        assertEquals("test.md", doc.fileName());
        assertEquals("md", doc.fileType());
        assertTrue(doc.text().startsWith("# 一、单选题"), "BOM 必须被剥离，不能留在首字符");
    }

    @Test
    @DisplayName("GBK 编码的中文 txt 能自动回退解码不乱码")
    void gbkTxtDecodedCleanly() {
        String raw = "1. 光合作用的场所是？\nA. 叶绿体\nB. 线粒体\n答案：A";
        byte[] gbkBytes = raw.getBytes(Charset.forName("GBK"));

        DocumentTextExtractor.ExtractedDocument doc = extractor.extract(gbkBytes, "生物.txt");

        assertEquals("txt", doc.fileType());
        assertTrue(doc.text().contains("光合作用"));
        assertTrue(doc.text().contains("叶绿体"));
    }

    @Test
    @DisplayName("docx 同时提取段落与表格文本")
    void docxParagraphAndTable() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("1. [单选] 表格题干");
            XWPFTable table = doc.createTable(2, 1);
            table.getRow(0).getCell(0).setText("A. 选项甲");
            table.getRow(1).getCell(0).setText("B. 选项乙");
            doc.createParagraph().createRun().setText("答案：A");
            doc.write(out);
            bytes = out.toByteArray();
        }

        DocumentTextExtractor.ExtractedDocument extracted = extractor.extract(bytes, "sample.docx");

        assertEquals("docx", extracted.fileType());
        assertTrue(extracted.text().contains("表格题干"));
        assertTrue(extracted.text().contains("A. 选项甲"));
        assertTrue(extracted.text().contains("答案：A"));
    }

    @Test
    @DisplayName("空文件、不支持后缀、伪造 docx/doc 魔数全部报 40000")
    void rejectsInvalidFiles() {
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> extractor.extract(new byte[0], "a.md")).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> extractor.extract("hello".getBytes(StandardCharsets.UTF_8), "a.pdf")).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> extractor.extract("not-a-zip".getBytes(StandardCharsets.UTF_8), "fake.docx")).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> extractor.extract("not-ole2".getBytes(StandardCharsets.UTF_8), "fake.doc")).errorCode());
    }
}
