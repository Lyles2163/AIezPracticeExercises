package org.leon.aimodule.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.config.EsClientFactory;
import org.leon.aimodule.rag.config.RagProperties;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("es-it")
class IndexRegistryIT {

    private static final String ALIAS = "test_ai_kb_chunk";
    private static final String PHYSICAL = "test_ai_kb_chunk_v1";

    private static ElasticsearchClient client;

    private static ElasticsearchClient client() throws Exception {
        if (client == null) {
            Assumptions.assumeTrue(System.getenv("ELASTIC_PASSWORD") != null, "需要 ELASTIC_PASSWORD");
            RagProperties.Elasticsearch es = new RagProperties.Elasticsearch();
            es.setUris(System.getenv().getOrDefault("ES_URIS", "https://127.0.0.1:9200"));
            es.setUsername(System.getenv().getOrDefault("ES_USERNAME", "elastic"));
            es.setPassword(System.getenv("ELASTIC_PASSWORD"));
            es.setCaCertPath(System.getenv().getOrDefault("ES_CA_CERT",
                    "F:/ES/elasticsearch-9.5.4/config/certs/http_ca.crt"));
            client = EsClientFactory.create(es);
        }
        return client;
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if (client == null) {
            return;
        }
        if (client.indices().exists(e -> e.index(PHYSICAL)).value()) {
            client.indices().delete(d -> d.index(PHYSICAL));
        }
        client.close();
    }

    @Test
    @DisplayName("测试索引建立后可被别名检索到，mapping 含 1024 维与 IK 分析器")
    void createsIndexWithAlias() throws Exception {
        ElasticsearchClient c = client();

        RagProperties props = new RagProperties();
        props.setAnalyzer("cjk");
        props.getIndex().setKbAlias(ALIAS);
        props.getIndex().setKbPhysical(PHYSICAL);
        IndexRegistry registry = new IndexRegistry(c, props);
        registry.deleteIndex(PHYSICAL);

        registry.ensureKbIndex();

        assertTrue(c.indices().existsAlias(a -> a.name(ALIAS)).value());

        GetMappingResponse response = c.indices().getMapping(m -> m.index(PHYSICAL));
        TypeMapping mapping = response.get(PHYSICAL).mappings();
        assertEquals(1024, mapping.properties().get("vec").denseVector().dims().intValue());
        assertEquals("cjk", mapping.properties().get("content").text().analyzer());
        assertEquals("cjk", mapping.properties().get("title").text().analyzer());
        String mappingJson = response.toString();
        assertTrue(mappingJson.contains("dims=1024") || mappingJson.contains("\"dims\":1024"));

        Map<String, Long> stats = registry.stats();
        assertTrue(stats.containsKey(ALIAS));
        assertFalse(stats.get(ALIAS) > 0);

        registry.deleteIndex(PHYSICAL);
    }
}
