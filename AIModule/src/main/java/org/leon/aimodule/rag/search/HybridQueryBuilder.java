package org.leon.aimodule.rag.search;

import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import org.leon.aimodule.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 两条腿的查询构造器。RRF 融合在 Java 侧完成（本机与生产都是 basic 许可，
 * ES 原生 rrf retriever 属 Platinum+ 功能），因此这里只生产 BM25 与 kNN 两个请求。
 * 权限 filter 必须同时传给两条腿，漏一条就泄露他人私人库。
 */
@Component
public class HybridQueryBuilder {

    public SearchRequest bm25Request(String index, String queryText, List<Query> filters, int size) {
        return SearchRequest.of(s -> s.index(index).size(size).query(bm25Query(queryText, filters)));
    }

    public SearchRequest knnRequest(String index, float[] vector, List<Query> filters,
                                    RagProperties.Search search, int size) {
        KnnSearch knn = KnnSearch.of(k -> k
                .field("vec")
                .queryVector(toList(vector))
                .k(search.getKnnK())
                .numCandidates(search.getNumCandidates())
                .similarity(search.getKnnSimilarity())
                .filter(filters));
        return SearchRequest.of(s -> s.index(index).size(size).knn(knn));
    }

    public Query bm25Query(String queryText, List<Query> filters) {
        return Query.of(q -> q.bool(b -> b
                .must(m -> m.multiMatch(mm -> mm.query(queryText).fields("title^2", "content")))
                .filter(filters)));
    }

    private List<Float> toList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }
}
