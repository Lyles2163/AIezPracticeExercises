package org.leon.aimodule.rag.parse;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 纯文本与 Markdown 解析器。txt/md 没有页的概念，pageNo 恒为 null ——
 * 检索结果与引用拼装会跳过 null 页码，不显示误导性的"第 1 页"。
 * <p>
 * 两件事是刻意为之，改之前先读完：
 * <ol>
 *   <li><b>编码探测</b>：中文 txt 大量来自 Windows 记事本，默认 GBK。
 *       直接按 UTF-8 读会得到乱码，而乱码写进 ES 后 <em>永远</em>检索不中，
 *       且日志里看不出异常 —— 属于最难排查的一类静默失败。
 *       所以先用严格模式试 UTF-8，解码报错再退 GBK。</li>
 *   <li><b>Markdown 标题去井号</b>：{@code ChunkPlanner} 靠正则识别「第X章」「1.2.3」「一、」
 *       来维护章节路径，而 {@code ## 3.2 常见排序算法} 因为前缀井号匹配不上，
 *       chapter_path 会整片为空，前端引用就只剩书名没有章节。
 *       去掉井号后它正好命中数字编号样式，章节路径立刻可用。</li>
 * </ol>
 */
@Component
public class PlainTextDocumentParser implements DocumentParser {

    private static final List<String> EXTENSIONS = List.of("txt", "md", "markdown", "text", "log", "csv");

    /** GBK 覆盖 GB2312，是中文 Windows 记事本的默认编码 */
    private static final Charset GBK = Charset.forName("GBK");

    /** 单文件上限 20MB：再大的文本文件切片数会失控，应该走异步而不是同步上传 */
    private static final int MAX_BYTES = 20 * 1024 * 1024;

    @Override
    public boolean supports(String fileName, String mimeType) {
        if (mimeType != null) {
            String mt = mimeType.toLowerCase(Locale.ROOT);
            if (mt.startsWith("text/") || mt.equals("application/json") || mt.equals("application/xml")) {
                return true;
            }
        }
        return EXTENSIONS.contains(extensionOf(fileName));
    }

    @Override
    public ParsedDoc parse(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (content.length > MAX_BYTES) {
            throw new IllegalArgumentException("文件超过 " + (MAX_BYTES / 1024 / 1024) + "MB 上限");
        }
        String raw = decode(content);
        String normalized = normalize(raw);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("文件解码后没有可读文本（可能是二进制文件）");
        }
        return new ParsedDoc(fileName, titleOf(fileName, normalized),
                List.of(new ParsedPage(null, normalized)));
    }

    /**
     * 严格 UTF-8 → GBK 兜底。
     * <p>
     * 用 REPORT 而非 REPLACE：REPLACE 会把非法字节换成 U+FFFD 并「成功」返回，
     * 于是 GBK 文件被静默读成一堆替换字符，正是上面注释里说的静默失败。
     */
    private String decode(byte[] content) {
        byte[] body = stripBom(content);
        try {
            return strictDecode(body, StandardCharsets.UTF_8);
        } catch (CharacterCodingException ignored) {
            // UTF-8 解不动，按 GBK 再试一次
        }
        try {
            return strictDecode(body, GBK);
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("无法识别文件编码（既不是 UTF-8 也不是 GBK）", e);
        }
    }

    private String strictDecode(byte[] body, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer out = decoder.decode(ByteBuffer.wrap(body));
        return out.toString();
    }

    /**
     * 去掉 UTF-8 BOM。BOM 会让文件第一个字符变成 U+FEFF，
     * 若首行正好是标题，Heading 正则的 ^ 就匹配不上了。
     */
    private byte[] stripBom(byte[] content) {
        if (content.length >= 3
                && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB && (content[2] & 0xFF) == 0xBF) {
            byte[] out = new byte[content.length - 3];
            System.arraycopy(content, 3, out, 0, out.length);
            return out;
        }
        return content;
    }

    /**
     * 统一换行 + 处理 Markdown。
     * <p>
     * 保留 Markdown 的正文语法（列表符号、粗体标记等）：切片是给模型读的上下文，
     * 少量符号不影响理解，剥掉反而可能把语义一起剥掉。只动标题前缀。
     */
    private String normalize(String raw) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            out.add(transformLine(line));
        }
        return String.join("\n", out);
    }

    /**
     * 单行变换：把 {@code # 标题} 的井号前缀去掉，让 ChunkPlanner 的标题正则能认出它。
     * 只处理行首的 1~6 个井号 + 空格；正文里出现的 # 不动。
     */
    private String transformLine(String line) {
        String trimmed = line.stripLeading();
        if (!trimmed.startsWith("#")) {
            return line;
        }
        int hashes = 0;
        while (hashes < trimmed.length() && hashes < 6 && trimmed.charAt(hashes) == '#') {
            hashes++;
        }
        // "######" 之后必须跟空格才是标题，否则是正文里的井号串
        if (hashes == 0 || hashes >= trimmed.length() || trimmed.charAt(hashes) != ' ') {
            return line;
        }
        String indent = line.substring(0, line.length() - line.stripLeading().length());
        return indent + trimmed.substring(hashes + 1).strip();
    }

    /**
     * 标题推导优先级：Markdown 一级标题 &gt; 首个非空行 &gt; 文件名去扩展名。
     * 截断到 255 与表列宽一致，超长直接入库会报 Data too long。
     */
    private String titleOf(String fileName, String text) {
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty()) {
                return truncate(t.length() > 200 ? t.substring(0, 200) : t, 255);
            }
        }
        return truncate(baseName(fileName), 255);
    }

    static String baseName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "未命名文档";
        }
        // 只取最后一段：浏览器有时会把本地绝对路径整个塞进 filename
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
