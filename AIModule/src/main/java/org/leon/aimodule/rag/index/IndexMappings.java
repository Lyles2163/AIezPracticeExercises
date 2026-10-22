package org.leon.aimodule.rag.index;

/**
 * 索引 mapping 的唯一生产者。analyzer 一旦建索引就不可改，
 * 切换 ik/cjk 等于重建索引，所以这里按 analyzer 整体替换。
 */
public final class IndexMappings {

    private IndexMappings() {
    }

    public static MappingSpec kb(String analyzer) {
        String settings = "ik".equalsIgnoreCase(analyzer) ? IK_SETTINGS : BASE_SETTINGS;
        String title = textField(analyzer);
        String content = "{\"type\":\"text\"," + analyzerProps(analyzer) + "}";
        String properties = """
                {"properties":{
                  "chunk_id":{"type":"long"},
                  "doc_id":{"type":"long"},
                  "scope":{"type":"keyword"},
                  "owner_name":{"type":"keyword"},
                  "subject_id":{"type":"long"},
                  "title":%s,
                  "content":%s,
                  "vec":{"type":"dense_vector","dims":1024,"index":true,"similarity":"cosine",
                         "index_options":{"type":"int8_hnsw","m":16,"ef_construction":100}},
                  "book":{"type":"keyword"},
                  "chapter_path":{"type":"keyword","index":false,"doc_values":true},
                  "page_no":{"type":"integer"},
                  "token_count":{"type":"integer"}
                }}""".formatted(title, content);
        return new MappingSpec(settings, properties);
    }

    public static MappingSpec question(String analyzer) {
        String settings = "ik".equalsIgnoreCase(analyzer) ? IK_SETTINGS : BASE_SETTINGS;
        String title = textField(analyzer);
        String analysis = "{\"type\":\"text\"," + analyzerProps(analyzer) + "}";
        String properties = """
                {"properties":{
                  "question_id":{"type":"long"},
                  "scope":{"type":"keyword"},
                  "owner_name":{"type":"keyword"},
                  "subject_id":{"type":"long"},
                  "title":%s,
                  "analysis":%s,
                  "vec":{"type":"dense_vector","dims":1024,"index":true,"similarity":"cosine",
                         "index_options":{"type":"int8_hnsw","m":16,"ef_construction":100}},
                  "tags":{"type":"keyword"},
                  "difficulty":{"type":"integer"},
                  "qtype":{"type":"keyword"}
                }}""".formatted(title, analysis);
        return new MappingSpec(settings, properties);
    }

    private static String textField(String analyzer) {
        return "{\"type\":\"text\"," + analyzerProps(analyzer) + ",\"fields\":{\"kw\":{\"type\":\"keyword\"}}}";
    }

    private static String analyzerProps(String analyzer) {
        if ("ik".equalsIgnoreCase(analyzer)) {
            return "\"analyzer\":\"zh_index\",\"search_analyzer\":\"zh_search\"";
        }
        return "\"analyzer\":\"cjk\"";
    }

    private static final String BASE_SETTINGS = """
            {"number_of_shards":1,"number_of_replicas":0,"refresh_interval":"30s"}""";

    private static final String IK_SETTINGS = """
            {"number_of_shards":1,"number_of_replicas":0,"refresh_interval":"30s",
             "analysis":{"analyzer":{
               "zh_index":{"tokenizer":"ik_max_word"},
               "zh_search":{"tokenizer":"ik_smart"}
             }}}""";
}
