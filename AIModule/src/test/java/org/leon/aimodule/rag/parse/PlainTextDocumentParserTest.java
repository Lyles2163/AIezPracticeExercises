package org.leon.aimodule.rag.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.chunk.ChunkPlanner;
import org.leon.aimodule.rag.chunk.TokenCounter;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析器的测试重点全在"静默失败"上：编码判错与标题认不出都不会抛异常，
 * 只会让数据带着乱码/空章节进 ES，之后永远检索不中且日志干净。
 */
class PlainTextDocumentParserTest {

    private static final Charset GBK = Charset.forName("GBK");

    private final PlainTextDocumentParser parser = new PlainTextDocumentParser();

    private ParsedDoc parse(String text) {
        return parser.parse(text.getBytes(StandardCharsets.UTF_8), "笔记.txt");
    }

    @Test
    @DisplayName("UTF-8 中文正常解码")
    void decodesUtf8() {
        ParsedDoc doc = parse("快速排序是分治算法，平均时间复杂度 O(nlogn)。");

        assertEquals(1, doc.pages().size());
        assertTrue(doc.pages().get(0).text().contains("快速排序"));
        assertNull(doc.pages().get(0).pageNo(), "txt 没有页概念，恒为 null（不显示"第 1 页"）");
    }

    @Test
    @DisplayName("GBK 中文回退解码，不产生乱码")
    void decodesGbk() {
        // Windows 记事本默认存 GBK；按 UTF-8 读会得到乱码，
        // 乱码进 ES 后检索永远命不中，且全过程不抛异常 —— 最难排查的一类失败
        String original = "第一章 排序算法\n\n快速排序是分治算法，归并排序是稳定的。";
        ParsedDoc doc = parser.parse(original.getBytes(GBK), "教材.txt");

        assertEquals(original, doc.pages().get(0).text(), "GBK 文件必须还原成原文，不能是乱码");
        assertFalse(doc.pages().get(0).text().contains("\uFFFD"), "不得出现替换字符");
    }

    @Test
    @DisplayName("去掉 UTF-8 BOM，否则首行标题认不出来")
    void stripsBom() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "第一章 绪论".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(body, 0, withBom, bom.length, body.length);

        ParsedDoc doc = parser.parse(withBom, "教材.txt");

        assertEquals("第一章 绪论", doc.pages().get(0).text());
        assertEquals("第一章 绪论", doc.title(), "BOM 残留会让标题多一个不可见字符");
    }

    @Test
    @DisplayName("Markdown 标题去掉井号，ChunkPlanner 才能识别章节")
    void stripsMarkdownHeadingPrefix() {
        // ChunkPlanner 的标题正则认「第X章」「1.2.3」「一、」，
        // "## 3.2 常见排序" 带井号前缀匹配不上，chapter_path 会整片为空
        ParsedDoc doc = parse("## 3.2 常见排序算法\n\n快速排序与归并排序。");

        String text = doc.pages().get(0).text();
        assertTrue(text.contains("3.2 常见排序算法"), "井号要去掉");
        assertFalse(text.contains("##"), "标题前缀不得残留");
    }

    @Test
    @DisplayName("正文里的井号不动，只处理标题前缀")
    void keepsHashInBody() {
        ParsedDoc doc = parse("C# 与 F# 都是 .NET 语言。\n\n####### 七个井号不是标题");

        String text = doc.pages().get(0).text();
        assertTrue(text.contains("C# 与 F#"), "正文井号必须保留");
        assertTrue(text.contains("####### 七个井号不是标题"), "超过 6 个井号不是 Markdown 标题");
    }

    @Test
    @DisplayName("统一 CRLF/CR 为 LF")
    void normalizesLineEndings() {
        ParsedDoc doc = parser.parse("第一行\r\n第二行\r第三行".getBytes(StandardCharsets.UTF_8), "a.txt");

        assertEquals("第一行\n第二行\n第三行", doc.pages().get(0).text());
    }

    @Test
    @DisplayName("标题取正文首个非空行")
    void titleFromFirstNonBlankLine() {
        ParsedDoc doc = parse("\n\n算法导论第三章笔记\n\n正文内容。");

        assertEquals("算法导论第三章笔记", doc.title());
    }

    @Test
    @DisplayName("超长标题截断到 255，否则入库报 Data too long")
    void truncatesLongTitle() {
        String longLine = "标".repeat(400);
        ParsedDoc doc = parse(longLine + "\n\n正文");

        assertTrue(doc.title().length() <= 255, "实际长度 " + doc.title().length());
    }

    @Test
    @DisplayName("扩展名与 MIME 白名单")
    void supportsByExtensionAndMime() {
        assertTrue(parser.supports("a.txt", null));
        assertTrue(parser.supports("a.MD", null), "扩展名大小写不敏感");
        assertTrue(parser.supports("a.markdown", null));
        assertTrue(parser.supports(null, "text/plain"), "MIME 是 text/* 就收");
        assertFalse(parser.supports("a.pdf", "application/pdf"));
        assertFalse(parser.supports("a.exe", "application/octet-stream"));
        assertFalse(parser.supports(null, null));
    }

    @Test
    @DisplayName("文件名带本地路径时只取最后一段作标题兜底")
    void baseNameStripsPath() {
        // 浏览器有时会把本地绝对路径整个塞进 filename，直接当标题会显示成 "D:\docs\我的笔记"
        assertEquals("我的笔记", PlainTextDocumentParser.baseName("D:\\docs\\我的笔记.txt"));
        assertEquals("我的笔记", PlainTextDocumentParser.baseName("/home/u/我的笔记.md"));
        assertEquals("未命名文档", PlainTextDocumentParser.baseName(null));
        assertEquals("未命名文档", PlainTextDocumentParser.baseName("   "));
    }

    @Test
    @DisplayName("解码后全是空白时报错，不静默入一条空文档")
    void rejectsBlankAfterDecode() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("  \n ".getBytes(StandardCharsets.UTF_8), "a.txt"));
    }

    @Test
    @DisplayName("空文件与超限文件直接拒")
    void rejectsEmptyAndOversize() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new byte[0], "a.txt"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(null, "a.txt"));
        // 21MB 超过 20MB 上限
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new byte[21 * 1024 * 1024], "a.txt"));
    }

    @Test
    @DisplayName("非 UTF-8 非 GBK 的字节序列报错而不是乱码入库")
    void rejectsUndecodable() {
        // 0xFF 在 UTF-8 与 GBK 里都不是合法起始字节
        byte[] junk = new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x01, (byte) 0x80};

        assertThrows(IllegalArgumentException.class, () -> parser.parse(junk, "a.txt"));
    }

    @Test
    @DisplayName("解析结果可直接交给 ChunkPlanner 切出带章节路径的切片")
    void feedsChunkPlanner() {
        // 这条是解析器与切片器的接口契约：chapterPath 能填上，前端引用才有章节可显示
        ParsedDoc doc = parse("## 3.2 常见排序算法\n\n" + "快速排序是分治算法。".repeat(80));
        List<ChunkDraft> drafts = new ChunkPlanner(new TokenCounter()).plan(doc);

        assertFalse(drafts.isEmpty());
        assertTrue(drafts.stream().anyMatch(d -> d.chapterPath() != null && d.chapterPath().contains("3.2")),
                "去掉井号后标题应被识别为章节，chapterPath 不该为空");
    }
}
