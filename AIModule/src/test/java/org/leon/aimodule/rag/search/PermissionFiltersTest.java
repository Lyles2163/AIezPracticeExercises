package org.leon.aimodule.rag.search;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import jakarta.json.stream.JsonGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.exception.RagException;

import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionFiltersTest {

    private final HybridQueryBuilder builder = new HybridQueryBuilder();
    private final JsonpMapper mapper = new JacksonJsonpMapper();
    private final RagProperties.Search search = new RagProperties.Search();

    private float[] vector() {
        float[] v = new float[1024];
        v[0] = 1f;
        return v;
    }

    private String json(SearchRequest request) {
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = mapper.jsonProvider().createGenerator(writer)) {
            request.serialize(generator, mapper);
        }
        return writer.toString();
    }

    /** 两条腿合并后的 JSON：融合在 Java 侧做，所以权限 filter 必须分别落到两个请求里 */
    private String bothLegs(List<Query> filters) {
        return json(builder.bm25Request("ai_kb_chunk", "快排为什么是 O(nlogn)", filters, 50))
                + json(builder.knnRequest("ai_kb_chunk", vector(), filters, search, 50));
    }

    @Test
    @DisplayName("权限 filter 在 BM25 与 kNN 两条腿里各出现一次，且写到序列化后的 JSON 里")
    void permissionFilterAppearsTwice() {
        List<Query> filters = PermissionFilters.build(List.of("official", "personal"), "alice", 12L);
        String json = bothLegs(filters);

        // 9.5.4 客户端的 term 查询序列化为 {"term":{"field":{"value":...}}}，不是简短形式
        assertEquals(2, count(json, "\"owner_name\":{\"value\":\"alice\"}"), json);
        assertEquals(2, count(json, "\"subject_id\":{\"value\":12}"), json);
        assertEquals(2, count(json, "\"subject_id\":{\"value\":0}"), json);
        assertFalse(json.contains("\"rank_constant\""), "basic 许可没有 rrf retriever：" + json);
        assertFalse(json.contains("\"retriever\""), "融合在 Java 侧：" + json);
        assertTrue(json.contains("\"similarity\":0.55"), json);
        assertFalse(json.contains("\"min_score\""), "RRF 的融合分不是 _score，不能设 min_score");
    }

    @Test
    @DisplayName("只查 official 时不出现 owner_name")
    void officialOnlyHasNoOwner() {
        List<Query> filters = PermissionFilters.build(List.of("official"), "alice", null);
        assertEquals(1, filters.size());
        String json = json(builder.bm25Request("ai_kb_chunk", "排序", filters, 50));
        assertFalse(json.contains("owner_name"));
        assertTrue(json.contains("\"scope\":{\"value\":\"official\"}"), json);
        assertFalse(json.contains("subject_id"));
    }

    @Test
    @DisplayName("只查 personal 时必须有 owner_name")
    void personalOnlyRequiresOwner() {
        List<Query> filters = PermissionFilters.build(List.of("personal"), "alice", null);
        String json = json(builder.bm25Request("ai_kb_chunk", "排序", filters, 50));
        assertTrue(json.contains("\"scope\":{\"value\":\"personal\"}"), json);
        assertTrue(json.contains("\"owner_name\":{\"value\":\"alice\"}"), json);
        assertFalse(json.contains("\"scope\":{\"value\":\"official\"}"));
    }

    @Test
    @DisplayName("subjectId=0 只匹配全学科通用")
    void zeroSubjectMeansGeneralOnly() {
        List<Query> filters = PermissionFilters.build(List.of("official"), "", 0L);
        String json = json(builder.bm25Request("ai_kb_chunk", "排序", filters, 50));
        assertTrue(json.contains("\"subject_id\":{\"value\":0}"), json);
        assertFalse(json.contains("\"subject_id\":{\"value\":12}"));
    }

    @Test
    @DisplayName("scope 缺省按 official+personal 处理；personal 没有登录身份直接拒绝")
    void emptyScopesDefaultToBothAndRequireOwner() {
        List<Query> nullScopes = PermissionFilters.build(null, "alice", null);
        String nullJson = json(builder.bm25Request("ai_kb_chunk", "排序", nullScopes, 50));
        assertTrue(nullJson.contains("\"scope\":{\"value\":\"official\"}"), nullJson);
        assertTrue(nullJson.contains("\"scope\":{\"value\":\"personal\"}"), nullJson);
        assertTrue(nullJson.contains("\"owner_name\":{\"value\":\"alice\"}"), nullJson);

        List<Query> emptyScopes = PermissionFilters.build(List.of(), "alice", null);
        String emptyJson = json(builder.bm25Request("ai_kb_chunk", "排序", emptyScopes, 50));
        assertTrue(emptyJson.contains("\"scope\":{\"value\":\"official\"}"), emptyJson);
        assertTrue(emptyJson.contains("\"scope\":{\"value\":\"personal\"}"), emptyJson);
        assertTrue(emptyJson.contains("\"owner_name\":{\"value\":\"alice\"}"), emptyJson);

        RagException explicit = assertThrows(RagException.class,
                () -> PermissionFilters.build(List.of("personal"), "  ", null));
        assertEquals("个人库检索需要登录身份", explicit.getMessage());
        RagException implicit = assertThrows(RagException.class,
                () -> PermissionFilters.build(null, null, null));
        assertEquals("个人库检索需要登录身份", implicit.getMessage());
    }

    private int count(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
