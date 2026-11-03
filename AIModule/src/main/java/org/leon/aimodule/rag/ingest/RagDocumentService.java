package org.leon.aimodule.rag.ingest;

import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.rag.chunk.ChunkPlanner;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.embedding.EmbeddingGateway;
import org.leon.aimodule.rag.entity.RagChunk;
import org.leon.aimodule.rag.entity.RagDocument;
import org.leon.aimodule.rag.exception.RagException;
import org.leon.aimodule.rag.index.RagIndexWriter;
import org.leon.aimodule.rag.mapper.RagChunkMapper;
import org.leon.aimodule.rag.mapper.RagDocumentMapper;
import org.leon.aimodule.rag.parse.ChunkDraft;
import org.leon.aimodule.rag.parse.DocumentParser;
import org.leon.aimodule.rag.parse.ParsedDoc;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文档入库的总编排：解析 → 切片 → 落 MySQL → embedding → 写 ES → 回写状态。
 * <p>
 * <b>事务边界刻意分成两段</b>，而不是一个大事务包住全程：
 * <ol>
 *   <li>MySQL 落文档行 + 全部切片行 —— 单事务，由 {@link RagChunkPersister} 承载。
 *       拆成独立 Bean 是因为 @Transactional 靠代理生效，同类内部自调用
 *       （this.persist(...)）不经过代理，注解会被静默忽略。</li>
 *   <li>embedding + 写 ES —— <b>事务外</b>。这两步是数秒到数十秒的外部 HTTP，
 *       圈进数据库事务会长时间占着连接和行锁，拖垮连接池。
 *       ES 写失败时 MySQL 数据仍在，切片停在 pending，可据此重放。</li>
 * </ol>
 * 顺序铁律：<b>先 MySQL 后 ES</b>。反过来一旦 MySQL 失败，ES 里就会出现没有源的
 * 孤儿切片 —— 检索得到却查不到出处，而且很难清理。
 */
@Slf4j
@Service
public class RagDocumentService {

    private final List<DocumentParser> parsers;
    private final ChunkPlanner chunkPlanner;
    private final EmbeddingGateway embeddingGateway;
    private final RagIndexWriter indexWriter;
    private final RagChunkPersister persister;
    private final RagDocumentMapper docMapper;
    private final RagChunkMapper chunkMapper;
    private final RagProperties props;

    public RagDocumentService(List<DocumentParser> parsers, ChunkPlanner chunkPlanner,
                              EmbeddingGateway embeddingGateway, RagIndexWriter indexWriter,
                              RagChunkPersister persister, RagDocumentMapper docMapper,
                              RagChunkMapper chunkMapper, RagProperties props) {
        this.parsers = parsers;
        this.chunkPlanner = chunkPlanner;
        this.embeddingGateway = embeddingGateway;
        this.indexWriter = indexWriter;
        this.persister = persister;
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.props = props;
    }

