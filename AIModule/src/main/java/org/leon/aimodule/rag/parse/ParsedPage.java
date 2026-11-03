package org.leon.aimodule.rag.parse;

/**
 * 解析后的一页。pageNo 为 null 表示该格式没有页概念（如 txt/md），
 * 检索结果与引用拼装会跳过 null 页码。
 */
public record ParsedPage(Integer pageNo, String text) {
}
