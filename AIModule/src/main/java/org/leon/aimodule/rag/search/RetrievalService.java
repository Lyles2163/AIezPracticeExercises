package org.leon.aimodule.rag.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.embedding.EmbeddingGateway;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 唯一的检索读入口。权限 filter 只在这里拼装；controller 不得自己构造 ES query。
 * BM25 与 kNN 各查一次，再在 Java 侧做 RRF 融合（ES 原生 rrf retriever 需 Platinum+ 许可）。
 */
@Service
public class RetrievalService {

    private final ElasticsearchClient client;
    private final EmbeddingGateway embeddingGateway;
    private final HybridQueryBuilder queryBuilder;
    private final RagProperties props;

    public RetrievalService(ElasticsearchClient client, EmbeddingGateway embeddingGateway,
                            HybridQueryBuilder queryBuilder, RagProperties props) {
        this.client = client;
        this.embeddingGateway = embeddingGateway;
        this.queryBuilder = queryBuilder;
        this.props = props;
    }

    public KnowledgeSearchResponse searchKnowledge(String query, SearchOptions options) throws IOException {
        float[] queryVector = embedQuery(query);
        List<Query> filters = PermissionFilters.build(
                options.scopes(), options.ownerName(), options.subjectId());
        String alias = props.getIndex().getKbAlias();
        int window = props.getSearch().getRankWindowSize();

        SearchResponse<KbChunkDoc> bm25Response = client.search(
                queryBuilder.bm25Request(alias, query, filters, window), KbChunkDoc.class);
        SearchResponse<KbChunkDoc> knnResponse = client.search(
                queryBuilder.knnRequest(alias, queryVector, filters, props.getSearch(), window),
                KbChunkDoc.class);

        Map<String, KbChunkDoc> docsById = new LinkedHashMap<>();
        List<String> bm25Order = collectOrder(bm25Response, docsById);
        List<String> knnOrder = collectOrder(knnResponse, docsById);
        List<RrfFusion.FusedHit> fused = RrfFusion.fuse(bm25Order, knnOrder,
                props.getSearch().getRrfRankConstant(), options.topK());

        List<KnowledgeSearchItem> items = new ArrayList<>(fused.size());
        for (RrfFusion.FusedHit hit : fused) {
            KbChunkDoc doc = docsById.get(hit.id());
            if (doc == null) {
                continue;
            }
            items.add(new KnowledgeSearchItem(
                    doc.getChunkId(), doc.getDocId(), hit.rank(),
                    VectorMath.cosine(queryVector, doc.getVec()),
                    doc.getTitle(), doc.getBook(), doc.getChapterPath(),
                    doc.getPageNo(), doc.getContent()));
        }
        // 两条腿串行执行，took 取两次 ES 耗时之和
        return new KnowledgeSearchResponse((int) (bm25Response.took() + knnResponse.took()),
                items.isEmpty(), items);
    }

    /**
     * 调参专用：把两条腿各自的前 50 名摊开，定位是切片、embedding、BM25 还是 RRF 的问题。
     */
    public DebugSearchResponse debugKnowledge(String query, SearchOptions options) throws IOException {
        float[] queryVector = embedQuery(query);
        List<Query> filters = PermissionFilters.build(
                options.scopes(), options.ownerName(), options.subjectId());
        String alias = props.getIndex().getKbAlias();
        int window = props.getSearch().getRankWindowSize();

        SearchRequest bm25Request = queryBuilder.bm25Request(alias, query, filters, window);
        SearchRequest knnRequest = queryBuilder.knnRequest(alias, queryVector, filters,
                props.getSearch(), window);

        SearchResponse<KbChunkDoc> bm25Response = client.search(bm25Request, KbChunkDoc.class);
        SearchResponse<KbChunkDoc> knnResponse = client.search(knnRequest, KbChunkDoc.class);

        return new DebugSearchResponse(
                (int) Math.max(bm25Response.took(), knnResponse.took()),
                toLegItems(bm25Response, queryVector),
                toLegItems(knnResponse, queryVector));
    }

    private List<String> collectOrder(SearchResponse<KbChunkDoc> response, Map<String, KbChunkDoc> docsById) {
        List<String> order = new ArrayList<>();
        for (Hit<KbChunkDoc> hit : response.hits().hits()) {
            KbChunkDoc doc = hit.source();
            if (doc == null || hit.id() == null) {
                continue;
            }
            order.add(hit.id());
            docsById.putIfAbsent(hit.id(), doc);
        }
        return order;
    }

    private List<DebugLegItem> toLegItems(SearchResponse<KbChunkDoc> response, float[] queryVector) {
        List<DebugLegItem> items = new ArrayList<>();
        List<Hit<KbChunkDoc>> hits = response.hits().hits();
        for (int i = 0; i < hits.size(); i++) {
            Hit<KbChunkDoc> hit = hits.get(i);
            KbChunkDoc doc = hit.source();
            if (doc == null) {
                continue;
            }
            items.add(new DebugLegItem(doc.getChunkId(), doc.getDocId(), i + 1, hit.score(),
                    VectorMath.cosine(queryVector, doc.getVec()), doc.getTitle(), doc.getContent()));
        }
        return items;
    }

    private float[] embedQuery(String query) {
        return embeddingGateway.embed(List.of(query)).get(0);
    }
}
