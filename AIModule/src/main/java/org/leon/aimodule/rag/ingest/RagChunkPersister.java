package org.leon.aimodule.rag.ingest;

import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.entity.RagChunk;
import org.leon.aimodule.rag.entity.RagDocument;
import org.leon.aimodule.rag.exception.RagException;
import org.leon.aimodule.rag.mapper.RagChunkMapper;
import org.leon.aimodule.rag.mapper.RagDocumentMapper;
import org.leon.aimodule.rag.parse.ChunkDraft;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 入库的 MySQL 落库段（事务边界）。
 * <p>
 * <b>为什么单独一个 Bean</b>：Spring 的 @Transactional 靠代理生效，
 * 同类内部调用（this.persist(...)）不经过代理，注解会被静默忽略 ——
 * 上一版把这方法放在 RagDocumentService 里自己调自己，事务实际没开。
 * 拆成独立 Bean 后由 RagDocumentService 跨 Bean 调用，代理才拦得住。
 * <p>
 * 事务范围刻意只包 MySQL：embedding 与写 ES 是数秒到数十秒的外部 HTTP，
 * 圈进事务会长时间占用连接和行锁，拖垮连接池。它们在事务外执行，
 * 失败时 MySQL 数据仍在（切片停在 pending），可据此重放，数据不丢。
 */
@Slf4j
@Service
public class RagChunkPersister {

    private final RagDocumentMapper docMapper;
    private final RagChunkMapper chunkMapper;
    private final RagProperties props;

    public RagChunkPersister(RagDocumentMapper docMapper, RagChunkMapper chunkMapper, RagProperties props) {
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.props = props;
    }

    /**
     * 文档行 + 全部切片行，一个事务：要么全成，要么全不成。
     * 中途失败不会留下"有文档没切片"或"有切片没文档"的半截状态。
     *
     * @return 文档自增 id（切片已落库，可按 doc_id 读回）
     */
    @Transactional
    public Long persist(RagDocument doc, List<ChunkDraft> drafts) {
        doc.setStatus(RagDocument.ST_CHUNKING);
        docMapper.insertReturningId(doc);
        Long docId = doc.getId();
        if (docId == null) {
            // Seata AT 代理下自增回填偶发拿不到；拿不到就没法给切片挂 doc_id，
            // 直接失败回滚，而不是插一堆孤儿切片
            throw new RagException("文档主键回填失败，无法继续入库");
        }

        int batch = Math.max(1, props.getIngest().getMysqlBatchSize());
        List<RagChunk> buffer = new ArrayList<>(batch);
        for (ChunkDraft draft : drafts) {
            buffer.add(toChunkEntity(docId, draft));
            if (buffer.size() >= batch) {
                chunkMapper.insertBatch(buffer);
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            chunkMapper.insertBatch(buffer);
        }
        log.debug("[RAG] MySQL 落库完成: docId={} chunks={}", docId, drafts.size());
        return docId;
    }

    /**
     * 删除文档的 MySQL 侧：切片行 + 文档行，一个事务。
     * <p>
     * 调用方必须已经删完 ES 再调这里。ES 删除是外部 HTTP，圈进事务会长时间占用
     * 连接与行锁；放在事务外，失败时 MySQL 行还在，就还留着"该按哪个 doc_id
     * 清理孤儿切片"的线索。
     */
    @Transactional
    public void deleteDocument(Long docId) {
        int chunks = chunkMapper.deleteByDocId(docId);
        docMapper.deleteById(docId);
        log.info("[RAG] MySQL 侧删除完成: docId={} chunks={}", docId, chunks);
    }

    private RagChunk toChunkEntity(Long docId, ChunkDraft draft) {
        RagChunk c = new RagChunk();
        c.setDocId(docId);
        c.setSeq(draft.seq());
        c.setContent(draft.content());
        c.setTokenCount(draft.tokenCount());
        c.setPageNo(draft.pageNo());
        c.setChapterPath(draft.chapterPath());
        c.setIndexStatus(RagChunk.IX_PENDING);
        return c;
    }
}
