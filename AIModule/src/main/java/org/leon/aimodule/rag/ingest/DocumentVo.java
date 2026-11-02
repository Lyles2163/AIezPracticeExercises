package org.leon.aimodule.rag.ingest;

import org.leon.aimodule.rag.entity.RagDocument;

/**
 * 文档对外视图。
 * <p>
 * 刻意不直接返回实体：{@code source_path}（服务器落盘路径）与 {@code file_hash}
 * 属于内部实现细节，暴露给前端既没用又有信息泄露风险，所以在这里裁掉。
 */
public record DocumentVo(Long id, String fileName, String title, String book,
                         String scope, String ownerName, Long subjectId,
                         String mimeType, Long fileSize,
                         String status, Integer chunkCount, Integer tokenCount,
                         String errorMsg, Long createTime, Long updateTime) {

    public static DocumentVo of(RagDocument d) {
        return new DocumentVo(
                d.getId(), d.getFileName(), d.getTitle(), d.getBook(),
                d.getScope(), d.getOwnerName(), d.getSubjectId(),
                d.getMimeType(), d.getFileSize(),
                d.getStatus(), d.getChunkCount(), d.getTokenCount(),
                d.getErrorMsg(),
                toEpochMilli(d.getCreateTime()), toEpochMilli(d.getUpdateTime()));
    }

    /** 与 chat 模块口径一致：LocalDateTime 转 epoch 毫秒给前端，前端不用管时区 */
    private static Long toEpochMilli(java.time.LocalDateTime t) {
        return t == null ? null : t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
