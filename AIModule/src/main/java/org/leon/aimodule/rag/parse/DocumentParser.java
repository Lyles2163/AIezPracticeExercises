package org.leon.aimodule.rag.parse;

import java.io.IOException;

/**
 * 原文件 → {@link ParsedDoc} 的解析器。
 * <p>
 * 一个实现对应一类文件格式；{@code RagDocumentService} 注入全部实现，
 * 按 {@link #supports} 选出第一个能处理的。新增 PDF/Word 支持时只需加实现类，
 * 入库编排代码不用改。
 * <p>
 * 实现约定：纯转换，不读库、不发请求、不碰磁盘（字节由调用方给），
 * 便于单测；同一输入必然得到同一输出。
 */
public interface DocumentParser {

    /**
     * 是否能处理该文件。
     *
     * @param fileName 原始文件名，可能为 null
     * @param mimeType 上传声明的类型，浏览器给的可能不准，实现应同时看扩展名
     */
    boolean supports(String fileName, String mimeType);

    /**
     * @param content  文件全部字节
     * @param fileName 原始文件名，用于推导标题
     * @throws IOException 格式不支持或内容无法解码
     */
    ParsedDoc parse(byte[] content, String fileName) throws IOException;
}
