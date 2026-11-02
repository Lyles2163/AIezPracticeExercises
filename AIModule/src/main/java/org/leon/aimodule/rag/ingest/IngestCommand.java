package org.leon.aimodule.rag.ingest;

/**
 * 一次入库请求的全部输入。
 * <p>
 * content 是原始字节而不是文本：编码探测由解析器负责
 * （中文 txt 常见 GBK，提前转 String 会把探测机会毁掉）。
 *
 * @param fileName  原始文件名，用于扩展名白名单与标题兜底
 * @param mimeType  浏览器声明的类型，可能不准，解析器同时看扩展名
 * @param title     用户显式标题；空则由解析器从正文首行推导
 * @param book      书名/课程名，溯源展示用，可空
 * @param scope     official / personal
 * @param ownerName 上传者（X-User-Name）；official 时传空串
 * @param subjectId 0=全学科通用；null 按 0 处理
 */
public record IngestCommand(String fileName, String mimeType, String title, String book,
                            String scope, String ownerName, Long subjectId, byte[] content) {
}
