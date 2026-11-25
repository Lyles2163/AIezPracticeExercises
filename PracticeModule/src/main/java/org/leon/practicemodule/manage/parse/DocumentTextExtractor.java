package org.leon.practicemodule.manage.parse;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.util.Set;

/**
 * 文档文本提取器：支持 .md / .markdown / .txt / .docx / .doc。
 *
 * <p>文本类文件采用严格 UTF-8（自动去 BOM）→ GBK 兜底，避免 Windows 记事本中文乱码；
 * Word 文档校验魔数后经 Apache POI 提取段落与表格中的文本。
 */
@Component
public class DocumentTextExtractor {

    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    public static final Set<String> ALLOWED_EXTENSIONS = Set.of("md", "markdown", "txt", "docx", "doc");

    private static final Charset GBK = Charset.forName("GBK");

    public record ExtractedDocument(String fileName, String fileType, long fileSize, String text) {
    }

    public ExtractedDocument extract(byte[] bytes, String originalFileName) {
        if (bytes == null || bytes.length == 0) {
            throw badRequest("上传文件不能为空");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw badRequest("上传文件超过 10MB 上限");
        }
        String safeName = sanitizeFileName(originalFileName);
        String ext = extensionOf(safeName);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw badRequest("仅支持 .md / .txt / .docx / .doc 文件，当前格式不支持");
        }

        String fileType = "markdown".equals(ext) ? "md" : ext;
        String text = switch (fileType) {
            case "md", "txt" -> decodePlainText(bytes);
            case "docx" -> extractDocx(bytes);
            case "doc" -> extractDoc(bytes);
            default -> throw badRequest("不支持的文件类型: " + ext);
        };
        String normalized = normalizeLineEndings(text);
        if (normalized.isBlank()) {
            throw badRequest("文档中没有可识别的文本内容");
        }
        return new ExtractedDocument(safeName, fileType, bytes.length, normalized);
    }

    private String decodePlainText(byte[] content) {
        byte[] body = stripBom(content);
        try {
            return strictDecode(body, StandardCharsets.UTF_8);
        } catch (CharacterCodingException ignored) {
            // UTF-8 解不动再按 GBK 尝试
        }
        try {
            return strictDecode(body, GBK);
        } catch (CharacterCodingException e) {
            throw badRequest("无法识别文本编码（请使用 UTF-8 或 GBK 编码保存）");
        }
    }

    private String strictDecode(byte[] body, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer decoded = decoder.decode(ByteBuffer.wrap(body));
        return decoded.toString();
    }

    private byte[] stripBom(byte[] content) {
        if (content.length >= 3
                && (content[0] & 0xFF) == 0xEF
                && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            byte[] stripped = new byte[content.length - 3];
            System.arraycopy(content, 3, stripped, 0, stripped.length);
            return stripped;
        }
        return content;
    }

    private String extractDocx(byte[] bytes) {
        if (!isZipMagic(bytes)) {
            throw badRequest("文件不是有效的 .docx 文档（文件头校验失败）");
        }
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<String> lines = new ArrayList<>();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    lines.add(p.getText() == null ? "" : p.getText());
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            String cellText = cell.getText();
                            if (cellText != null && !cellText.isBlank()) {
                                cells.add(cellText.trim());
                            }
                        }
                        if (!cells.isEmpty()) {
                            lines.add(String.join(" ", cells));
                        }
                    }
                }
            }
            return String.join("\n", lines);
        } catch (PracticeException e) {
            throw e;
        } catch (Exception e) {
            throw badRequest("解析 .docx 文档失败：" + e.getMessage());
        }
    }

    private String extractDoc(byte[] bytes) {
        if (!isOle2Magic(bytes)) {
            throw badRequest("文件不是有效的 .doc 文档（若为新版 Word 请保存为 .docx）");
        }
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(doc)) {
            String[] paragraphs = extractor.getParagraphText();
            if (paragraphs == null || paragraphs.length == 0) {
                return extractor.getText();
            }
            List<String> lines = new ArrayList<>(paragraphs.length);
            for (String p : paragraphs) {
                if (p != null) {
                    lines.add(p.replace("\r", "").replace("\u0007", " ").stripTrailing());
                }
            }
            return String.join("\n", lines);
        } catch (IOException | RuntimeException e) {
            throw badRequest("解析 .doc 文档失败：" + e.getMessage());
        }
    }

    private static boolean isZipMagic(byte[] b) {
        return b.length >= 4
                && (b[0] & 0xFF) == 0x50
                && (b[1] & 0xFF) == 0x4B
                && (b[2] & 0xFF) == 0x03
                && (b[3] & 0xFF) == 0x04;
    }

    private static boolean isOle2Magic(byte[] b) {
        return b.length >= 8
                && (b[0] & 0xFF) == 0xD0
                && (b[1] & 0xFF) == 0xCF
                && (b[2] & 0xFF) == 0x11
                && (b[3] & 0xFF) == 0xE0;
    }

    private static String normalizeLineEndings(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\r\n", "\n").replace('\r', '\n');
    }

    static String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未命名文件.txt";
        }
        String normalized = raw.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        name = name.trim();
        if (name.length() > 255) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 ? name.substring(dot) : "";
            name = name.substring(0, Math.max(1, 255 - ext.length())) + ext;
        }
        return name;
    }

    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static PracticeException badRequest(String msg) {
        return new PracticeException(PracticeErrorCode.BAD_REQUEST, msg);
    }
}
