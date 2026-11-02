package org.leon.aimodule.rag.ingest;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.leon.aimodule.rag.entity.RagChunk;
import org.leon.aimodule.rag.entity.RagDocument;
import org.leon.aimodule.rag.exception.RagException;
import org.leon.aimodule.rag.mapper.RagChunkMapper;
import org.leon.aimodule.rag.mapper.RagDocumentMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库文档的只读查询。
 * <p>
 * 分页刻意用 SQL LIMIT + 游标，而不是 MyBatis-Plus 的 Page：
 * 本项目没有注册 PaginationInnerInterceptor，Page 传进去不会自动加 LIMIT，
 * 会静默返回全表 —— 那是要上线后才炸的那种 bug。
 */
@Service
public class RagDocumentQueryService {

    /** 单页上限，防前端传个大数把库拖垮 */
    private static final int MAX_LIMIT = 100;

    private final RagDocumentMapper docMapper;
    private final RagChunkMapper chunkMapper;

    public RagDocumentQueryService(RagDocumentMapper docMapper, RagChunkMapper chunkMapper) {
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
    }

    /**
     * 列出当前用户可见的文档：自己的个人库 + 全部官方库。
     *
     * @param beforeId 游标，上一页最后一条的 id；首页传 null
     */
    public List<RagDocument> list(String userName, String status, Long beforeId, int limit) {
        int size = limit <= 0 ? 20 : Math.min(limit, MAX_LIMIT);
        QueryWrapper<RagDocument> w = new QueryWrapper<>();
        // 可见范围：官方库所有人可见；个人库只有本人可见
        w.and(q -> q.eq("scope", RagDocument.SCOPE_OFFICIAL)
                .or(o -> o.eq("scope", RagDocument.SCOPE_PERSONAL).eq("owner_name", safe(userName))));
        if (status != null && !status.isBlank()) {
            w.eq("status", status);
        }
        if (beforeId != null) {
            w.lt("id", beforeId);
        }
        w.orderByDesc("id").last("LIMIT " + size);
        return docMapper.selectList(w);
    }

    public RagDocument requireOwned(Long docId, String userName) {
        RagDocument doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new RagException("文档不存在");
        }
        // 官方库所有人可读；个人库仅本人可读
        if (RagDocument.SCOPE_PERSONAL.equals(doc.getScope())
                && (userName == null || !userName.equals(doc.getOwnerName()))) {
            // 报"不存在"而不是"无权"：不泄露他人文档 id 是否存在
            throw new RagException("文档不存在");
        }
        return doc;
    }

    /** 文档的切片列表，按 seq 升序。用于前端展开查看切片详情 */
    public List<RagChunk> chunksOf(Long docId, String userName) {
        requireOwned(docId, userName);
        return chunkMapper.findByDocId(docId);
    }

    /** 待补切片数：index_status != indexed 的行数，重放入口的可见指标 */
    public int pendingCount(Long docId) {
        return chunkMapper.findNotIndexed(docId).size();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
