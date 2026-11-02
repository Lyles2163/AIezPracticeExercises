package org.leon.aimodule.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.entity.RagChunk;
import org.leon.aimodule.rag.entity.RagDocument;
import org.leon.aimodule.rag.exception.RagException;
import org.leon.aimodule.rag.search.KbChunkDoc;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 切片 → ES 的唯一写入口。
 * <p>
 * 一条铁律：<b>ES 文档的 _id 必须等于 rag_chunk.id</b>。用同一个数字寻址，
 * 删文档时才能按 id 精确删掉 ES 里那批切片；否则删了 MySQL 行，
 * ES 里会留下一批永远检索得到、却查不到出处的孤儿切片。
 */
@Slf4j
@Component
public class RagIndexWriter {

    /** ES bulk 单次条数。过大易触发 429/内存压力，过小则往返太多 */
    private static final int BULK_SIZE = 200;

    private final ElasticsearchClient client;
    private final RagProperties props;

    public RagIndexWriter(ElasticsearchClient client, RagProperties props) {
        this.client = client;
        this.props = props;
    }

    /**
     * 把一批切片写入 ES。
     *
     * @return 成功写入的 chunk id 列表，供调用方回写 index_status
     * @throws RagException 传输失败，或存在条目级失败（部分成功时已成功的 id 仍会返回）
     */
    public List<Long> write(List<RagChunk> chunks, List<float[]> vectors, RagDocument doc) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        if (chunks.size() != vectors.size()) {
            // 契约错误：一一对应是前提，错位会把向量安到别人的正文上，属于最坏的一类静默错误
            throw new RagException("切片与向量条数不符: " + chunks.size() + " vs " + vectors.size());
        }

        String alias = props.getIndex().getKbAlias();
        List<Long> ok = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (int from = 0; from < chunks.size(); from += BULK_SIZE) {
            int to = Math.min(from + BULK_SIZE, chunks.size());
            List<BulkOperation> ops = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                RagChunk chunk = chunks.get(i);
                KbChunkDoc body = toDoc(chunk, vectors.get(i), doc);
                // _id = chunk.id：见类注释的铁律
                ops.add(BulkOperation.of(o -> o.index(idx -> idx
                        .index(alias).id(String.valueOf(chunk.getId())).document(body))));
            }
            try {
                BulkResponse resp = client.bulk(BulkRequest.of(b -> b.index(alias).operations(ops)));
                collect(resp, chunks, from, to, ok, failures);
            } catch (IOException e) {
                throw new RagException("写入 ES 失败: " + e.getMessage(), e);
            }
        }

        // 写完立刻 refresh：索引 refresh_interval 是 30s，不主动刷新的话
        // 用户传完文档马上提问会检索不到，看起来像"入库没生效"。
        try {
            client.indices().refresh(r -> r.index(alias));
        } catch (IOException e) {
            log.warn("[RAG] refresh 失败（不影响已写入数据）: {}", e.getMessage());
        }

        if (!failures.isEmpty()) {
            throw new RagException("ES 写入部分失败 " + failures.size() + " 条，例如: " + failures.get(0));
        }
        return ok;
    }

    /**
     * 按 doc_id 删除该文档的全部切片。
     * <p>
     * 用 delete_by_query 而不是按 id 逐条删：id 列表可能有几百个，
     * 而且 MySQL 行已经删掉时我们也拿不到完整 id 列表了。
     */
    public long deleteByDocId(long docId) throws IOException {
        String alias = props.getIndex().getKbAlias();
        var resp = client.deleteByQuery(d -> d
                .index(alias)
                .query(q -> q.term(t -> t.field("doc_id").value(docId)))
                .refresh(true)
                // 版本冲突（并发写）不算失败：重试也解决不了，跳过即可
                .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed));
        long deleted = resp.deleted() == null ? 0L : resp.deleted();
        log.info("[RAG] 已从 {} 删除 doc_id={} 的切片 {} 条", alias, docId, deleted);
        return deleted;
    }

    private void collect(BulkResponse resp, List<RagChunk> chunks, int from, int to,
                         List<Long> ok, List<String> failures) {
        List<BulkResponseItem> items = resp.items();
        for (int i = 0; i < items.size(); i++) {
            BulkResponseItem item = items.get(i);
            int chunkIndex = from + i;
            if (chunkIndex >= to) {
                break;
            }
            Long id = chunks.get(chunkIndex).getId();
            if (item.error() == null) {
                ok.add(id);
            } else {
                failures.add("chunkId=" + id + " " + item.error().type() + ": " + item.error().reason());
            }
        }
    }

    private KbChunkDoc toDoc(RagChunk chunk, float[] vector, RagDocument doc) {
        KbChunkDoc d = new KbChunkDoc();
        d.setChunkId(chunk.getId());
        d.setDocId(doc.getId());
        d.setScope(doc.getScope());
        // official 的 owner_name 在库里是空串，检索时 PermissionFilters 对 official 不校验 owner
        d.setOwnerName(doc.getOwnerName() == null ? "" : doc.getOwnerName());
        d.setSubjectId(doc.getSubjectId() == null ? 0L : doc.getSubjectId());
        d.setTitle(doc.getTitle());
        d.setContent(chunk.getContent());
        d.setVec(vector);
        d.setBook(doc.getBook());
        d.setChapterPath(chunk.getChapterPath());
        d.setPageNo(chunk.getPageNo());
        d.setTokenCount(chunk.getTokenCount());
        return d;
    }
}
