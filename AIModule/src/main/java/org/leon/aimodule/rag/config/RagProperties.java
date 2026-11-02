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
    private Ingest ingest = new Ingest();

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

    /**
     * 文档入库（上传 → 切片 → MySQL → embedding → ES）相关配置。
     * 上传是同步接口，这里的上限就是"一次请求最多干多少活"的闸门。
     */
    @Data
    public static class Ingest {
        /** 单文件字节上限；超过直接拒，不等到解析阶段才失败 */
        private long maxFileBytes = 20L * 1024 * 1024;
        /** 单文档切片数上限；正常教材远达不到，防的是"一行一个字的畸形文件"炸出上万条 */
        private int maxChunksPerDoc = 2000;
        /** 一次 bulk 写 MySQL 的切片条数 */
        private int mysqlBatchSize = 200;
        /** 原文件落盘目录；留空则只存元数据不存原件（重建索引时靠 rag_chunk.content 重放） */
        private String storageDir = "";
        /** 允许的扩展名，小写；不在名单内直接拒，避免把可执行文件当文档收进来 */
        private List<String> allowedExtensions = new ArrayList<>(List.of("txt", "md", "markdown", "text"));
    }
}
