package org.leon.aimodule.rag.qa;

import org.leon.aimodule.rag.search.KnowledgeSearchItem;
import org.leon.aimodule.rag.search.KnowledgeSearchResponse;
import org.leon.aimodule.rag.search.RetrievalService;
import org.leon.aimodule.rag.search.SearchOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 RAG 检索结果包装成可喂给模型的上下文，并同时产出给前端展示的引用列表。
 * 这是检索与对话之间唯一的胶水层：chat 编排层只调这一个入口，
 * 不直接接触 RetrievalService，也不自己拼检索 prompt。
 */
@Service
public class RagContextService {

    private static final List<String> DEFAULT_SCOPES = List.of("official", "personal");
    private static final int DEFAULT_TOP_K = 6;

    private final RetrievalService retrievalService;

    public RagContextService(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    /**
     * 检索 + 拼装。任何检索异常都向上抛，由编排层决定降级还是报错。
     *
     * @return 非 null；检索为空时 context 为空串、sources 为空列表
     */
    public RagContext retrieve(String question, String userName, RagOptions options) throws Exception {
        List<String> scopes = options.scopes() == null || options.scopes().isEmpty()
                ? DEFAULT_SCOPES : options.scopes();
        // topK 兜底：null 或非正数都用默认值，防止 0 被 RRF 当成"取前 0 条"导致结果全丢
        int topK = options.topK() == null || options.topK() <= 0 ? DEFAULT_TOP_K : options.topK();
        SearchOptions so = new SearchOptions(userName, scopes, options.subjectId(), topK);
        KnowledgeSearchResponse resp = retrievalService.searchKnowledge(question, so);
        if (resp.empty()) {
            return RagContext.EMPTY;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是检索到的参考资料（每条以 [n] 编号，回答时如引用请在句末标注对应编号）：\n\n");
        List<RagCitation> citations = new ArrayList<>();
        int n = 1;
        for (KnowledgeSearchItem item : resp.items()) {
            // book（书名/课程名）优先；没传时回退到 title（已存文件名去扩展名），
            // 再没有才用占位符。避免上传时没填 book 就一律显示"未命名文档"。
            String docName = firstNonBlank(item.book(), item.title());
            String header = String.format("[%d] 《%s》", n,
                    docName == null ? "未命名文档" : docName);
            if (item.chapterPath() != null && !item.chapterPath().isBlank()) {
                header += " · " + item.chapterPath();
            }
            // title 与 book 不同时才追加（同值时重复展示无意义）
            if (item.title() != null && !item.title().isBlank()
                    && !item.title().equals(item.book())) {
                header += " · " + item.title();
            }
            if (item.pageNo() != null) {
                header += "（第 " + item.pageNo() + " 页）";
            }
            sb.append(header).append("\n");
            sb.append(item.content() == null ? "" : item.content().trim()).append("\n\n");
            citations.add(new RagCitation(n, item.docId(), item.chunkId(),
                    item.title(), item.book(), item.chapterPath(), item.pageNo(),
                    item.cosine()));
            n++;
        }
        return new RagContext(sb.toString(), citations);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    public record RagOptions(List<String> scopes, Long subjectId, Integer topK) {
    }

    public record RagCitation(int index, long docId, long chunkId,
                              String title, String book, String chapterPath,
                              Integer pageNo, Double cosine) {
    }

    /**
     * context 可直接拼进 system prompt；citations 原样发给前端展示。
     */
    public record RagContext(String context, List<RagCitation> citations) {
        public static final RagContext EMPTY = new RagContext("", List.of());

        public boolean empty() {
            return citations.isEmpty();
        }
    }
}
