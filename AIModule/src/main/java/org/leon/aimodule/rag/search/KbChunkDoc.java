package org.leon.aimodule.rag.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KbChunkDoc {
    @JsonProperty("chunk_id") private Long chunkId;
    @JsonProperty("doc_id") private Long docId;
    private String scope;
    @JsonProperty("owner_name") private String ownerName;
    @JsonProperty("subject_id") private Long subjectId;
    private String title;
    private String content;
    private float[] vec;
    private String book;
    @JsonProperty("chapter_path") private String chapterPath;
    @JsonProperty("page_no") private Integer pageNo;
    @JsonProperty("token_count") private Integer tokenCount;
}
