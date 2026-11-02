package org.leon.aimodule.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 文档切片（一个 chunk 一行）。
 * <p>
 * 本表 id 就是 ES ai_kb_chunk 里对应文档的 _id —— 用同一个数字寻址，
 * 删除文档时才能按 id 精确删掉 ES 里的那批切片，不留孤儿。
 * <p>
 * {@link #content} 是重建索引与重放 embedding 的唯一来源，向量刻意不落库：
 * 1024 维 float ≈ 4KB/条，MySQL 存了也检索不了，只有 ES 能做 kNN。
 */
@Data
@TableName("rag_chunk")
public class RagChunk {

    /** 已落库，还没 embedding */
    public static final String IX_PENDING = "pending";
    /** 已拿到向量，还没写进 ES */
    public static final String IX_EMBEDDED = "embedded";
    /** 已写进 ES，可被检索 */
    public static final String IX_INDEXED = "indexed";
    /** embedding 或写 ES 失败；原因见 errorMsg，重放时据此筛出待补清单 */
    public static final String IX_FAILED = "failed";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long docId;
    /** 文档内顺序，从 1 开始；与 doc_id 一起构成唯一键，重放不会产生第二条 seq=1 */
    private Integer seq;
    private String content;
    private Integer tokenCount;
    private Integer pageNo;
    private String chapterPath;
    private String indexStatus;
    private String errorMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
