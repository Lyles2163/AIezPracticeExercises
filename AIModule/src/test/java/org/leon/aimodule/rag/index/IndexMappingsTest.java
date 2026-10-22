package org.leon.aimodule.rag.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexMappingsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("ik 模式使用 ik_max_word 建索引、ik_smart 查询")
    void ikMapping() {
        MappingSpec spec = IndexMappings.kb("ik");
        assertTrue(spec.settings().contains("ik_max_word"));
        assertTrue(spec.settings().contains("ik_smart"));
        assertTrue(spec.properties().contains("zh_index"));
        assertTrue(spec.properties().contains("zh_search"));
        assertTrue(spec.properties().contains("\"dims\":1024"));
        assertTrue(spec.properties().contains("int8_hnsw"));
    }

    @Test
    @DisplayName("cjk 模式只使用内置分析器，不声明 analysis 段")
    void cjkMapping() {
        MappingSpec spec = IndexMappings.kb("cjk");
        assertFalse(spec.settings().contains("analysis"));
        assertTrue(spec.properties().contains("\"analyzer\":\"cjk\""));
        assertFalse(spec.properties().contains("ik_"));
    }

    @Test
    @DisplayName("题目索引没有 book/chapter_path/page_no")
    void questionMapping() {
        String props = IndexMappings.question("ik").properties();
        assertTrue(props.contains("question_id"));
        assertTrue(props.contains("\"analysis\":{\"type\":\"text\""));
        assertFalse(props.contains("chapter_path"));
        assertFalse(props.contains("page_no"));
    }

    @Test
    @DisplayName("生成的 settings 与 properties 都是合法 JSON")
    void mappingJsonIsValid() throws Exception {
        for (MappingSpec spec : List.of(IndexMappings.kb("ik"), IndexMappings.kb("cjk"),
                IndexMappings.question("ik"), IndexMappings.question("cjk"))) {
            JSON.readTree(spec.settings());
            JSON.readTree(spec.properties());
        }
    }
}
