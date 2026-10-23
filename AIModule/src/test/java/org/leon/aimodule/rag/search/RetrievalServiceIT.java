package org.leon.aimodule.rag.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.config.EsClientFactory;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.embedding.EmbeddingClient;
import org.leon.aimodule.rag.embedding.EmbeddingGateway;
import org.leon.aimodule.rag.index.IndexMappings;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("es-it")
class RetrievalServiceIT {

    private static final String ALIAS = "test_ai_kb_chunk";
    private static final String PHYSICAL = "test_ai_kb_chunk_v1";

    private static ElasticsearchClient client;
    private static RetrievalService service;
    private static float[] baseVector;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(System.getenv("ELASTIC_PASSWORD") != null, "需要 ELASTIC_PASSWORD");

        RagProperties.Elasticsearch es = new RagProperties.Elasticsearch();
        es.setUris(System.getenv().getOrDefault("ES_URIS", "https://127.0.0.1:9200"));
        es.setUsername(System.getenv().getOrDefault("ES_USERNAME", "elastic"));
        es.setPassword(System.getenv("ELASTIC_PASSWORD"));
        es.setCaCertPath(System.getenv().getOrDefault("ES_CA_CERT",
                "F:/ES/elasticsearch-9.5.4/config/certs/http_ca.crt"));
        client = EsClientFactory.create(es);

        if (client.indices().exists(e -> e.index(PHYSICAL)).value()) {
            client.indices().delete(d -> d.index(PHYSICAL));
        }
        client.indices().create(c -> c.index(PHYSICAL)
                .aliases(ALIAS, a -> a)
                .settings(s -> s.withJson(new StringReader(IndexMappings.kb("cjk").settings())))
                .mappings(m -> m.withJson(new StringReader(IndexMappings.kb("cjk").properties()))));

        baseVector = new float[1024];
        for (int i = 0; i < 10; i++) {
            baseVector[i] = 1f;
        }

        EmbeddingClient fake = texts -> texts.stream()
                .map(t -> t.contains("量子力学") ? thirdVector() : baseVector.clone())
                .toList();
        RagProperties props = new RagProperties();
        props.getEmbedding().setDimensions(1024);
        props.getEmbedding().setBatchSize(10);
        props.getEmbedding().setConcurrency(2);
        props.getIndex().setKbAlias(ALIAS);
        EmbeddingGateway gateway = new EmbeddingGateway(fake, props);
        service = new RetrievalService(client, gateway, new HybridQueryBuilder(), props);