    /**
     * 入库主流程，同步执行。
     *
     * @return 最终状态的文档；失败时也返回（status=failed 且带 errorMsg），
     *         同时抛 {@link RagException}，由 handler 转成 Result
     * @throws RagException 任一阶段失败
     */
    public RagDocument ingest(IngestCommand cmd) {
        validate(cmd);
        String hash = sha256(cmd.content());

        // 去重：同 (scope, owner, hash) 已存在就直接返回。
        // 重复上传不该再付一遍 embedding 的钱，也不该在 ES 里堆两份一样的切片。
        RagDocument dup = docMapper.findByHash(cmd.scope(), cmd.ownerName(), hash);
        if (dup != null) {
            if (RagDocument.ST_FAILED.equals(dup.getStatus())) {
                // 失败行不能挡路：否则同一份文件第一次上传失败（比如 embedding 服务
                // 抖动）后就永远传不上去 —— 用户重传看到"成功"，实际返回的是 failed 旧行，
                // ES 里什么都没有，属于静默撒谎。清掉旧行重新入库才是重传的真实语义。
                log.info("[RAG] 上次入库失败，清除旧行重传: docId={}", dup.getId());
                clearFailed(dup.getId());
            } else {
                log.info("[RAG] 命中重复文档，跳过入库: docId={} status={}", dup.getId(), dup.getStatus());
                return dup;
            }
        }

        RagDocument doc = newDocument(cmd, hash, cmd.content().length);
        try {
            ParsedDoc parsed = parse(cmd);
            // 用户没显式给标题时，用解析器从正文首行推出的标题覆盖文件名兜底值
            if (cmd.title() == null || cmd.title().isBlank()) {
                doc.setTitle(truncate(parsed.title(), 255));
            }
            List<ChunkDraft> drafts = chunkPlanner.plan(parsed);
            if (drafts.isEmpty()) {
                throw new RagException("文档切片后为空，没有可入库的内容");
            }
            int limit = props.getIngest().getMaxChunksPerDoc();
            if (drafts.size() > limit) {
                throw new RagException("切片数 " + drafts.size() + " 超过上限 " + limit);
            }

            // 阶段①：MySQL 落库（事务内，跨 Bean 调用才走代理）
            Long docId = persister.persist(doc, drafts);
            doc.setId(docId);

            // 阶段②：读回切片拿到 id → embedding → 写 ES（事务外）
            docMapper.updateStatus(docId, RagDocument.ST_EMBEDDING);
            List<RagChunk> chunks = chunkMapper.findByDocId(docId);
            indexToEs(doc, chunks);

            int tokens = 0;
            for (RagChunk c : chunks) {
                tokens += c.getTokenCount() == null ? 0 : c.getTokenCount();
            }
            docMapper.markIndexed(docId, chunks.size(), tokens);
            doc.setStatus(RagDocument.ST_INDEXED);
            doc.setChunkCount(chunks.size());
            doc.setTokenCount(tokens);
            log.info("[RAG] 文档入库完成: docId={} chunks={} tokens={}", docId, chunks.size(), tokens);
            // 回读一次：create_time/update_time 由 DB 的 DEFAULT CURRENT_TIMESTAMP(3) 生成，
            // 内存里的 doc 拿不到。不回读就会给前端返 null，列表页时间列全是"—"。
            RagDocument saved = docMapper.selectById(docId);
            return saved != null ? saved : doc;
        } catch (Exception e) {
            return fail(doc, cmd.fileName(), e);
        }
    }

