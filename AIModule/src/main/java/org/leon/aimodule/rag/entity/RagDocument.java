package org.leon.aimodule.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 知识库文档（文档级元数据，一个上传文件一行）。
 * <p>
 * 与 ES 的关系：本表是不可丢的事实之源，ES 的 ai_kb_chunk 是可随时重建的派生索引。
 * ES 文档里的 doc_id 就指回本表 id。analyzer 一旦建索引就不可改，重建索引时要靠
 * {@link RagChunk#getContent()} 重放，原文件反而不必再解析一次。
 */
@Data
@TableName("rag_document")
public class RagDocument {

    /** 刚入库，还没开始解析 */
    public static final String ST_PENDING = "pending";
    /** 正在解析原文件为 ParsedDoc */
    public static final String ST_PARSING = "parsing";
    /** 正在切片 */
    public static final String ST_CHUNKING = "chunking";
    /** 正在 embedding 并写 ES */
    public static final String ST_EMBEDDING = "embedding";
    /** 全部切片已进 ES，可被检索 */
    public static final String ST_INDEXED = "indexed";
    /** 任一阶段失败；原因见 errorMsg，已成功的切片不回滚 */
    public static final String ST_FAILED = "failed";

    /** official=全用户可见；personal=仅 owner_name 本人可见 */
    public static final String SCOPE_OFFICIAL = "official";
    public static final String SCOPE_PERSONAL = "personal";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String fileName;
    private String title;
    private String book;
    private String scope;
    private String ownerName;
    private Long subjectId;
    private String sourcePath;
    /** SHA-256 十六进制小写；与 (scope, owner_name) 一起构成去重唯一键 */
    private String fileHash;
    private Long fileSize;
    private String mimeType;
    private String status;
    private Integer chunkCount;
    private Integer tokenCount;
    private String errorMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