        List<BulkOperation> ops = new ArrayList<>();
        ops.add(op(1, 41, "official", "", 12, "快速排序时间复杂度", "快速排序的时间复杂度是 O(nlogn)。", baseVector));
        ops.add(op(2, 42, "personal", "alice", 12, "快速排序练习", "alice 的私人讲义：快速排序平均复杂度。", baseVector));
        ops.add(op(3, 43, "personal", "bob", 12, "快速排序私人讲义", "bob 的私人讲义：快速排序只允许他自己搜到。", baseVector));
        ops.add(op(4, 44, "official", "", 0, "算法通用知识", "全学科通用的算法知识。", baseVector));
        ops.add(op(5, 45, "official", "", 12, "火车相遇", "火车相遇问题的解法与相对速度、总路程有关。", offsetVector()));
        client.bulk(BulkRequest.of(b -> b.index(PHYSICAL).operations(ops)));
        client.indices().refresh(r -> r.index(ALIAS));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (client != null) {
            if (client.indices().exists(e -> e.index(PHYSICAL)).value()) {
                client.indices().delete(d -> d.index(PHYSICAL));
            }
            client.close();
        }
    }

    private static BulkOperation op(long chunkId, long docId, String scope, String owner,
                                    long subjectId, String title, String content, float[] vector) {
        KbChunkDoc doc = new KbChunkDoc();
        doc.setChunkId(chunkId);
        doc.setDocId(docId);
        doc.setScope(scope);
        doc.setOwnerName(owner);
        doc.setSubjectId(subjectId);
        doc.setTitle(title);
        doc.setContent(content);
        doc.setVec(vector);
        doc.setBook("测试教材");
        doc.setChapterPath("第3章 → 3.2");
        doc.setPageNo(45);
        doc.setTokenCount(20);
        return BulkOperation.of(o -> o.index(i -> i.index(PHYSICAL).id(String.valueOf(chunkId)).document(doc)));
    }

    private static float[] offsetVector() {
        float[] v = new float[1024];
        for (int i = 100; i < 110; i++) {
            v[i] = 1f;
        }
        return v;
    }

    /** 与 baseVector、offsetVector 都正交，用于空召回测试 */
    private static float[] thirdVector() {
        float[] v = new float[1024];
        for (int i = 200; i < 210; i++) {
            v[i] = 1f;
        }
        return v;
    }

    @Test
    @DisplayName("alice 能搜到官方、自己的私人、全学科通用，搜不到 bob 的私人讲义")
    void permissionIsolation() throws Exception {
        KnowledgeSearchResponse response = service.searchKnowledge("快速排序",
                new SearchOptions("alice", List.of("official", "personal"), 12L, 10));

        assertFalse(response.empty());
        List<Long> docIds = response.items().stream().map(KnowledgeSearchItem::docId).toList();
        assertTrue(docIds.contains(41L), "官方文档应命中");
        assertTrue(docIds.contains(42L), "alice 自己的文档应命中");
        assertTrue(docIds.contains(44L), "subject=0 的通用文档应命中");
        assertFalse(docIds.contains(43L), "bob 的私人文档绝不能出现");
        assertFalse(docIds.contains(45L), "kNN 腿的 0.55 闸门应卡掉无关向量");
        System.out.printf("[RAG-IT] hybrid took=%d ms, 命中=%d, 第一条 cosine=%.4f%n",
                response.took(), response.items().size(), response.items().get(0).cosine());
    }

    @Test
    @DisplayName("debug 接口两条腿各有结果")
    void debugHasTwoLegs() throws Exception {
        DebugSearchResponse response = service.debugKnowledge("快速排序",
                new SearchOptions("alice", List.of("official", "personal"), 12L, 10));

        assertFalse(response.bm25().isEmpty(), "BM25 腿应有结果");
        assertFalse(response.knn().isEmpty(), "kNN 腿应有结果");
        assertTrue(response.bm25().stream().noneMatch(item -> item.docId() == 43L));
        assertTrue(response.knn().stream().noneMatch(item -> item.docId() == 43L));
        // cosine 只能来自 kNN 腿的 _score，BM25 腿的 _score 是 BM25 分，不能冒充余弦
        assertTrue(response.bm25().stream().allMatch(item -> item.cosine() == null),
                "BM25 腿不该产出 cosine");
        assertTrue(response.knn().stream().allMatch(item -> item.cosine() != null
                        && item.cosine() >= 0.55 - 1e-6),
                "kNN 腿的 cosine 必须由 score 反解且不低于 similarity 闸门");
        System.out.printf("[RAG-IT] debug bm25=%d knn=%d took=%d ms, knn 第一条 score=%.4f cosine=%.4f%n",
                response.bm25().size(), response.knn().size(), response.took(),
                response.knn().get(0).score(), response.knn().get(0).cosine());
    }

    @Test
    @DisplayName("完全无关的查询返回 empty，不编造结果")
    void emptyRecall() throws Exception {
        KnowledgeSearchResponse response = service.searchKnowledge("量子力学薛定谔方程",
                new SearchOptions("alice", List.of("official", "personal"), 12L, 6));
        assertTrue(response.empty());
        assertTrue(response.items().isEmpty());
    }

    @Test
    @DisplayName("只查 official 时私人文档不出现")
    void officialOnly() throws Exception {
        KnowledgeSearchResponse response = service.searchKnowledge("快速排序",
                new SearchOptions("alice", List.of("official"), 12L, 10));
        assertTrue(response.items().stream().noneMatch(item -> item.docId() == 42L));
    }

    @Test
    @DisplayName("cosine 与向量夹角一致，且 chunk/页码字段完整")
    void mapsFieldsAndCosine() throws Exception {
        KnowledgeSearchResponse response = service.searchKnowledge("快速排序",
                new SearchOptions("alice", List.of("official"), 12L, 10));
        KnowledgeSearchItem first = response.items().get(0);
        assertNotNull(first.cosine(), "官方命中的第一条应同时在 kNN 腿里，能反解 cosine");
        assertEquals(1.0, first.cosine(), 0.0001);
        assertEquals("测试教材", first.book());
        assertEquals(45, first.pageNo());
    }
}
