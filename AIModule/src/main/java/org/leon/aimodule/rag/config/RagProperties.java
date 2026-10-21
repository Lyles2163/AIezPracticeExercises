package org.leon.aimodule.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** ik | cjk；只在 IndexRegistry 生成 mapping 时读取 */
    private String analyzer = "ik";

    /** 临时管理员名单：/ai/retrieval/debug、/ai/admin/**、official 上传 */
    private List<String> adminNames = new ArrayList<>();

    private Elasticsearch elasticsearch = new Elasticsearch();
    private Index index = new Index();
    private Embedding embedding = new Embedding();
    private Search search = new Search();

    /** poll | mq；阶段 4a 只实现 poll */
    private String async = "poll";

    @Data
    public static class Elasticsearch {
        private String uris = "https://127.0.0.1:9200";
        private String username = "elastic";
        private String password = "";
        private String caCertPath = "F:/ES/elasticsearch-9.5.4/config/certs/http_ca.crt";
    }

    @Data
    public static class Index {
        private boolean autoCreate = true;
        private String kbAlias = "ai_kb_chunk";
        private String kbPhysical = "ai_kb_chunk_v1";
        private String questionAlias = "ai_question";
        private String questionPhysical = "ai_question_v1";
    }

    @Data
    public static class Embedding {
        private int dimensions = 1024;
        private int batchSize = 10;
        private int concurrency = 4;
    }

    @Data
    public static class Search {
        private int topKDefault = 6;
        private int topKMax = 20;
        private int rrfRankConstant = 60;
        private int rankWindowSize = 50;
        private int knnK = 20;
        private int numCandidates = 100;
        private float knnSimilarity = 0.55f;
    }
}
