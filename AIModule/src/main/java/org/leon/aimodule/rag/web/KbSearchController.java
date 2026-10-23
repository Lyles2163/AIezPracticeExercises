package org.leon.aimodule.rag.web;

import jakarta.validation.Valid;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.exception.RagException;
import org.leon.aimodule.rag.search.DebugSearchResponse;
import org.leon.aimodule.rag.search.KnowledgeSearchResponse;
import org.leon.aimodule.rag.search.RetrievalQuery;
import org.leon.aimodule.rag.search.RetrievalService;
import org.leon.aimodule.rag.search.SearchOptions;
import org.leon.commonjwt.pojo.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/ai")
public class KbSearchController {

    private static final List<String> DEFAULT_SCOPES = List.of("official", "personal");

    private final RetrievalService retrievalService;
    private final RagAdminGuard adminGuard;
    private final RagProperties props;

    public KbSearchController(RetrievalService retrievalService, RagAdminGuard adminGuard,
                              RagProperties props) {
        this.retrievalService = retrievalService;
        this.adminGuard = adminGuard;
        this.props = props;
    }

    @PostMapping("/kb/search")
    public Result<KnowledgeSearchResponse> search(
            @RequestHeader("X-User-Name") String userName,
            @Valid @RequestBody RetrievalQuery query) throws IOException {
        return Result.success(retrievalService.searchKnowledge(query.getQuery(),
                options(userName, query)));
    }

    @PostMapping("/retrieval/debug")
    public Result<DebugSearchResponse> debug(
            @RequestHeader("X-User-Name") String userName,
            @Valid @RequestBody RetrievalQuery query) throws IOException {
        adminGuard.requireAdmin(userName);
        return Result.success(retrievalService.debugKnowledge(query.getQuery(),
                options(userName, query)));
    }

    private SearchOptions options(String userName, RetrievalQuery query) {
        List<String> scopes = query.getScope() == null || query.getScope().isEmpty()
                ? DEFAULT_SCOPES : query.getScope();
        for (String scope : scopes) {
            if (!"official".equals(scope) && !"personal".equals(scope)) {
                throw new RagException("scope 只能是 official 或 personal");
            }
        }
        if (scopes.contains("personal") && (userName == null || userName.isBlank())) {
            throw new RagException("个人库检索需要登录身份");
        }
        int topK = query.getTopK() == null
                ? props.getSearch().getTopKDefault() : query.getTopK();
        topK = Math.min(topK, props.getSearch().getTopKMax());
        return new SearchOptions(userName, scopes, query.getSubjectId(), topK);
    }
}
