package org.leon.practicemodule.manage.parse;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 标准题库导入模板生成器：支持导出 .md / .txt / .docx 示例文件，
 * 覆盖单选、多选、判断、填空、简答五种题型，下载后可直接修改并回传导入。
 */
@Component
public class ImportTemplateGenerator {

    public record TemplateFile(String fileName, String contentType, byte[] bytes) {
    }

    private static final String MARKDOWN_TEMPLATE = """
            # 一、单选题

            1. [单选] Java 中以下哪个不是基本数据类型？
            A. int
            B. boolean
            C. String
            D. double
            答案：C
            解析：String 是引用类型（类），Java 的 8 种基本数据类型为 byte/short/int/long/float/double/char/boolean。
            难度：简单
            标签：Java, 基础
            分值：5

            # 二、多选题

            2. [多选] 以下哪些集合实现了 List 接口？
            A. ArrayList
            B. HashSet
            C. LinkedList
            D. HashMap
            答案：AC
            解析：ArrayList 与 LinkedList 实现 List 接口；HashSet 实现 Set，HashMap 实现 Map。
            难度：中等
            标签：Java, 集合
            分值：10

            # 三、判断题

            3. [判断] HTTP 协议默认工作在 TCP 协议之上，且本身是无状态的。
            答案：正确
            解析：HTTP/1.x 与 HTTP/2 基于 TCP，请求之间默认不保留状态信息。
            难度：简单
            标签：计算机网络, HTTP
            分值：5

            # 四、填空题

            4. [填空] Java 虚拟机执行字节码的运行环境简称是 ____，其负责内存回收的核心机制称为 ____。
            答案：JVM/Java虚拟机 | GC/垃圾回收
            解析：多个空用竖线 | 或分号 ； 分隔，同一空的同义答案用斜杠 / 分隔。
            难度：中等
            标签：JVM, 内存管理
            分值：10

            # 五、简答题

            5. [简答] 请简述 TCP 三次握手的主要目的。
            答案：1. 确认通信双方的发送与接收能力均正常；2. 同步双方的初始序列号（ISN）；3. 防止已失效的历史连接请求突然到达服务端而造成资源浪费。
            解析：简答题提交后会展示参考答案供自评，不自动判分。
            难度：困难
            标签：计算机网络, TCP
            分值：20
            """;

    public TemplateFile generate(String format) {
        String fmt = format == null ? "md" : format.trim().toLowerCase(Locale.ROOT);
        return switch (fmt) {
            case "md", "markdown" -> new TemplateFile(
                    "question-import-template.md",
                    "text/markdown; charset=UTF-8",
                    MARKDOWN_TEMPLATE.getBytes(StandardCharsets.UTF_8));
            case "txt" -> new TemplateFile(
                    "question-import-template.txt",
                    "text/plain; charset=UTF-8",
                    toPlainTxt(MARKDOWN_TEMPLATE).getBytes(StandardCharsets.UTF_8));
            case "docx" -> new TemplateFile(
                    "question-import-template.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    toDocx(toPlainTxt(MARKDOWN_TEMPLATE)));
            default -> throw new PracticeException(PracticeErrorCode.BAD_REQUEST,
                    "模板格式仅支持 md / txt / docx");
        };
    }

    private static String toPlainTxt(String md) {
        return md.replaceAll("(?m)^#+\\s+", "");
    }

    private static byte[] toDocx(String text) {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : text.split("\n", -1)) {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun run = p.createRun();
                run.setText(line);
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("生成 Word 模板失败", e);
        }
    }
}
