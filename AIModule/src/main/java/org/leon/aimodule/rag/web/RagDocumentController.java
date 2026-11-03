package org.leon.aimodule.rag.web;

import org.leon.aimodule.rag.entity.RagChunk;
import org.leon.aimodule.rag.entity.RagDocument;
import org.leon.aimodule.rag.exception.RagException;
import org.leon.aimodule.rag.ingest.DocumentVo;
import org.leon.aimodule.rag.ingest.IngestCommand;
import org.leon.aimodule.rag.ingest.RagDocumentQueryService;
import org.leon.aimodule.rag.ingest.RagDocumentService;
import org.leon.commonjwt.pojo.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 知识库文档管理接口。
 * <p>
 * 必须待在 org.leon.aimodule.rag 包下：AiExceptionHandler 的
 * @RestControllerAdvice(basePackages = "org.leon.aimodule.rag") 只覆盖这个包，
 * 放到别处 RagException 就不会被转成 Result，前端拿到的是 Boot 默认错误体。
 * <p>
 * 权限口径与检索侧保持一致：official 需要管理员（RagAdminGuard），personal 看本人。
 */
@RestController
@RequestMapping("/ai/kb/documents")
public class RagDocumentController {

    private final RagDocumentService service;
    private final RagDocumentQueryService query;
    private final RagAdminGuard adminGuard;

    public RagDocumentController(RagDocumentService service, RagDocumentQueryService query,
                                 RagAdminGuard adminGuard) {
        this.service = service;
        this.query = query;
        this.adminGuard = adminGuard;
    }

    /**
     * 上传并入库（同步）。
     * <p>
     * scope=official 时要求管理员身份：官方库对所有人可见，
     * 谁都能往里塞内容等于谁都能污染全局检索结果。
     */
    @PostMapping(consumes = "multipart/form-data")
    public Result<DocumentVo> upload(@RequestHeader("X-User-Name") String userName,
                                     @RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "scope", defaultValue = "personal") String scope,
                                     @RequestParam(value = "title", required = false) String title,
                                     @RequestParam(value = "book", required = false) String book,
                                     @RequestParam(value = "subjectId", required = false) Long subjectId)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new RagException("未收到文件");
        }
        boolean official = RagDocument.SCOPE_OFFICIAL.equals(scope);
        if (official) {
            adminGuard.requireAdmin(userName);
        } else if (!RagDocument.SCOPE_PERSONAL.equals(scope)) {
            throw new RagException("scope 只能是 official 或 personal");
        }
        // official 的 owner_name 恒为空串：它不属于任何个人，
        // 也让唯一键 (scope, owner_name, file_hash) 能拦住官方库的重复上传
        String owner = official ? "" : requireUser(userName);

        IngestCommand cmd = new IngestCommand(file.getOriginalFilename(), file.getContentType(),
                title, book, scope, owner, subjectId, file.getBytes());
        return Result.success(DocumentVo.of(service.ingest(cmd)));
    }

    /** 文档列表：官方库全部 + 本人个人库。beforeId 是上一页最后一条的 id */
    @GetMapping
    public Result<List<DocumentVo>> list(@RequestHeader("X-User-Name") String userName,
                                         @RequestParam(value = "status", required = false) String status,
                                         @RequestParam(value = "beforeId", required = false) Long beforeId,
                                         @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return Result.success(query.list(userName, status, beforeId, limit).stream()
                .map(DocumentVo::of).toList());
    }

    @GetMapping("/{id}")
    public Result<DocumentVo> detail(@RequestHeader("X-User-Name") String userName, @PathVariable Long id) {
        return Result.success(DocumentVo.of(query.requireOwned(id, userName)));
    }

    /** 切片明细：排查"为什么这段话没被检索到"时看这里 */
    @GetMapping("/{id}/chunks")
    public Result<List<ChunkVo>> chunks(@RequestHeader("X-User-Name") String userName, @PathVariable Long id) {
        List<RagChunk> chunks = query.chunksOf(id, userName);
        return Result.success(chunks.stream().map(ChunkVo::of).toList());
    }

    /**
     * 重放：把还没进 ES 的切片重新 embedding + 写入。
     * <p>
     * MySQL 写成功而 ES 写失败时的补救入口。切片正文存在 rag_chunk.content 里，
     * 所以不必让用户重新上传，也不会重复付解析的成本。
     */
    @PostMapping("/{id}/reindex")
    public Result<DocumentVo> reindex(@RequestHeader("X-User-Name") String userName, @PathVariable Long id) {
        query.requireOwned(id, userName);
        return Result.success(DocumentVo.of(service.reindex(id)));
    }

    /**
     * 强制重放：清空 ES 旧切片并重置 MySQL 状态后重新 embedding + 写入。
     * <p>
     * 用于切片元数据（如 page_no）在 MySQL 侧修正后需要同步到 ES 的场景。
     * 会重新付一遍 embedding 的钱，正常补录请用 {@link #reindex}。
     */
    @PostMapping("/{id}/reindex/force")
    public Result<DocumentVo> forceReindex(@RequestHeader("X-User-Name") String userName, @PathVariable Long id) {
        query.requireOwned(id, userName);
        return Result.success(DocumentVo.of(service.forceReindex(id)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestHeader("X-User-Name") String userName, @PathVariable Long id) {
        service.delete(id, userName);
        return Result.success();
    }

    private static String requireUser(String userName) {
        if (userName == null || userName.isBlank()) {
            throw new RagException("个人库文档需要登录身份");
        }
        return userName;
    }

    /** 切片视图；content 截断到 500 字，列表页不需要整段正文 */
    public record ChunkVo(Long id, Integer seq, Integer tokenCount, Integer pageNo,
                          String chapterPath, String indexStatus, String content) {

        static ChunkVo of(RagChunk c) {
            String text = c.getContent() == null ? "" : c.getContent();
            return new ChunkVo(c.getId(), c.getSeq(), c.getTokenCount(), c.getPageNo(),
                    c.getChapterPath(), c.getIndexStatus(),
                    text.length() <= 500 ? text : text.substring(0, 500) + "…");
        }
    }
}