    /**
     * 删除文档：先删 ES 切片，再删 MySQL 行。
     * <p>
     * 顺序与入库相反，因为失败模式不同：ES 删失败时 MySQL 行还在，
     * 文档仍可被管理、可重试删除；反过来先删 MySQL，一旦 ES 删失败，
     * 就再也不知道该按哪个 doc_id 去清理那批孤儿切片了。
     * <p>
     * 本方法<b>不带 @Transactional</b>：ES 删除是外部 HTTP，圈进事务会长时间占用
     * 连接与行锁。事务只包 MySQL 那两步，由 {@link RagChunkPersister#deleteDocument} 承载
     * （跨 Bean 调用才经过代理，注解才生效）。
     */
    public void delete(Long docId, String userName) {
        RagDocument doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new RagException("文档不存在");
        }
        requireOwned(doc, userName);
        try {
            long removed = indexWriter.deleteByDocId(docId);
            log.info("[RAG] 删除文档 docId={}，清理 ES 切片 {} 条", docId, removed);
        } catch (Exception e) {
            // ES 删不掉就别删 MySQL：留下行才留得住"该清理哪个 doc_id"的线索
            throw new RagException("清理检索索引失败，文档未删除: " + e.getMessage(), e);
        }
        persister.deleteDocument(docId);
    }

    /**
     * 重放：把某文档下还没进 ES 的切片重新 embedding + 写入。
     * <p>
     * MySQL 写成功而 ES 写失败时的补救入口 —— rag_chunk.content 存着切片正文，
     * 所以不必重新解析原文件，也不必让用户重新上传。
     */
    public RagDocument reindex(Long docId) {
        RagDocument doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new RagException("文档不存在");
        }
        List<RagChunk> pending = chunkMapper.findNotIndexed(docId);
        if (pending.isEmpty()) {
            log.info("[RAG] docId={} 没有待补切片，跳过重放", docId);
            return doc;
        }
        docMapper.updateStatus(docId, RagDocument.ST_EMBEDDING);
        try {
            indexToEs(doc, pending);
            int total = chunkMapper.countByDocId(docId);
            int tokens = 0;
            for (RagChunk c : chunkMapper.findByDocId(docId)) {
                tokens += c.getTokenCount() == null ? 0 : c.getTokenCount();
            }
            docMapper.markIndexed(docId, total, tokens);
            doc.setStatus(RagDocument.ST_INDEXED);
            doc.setChunkCount(total);
            doc.setTokenCount(tokens);
            log.info("[RAG] 重放完成: docId={} 本次补 {} 条，累计 {} 条", docId, pending.size(), total);
            return doc;
        } catch (Exception e) {
            return fail(doc, doc.getFileName(), e);
        }
    }

    /**
     * 强制重放：清空 ES 中该文档的全部切片，把 MySQL 侧 index_status 重置为 pending，
     * 再走一遍 embedding + 写 ES。
     * <p>
     * 与 {@link #reindex} 的区别：reindex 只补没进 ES 的切片，本方法会覆盖已 indexed 的，
     * 用于切片元数据（如 page_no）在 MySQL 侧修正后需要同步到 ES 的场景。
     * 切片正文存于 rag_chunk.content，无需重新解析原文件。
     */
    public RagDocument forceReindex(Long docId) {
        RagDocument doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new RagException("文档不存在");
        }
        // 纯文本格式没有页概念，旧数据可能带着假的"第 1 页"，重放前先清掉
        if (isPlainText(doc.getFileName())) {
            chunkMapper.clearPageNo(docId);
            log.info("[RAG] 强制重放前已清空纯文本文档的 page_no: docId={}", docId);
        }
        try {
            long removed = indexWriter.deleteByDocId(docId);
            log.info("[RAG] 强制重放前清理 ES 切片 docId={} {} 条", docId, removed);
        } catch (Exception e) {
            log.warn("[RAG] 强制重放前清理 ES 失败（继续，旧切片会被同 _id 覆盖）: docId={} err={}",
                    docId, e.getMessage());
        }
        chunkMapper.resetToPending(docId);
        return reindex(docId);
    }

    /**
     * 清理失败文档的残留，为重传腾出唯一键。
     * <p>
     * 失败可能发生在任一阶段：解析失败时既无切片也无 ES 数据；embedding/ES 阶段失败时
     * MySQL 已有切片、ES 可能已有部分数据。所以两边都要清。
     * ES 删除失败不阻断 —— 那批孤儿切片没有 doc 行引用，不会出现在任何列表里，
     * 最坏情况是检索到一条查不到出处的内容，下次成功入库会覆盖同 doc_id。
     */
    private void clearFailed(Long docId) {
        try {
            indexWriter.deleteByDocId(docId);
        } catch (Exception e) {
            log.warn("[RAG] 清理失败文档的 ES 残留时出错（忽略，继续重传）: docId={} err={}", docId, e.getMessage());
        }
        persister.deleteDocument(docId);
    }

    /** embedding + 写 ES + 回写切片状态。chunks 必须已带 id（ES 的 _id 用它） */
    private void indexToEs(RagDocument doc, List<RagChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        List<String> texts = new ArrayList<>(chunks.size());
        for (RagChunk c : chunks) {
            texts.add(c.getContent());
        }
        // EmbeddingGateway 保证返回顺序与入参一致，所以向量能按下标与 chunks 对齐
        List<float[]> vectors = embeddingGateway.embed(texts);
        indexWriter.write(chunks, vectors, doc);

        List<Long> ids = new ArrayList<>(chunks.size());
        for (RagChunk c : chunks) {
            ids.add(c.getId());
        }
        chunkMapper.markIndexedBatch(ids, RagChunk.IX_INDEXED);
    }

    private ParsedDoc parse(IngestCommand cmd) {
        for (DocumentParser parser : parsers) {
            if (parser.supports(cmd.fileName(), cmd.mimeType())) {
                try {
                    return parser.parse(cmd.content(), cmd.fileName());
                } catch (RagException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RagException("文档解析失败: " + e.getMessage(), e);
                }
            }
        }
        throw new RagException("不支持的文件类型: " + cmd.fileName());
    }

    private RagDocument fail(RagDocument doc, String fileName, Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        log.warn("[RAG] 文档入库失败: fileName={} err={}", fileName, msg);
        String brief = truncate(msg, 512);
        // 解析阶段就失败时文档行还没落库（id 为 null），无从更新，只回填内存对象
        if (doc.getId() != null) {
            docMapper.markFailed(doc.getId(), brief);
        }
        doc.setStatus(RagDocument.ST_FAILED);
        doc.setErrorMsg(brief);
        if (e instanceof RagException re) {
            throw re;
        }
        throw new RagException("文档入库失败: " + msg, e);
    }

    private void requireOwned(RagDocument doc, String userName) {
        // official 库只有管理员能删；personal 库只有上传者本人能删
        if (RagDocument.SCOPE_OFFICIAL.equals(doc.getScope())) {
            List<String> admins = props.getAdminNames();
            if (userName == null || admins == null || !admins.contains(userName)) {
                throw new RagException("仅管理员可删除官方库文档");
            }
            return;
        }
        if (userName == null || !userName.equals(doc.getOwnerName())) {
            throw new RagException("无权删除该文档");
        }
    }

    private RagDocument newDocument(IngestCommand cmd, String hash, int size) {
        RagDocument doc = new RagDocument();
        doc.setFileName(truncate(cmd.fileName(), 255));
        // 用户显式给了 title 就用它；否则先拿文件名去扩展名兜底，
        // 解析完成后 ingest() 会用 ParsedDoc.title()（正文首个非空行）覆盖 —— 那个更准
        doc.setTitle(truncate(cmd.title() == null || cmd.title().isBlank()
                ? baseName(cmd.fileName()) : cmd.title(), 255));
        doc.setBook(cmd.book());
        doc.setScope(cmd.scope());
        doc.setOwnerName(cmd.ownerName() == null ? "" : cmd.ownerName());
        doc.setSubjectId(cmd.subjectId() == null ? 0L : cmd.subjectId());
        doc.setFileHash(hash);
        doc.setFileSize((long) size);
        doc.setMimeType(cmd.mimeType());
        doc.setStatus(RagDocument.ST_PENDING);
        return doc;
    }

    /** 文件名去扩展名、去路径前缀，作为标题兜底 */
    private static String baseName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "未命名文档";
        }
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.isBlank() ? "未命名文档" : base;
    }

    private void validate(IngestCommand cmd) {
        if (cmd.content() == null || cmd.content().length == 0) {
            throw new RagException("文件内容为空");
        }
        long max = props.getIngest().getMaxFileBytes();
        if (cmd.content().length > max) {
            throw new RagException("文件超过大小上限 " + (max / 1024 / 1024) + "MB");
        }
        if (!RagDocument.SCOPE_OFFICIAL.equals(cmd.scope())
                && !RagDocument.SCOPE_PERSONAL.equals(cmd.scope())) {
            throw new RagException("scope 只能是 official 或 personal");
        }
        if (RagDocument.SCOPE_PERSONAL.equals(cmd.scope())
                && (cmd.ownerName() == null || cmd.ownerName().isBlank())) {
            throw new RagException("个人库文档需要登录身份");
        }
        String ext = extensionOf(cmd.fileName());
        List<String> allowed = props.getIngest().getAllowedExtensions();
        if (allowed != null && !allowed.isEmpty() && !allowed.contains(ext)) {
            throw new RagException("不支持的文件扩展名: ." + ext);
        }
    }

    /** SHA-256 十六进制小写；去重唯一键的组成部分 */
    static String sha256(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 纯文本格式没有页概念，与 PlainTextDocumentParser.EXTENSIONS 保持一致 */
    private static final java.util.Set<String> PLAIN_TEXT_EXTS =
            java.util.Set.of("txt", "md", "markdown", "text", "log", "csv");

    private static boolean isPlainText(String fileName) {
        return PLAIN_TEXT_EXTS.contains(extensionOf(fileName));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
