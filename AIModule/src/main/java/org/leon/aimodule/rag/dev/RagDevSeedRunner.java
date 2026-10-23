package org.leon.aimodule.rag.dev;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.embedding.EmbeddingGateway;
import org.leon.aimodule.rag.index.IndexRegistry;
import org.leon.aimodule.rag.search.KbChunkDoc;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.dev-seed", name = "enabled", havingValue = "true")
public class RagDevSeedRunner implements ApplicationRunner {

    private final IndexRegistry registry;
    private final EmbeddingGateway embeddingGateway;
    private final ElasticsearchClient client;
    private final RagProperties props;

    public RagDevSeedRunner(IndexRegistry registry, EmbeddingGateway embeddingGateway,
                            ElasticsearchClient client, RagProperties props) {
        this.registry = registry;
        this.embeddingGateway = embeddingGateway;
        this.client = client;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        registry.ensureKbIndex();
        String alias = props.getIndex().getKbAlias();
        if (client.count(c -> c.index(alias)).count() > 0) {
            log.info("[RAG] dev-seed 跳过：{} 已有数据", alias);
            return;
        }

        List<String> contents = List.of(
                "快速排序是分治算法：每轮选一个基准，把小于基准的放左边、大于的放右边，平均时间复杂度 O(nlogn)，最坏 O(n^2)。",
                "快速排序的空间复杂度来自递归栈，平均 O(logn)，最坏 O(n)。",
                "归并排序稳定，时间复杂度恒为 O(nlogn)，代价是 O(n) 额外空间。",
                "火车相遇问题：相遇时间 = 总路程 ÷ 两车速度之和，注意单位统一。",
                "全学科通用：算法复杂度分析只关心输入规模趋于无穷时的增长量级。"
        );
        long start = System.currentTimeMillis();
        List<float[]> vectors = embeddingGateway.embed(contents);
        log.info("[RAG] dev-seed embedding 5 条耗时 {} ms", System.currentTimeMillis() - start);

        List<BulkOperation> ops = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            KbChunkDoc doc = new KbChunkDoc();
            doc.setChunkId(9000L + i);
            doc.setDocId(9000L);
            doc.setScope("official");
            doc.setOwnerName("");
            doc.setSubjectId(i == 3 ? 12L : (i == 4 ? 0L : 12L));
            doc.setTitle("算法与数据结构 第3章");
            doc.setContent(contents.get(i));
            doc.setVec(vectors.get(i));
            doc.setBook("《算法基础（测试语料）》");
            doc.setChapterPath("第3章 → 3.2 常见排序算法");
            doc.setPageNo(40 + i);
            doc.setTokenCount(contents.get(i).length());
            int id = i;
            ops.add(BulkOperation.of(o -> o.index(idx -> idx
                    .index(alias).id(String.valueOf(9000 + id)).document(doc))));
        }
        client.bulk(BulkRequest.of(b -> b.index(alias).operations(ops)));
        registry.refresh(alias);
        log.info("[RAG] dev-seed 完成：5 条切片已写入 {}", alias);
    }
}
