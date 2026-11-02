package org.leon.aimodule.rag.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.chunk.ChunkPlanner;
import org.leon.aimodule.rag.chunk.TokenCounter;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.embedding.EmbeddingGateway;
import org.leon.aimodule.rag.entity.RagChunk;
import org.leon.aimodule.rag.entity.RagDocument;
import org.leon.aimodule.rag.exception.EmbeddingException;
import org.leon.aimodule.rag.exception.RagException;
import org.leon.aimodule.rag.index.RagIndexWriter;
import org.leon.aimodule.rag.mapper.RagChunkMapper;
import org.leon.aimodule.rag.mapper.RagDocumentMapper;
import org.leon.aimodule.rag.parse.ChunkDraft;
import org.leon.aimodule.rag.parse.PlainTextDocumentParser;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入库编排的测试。真实解析器 + 真实 ChunkPlanner，只 mock 三个外部边界
 * （MySQL mapper、embedding、ES writer）—— 测的是编排顺序与失败语义，
 * 解析/切片质量由各自的测试负责。
 */
class RagDocumentServiceTest {

    private RagDocumentMapper docMapper;
    private RagChunkMapper chunkMapper;
    private RagChunkPersister persister;
    private EmbeddingGateway embeddingGateway;
    private RagIndexWriter indexWriter;
    private RagProperties props;
    private RagDocumentService service;

    /** 一篇够长、能切出多片的中文教材 */
    private static final String TEXTBOOK = """
            第三章 排序算法

            3.1 快速排序

            快速排序是分治算法：每轮选一个基准，把小于基准的放左边、大于基准的放右边。
            平均时间复杂度 O(nlogn)，最坏情况（已有序且取端点为基准）退化为 O(n^2)。

            3.2 归并排序

            归并排序稳定，时间复杂度恒为 O(nlogn)，代价是 O(n) 额外空间。
            """;

    @BeforeEach
    void setUp() {
        docMapper = mock(RagDocumentMapper.class);
        chunkMapper = mock(RagChunkMapper.class);
        persister = mock(RagChunkPersister.class);
        embeddingGateway = mock(EmbeddingGateway.class);
        indexWriter = mock(RagIndexWriter.class);
        props = new RagProperties();

        service = new RagDocumentService(
                List.of(new PlainTextDocumentParser()),
                new ChunkPlanner(new TokenCounter()),
                embeddingGateway, indexWriter, persister, docMapper, chunkMapper, props);

        // 默认路径：没有重复文档，persist 回填 docId=7
        when(docMapper.findByHash(anyString(), anyString(), anyString())).thenReturn(null);
        when(persister.persist(any(), anyList())).thenAnswer(inv -> {
            RagDocument doc = inv.getArgument(0);
            doc.setId(7L);
            return 7L;
        });
    }

    private IngestCommand cmd(String fileName, String scope, String owner, String text) {
        return new IngestCommand(fileName, "text/plain", null, "《算法基础》",
                scope, owner, 12L, text.getBytes(StandardCharsets.UTF_8));
    }

    /** persist 落库后服务会读回切片（带 id），这里按 drafts 造出对应实体 */
    private void stubChunksReadBack(int count) {
        when(chunkMapper.findByDocId(7L)).thenAnswer(inv -> {
            List<RagChunk> chunks = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                RagChunk c = new RagChunk();
                c.setId(900L + i);
                c.setDocId(7L);
                c.setSeq(i);
                c.setContent("切片正文" + i);
                c.setTokenCount(10);
                c.setIndexStatus(RagChunk.IX_PENDING);
                chunks.add(c);
            }
            return chunks;
        });
    }

    private void stubEmbedding(int count) {
        when(embeddingGateway.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            assertEquals(count, texts.size(), "embedding 条数必须与切片数一致");
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(new float[]{0.1f * i});
            }
            return vectors;
        });
    }

    @Test
    @DisplayName("完整链路：解析→切片→MySQL→embedding→ES→indexed，顺序正确")
    void fullPipeline() {
        stubChunksReadBack(2);
        stubEmbedding(2);
        when(indexWriter.write(anyList(), anyList(), any())).thenReturn(List.of(901L, 902L));

        RagDocument doc = service.ingest(cmd("算法.txt", "personal", "alice", TEXTBOOK));

        assertEquals(RagDocument.ST_INDEXED, doc.getStatus());
        assertEquals(7L, doc.getId());
        assertTrue(doc.getChunkCount() >= 2, "长文应切出多片，实际 " + doc.getChunkCount());
        // 标题来自解析器（正文首个非空行），不是文件名
        assertEquals("第三章 排序算法", doc.getTitle());

        // 关键顺序验证：MySQL persist 必须发生在 embedding 之前。
        // InOrder 只能校验创建时传进去的 mock，漏一个就会在 verify 时抛异常
        var inOrder = org.mockito.Mockito.inOrder(
                persister, embeddingGateway, indexWriter, docMapper, chunkMapper);
        inOrder.verify(persister).persist(any(), anyList());
        inOrder.verify(docMapper).updateStatus(7L, RagDocument.ST_EMBEDDING);
        inOrder.verify(embeddingGateway).embed(anyList());
        inOrder.verify(indexWriter).write(anyList(), anyList(), any());
        inOrder.verify(chunkMapper).markIndexedBatch(anyList(), eq(RagChunk.IX_INDEXED));
        inOrder.verify(docMapper).markIndexed(eq(7L), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("切片的章节路径来自 Markdown/编号标题识别")
    void chapterPathCaptured() {
        stubChunksReadBack(2);
        stubEmbedding(2);

        service.ingest(cmd("算法.txt", "personal", "alice", TEXTBOOK));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChunkDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(persister).persist(any(), captor.capture());
        List<ChunkDraft> drafts = captor.getValue();
        assertTrue(drafts.stream().anyMatch(d -> d.chapterPath() != null && d.chapterPath().contains("排序算法")),
                "编号标题应被识别为章节路径，实际: " + drafts.stream().map(ChunkDraft::chapterPath).toList());
    }

    @Test
    @DisplayName("重复文件（同 hash）直接返回旧行，不再付 embedding 的钱")
    void duplicateSkipped() {
        RagDocument existing = new RagDocument();
        existing.setId(3L);
        existing.setStatus(RagDocument.ST_INDEXED);
        when(docMapper.findByHash(eq("personal"), eq("alice"), anyString())).thenReturn(existing);

        RagDocument result = service.ingest(cmd("算法.txt", "personal", "alice", TEXTBOOK));

        assertEquals(3L, result.getId());
        verify(persister, never()).persist(any(), anyList());
        verify(embeddingGateway, never()).embed(anyList());
        verify(indexWriter, never()).write(anyList(), anyList(), any());
    }

    @Test
    @DisplayName("同内容不同归属不算重复：alice 个人库与官方库互不影响")
    void differentOwnersNotDuplicate() {
        // 官方库已有同 hash 文档
        RagDocument official = new RagDocument();
        official.setId(5L);
        when(docMapper.findByHash(eq("official"), eq(""), anyString())).thenReturn(official);
        // alice 的个人库没有
        when(docMapper.findByHash(eq("personal"), eq("alice"), anyString())).thenReturn(null);
        stubChunksReadBack(2);
        stubEmbedding(2);

        RagDocument doc = service.ingest(cmd("算法.txt", "personal", "alice", TEXTBOOK));

        assertEquals(7L, doc.getId(), "alice 传同一份文件应正常入库，不被官方库的去重拦住");
        verify(persister).persist(any(), anyList());
    }

    @Test
    @DisplayName("上次失败的行不挡重传：清掉残留后重新入库")
    void failedRowDoesNotBlockRetry() throws Exception {
        // 第一次上传因 embedding 抖动失败，failed 行留在库里占着 hash 唯一键
        RagDocument failed = new RagDocument();
        failed.setId(3L);
        failed.setStatus(RagDocument.ST_FAILED);
        failed.setErrorMsg("embedding 调用失败");
        when(docMapper.findByHash(eq("personal"), eq("alice"), anyString())).thenReturn(failed);
        stubChunksReadBack(2);
        stubEmbedding(2);

        RagDocument result = service.ingest(cmd("算法.txt", "personal", "alice", TEXTBOOK));

        // 重传必须真的重新入库，而不是把那条 failed 旧行当"成功"返回
        assertEquals(7L, result.getId(), "应入库为新文档，不是返回失败的旧行");
        assertEquals(RagDocument.ST_INDEXED, result.getStatus());
        verify(persister).deleteDocument(3L);
        verify(indexWriter).deleteByDocId(3L);
        verify(persister).persist(any(), anyList());
    }

    @Test
    @DisplayName("清理旧行的 ES 删除失败时不阻断重传")
    void clearFailureToleratesEsError() throws Exception {
        RagDocument failed = new RagDocument();
        failed.setId(3L);
        failed.setStatus(RagDocument.ST_FAILED);
        when(docMapper.findByHash(eq("personal"), eq("alice"), anyString())).thenReturn(failed);
        // ES 挂着：孤儿切片最坏只是检索到一条查不到出处的内容，不该让重传整个失败
        when(indexWriter.deleteByDocId(3L)).thenThrow(new java.io.IOException("connection refused"));
        stubChunksReadBack(2);
        stubEmbedding(2);

        RagDocument result = service.ingest(cmd("算法.txt", "personal", "alice", TEXTBOOK));

        assertEquals(RagDocument.ST_INDEXED, result.getStatus());
        verify(persister).deleteDocument(3L);
    }

    @Test
    @DisplayName("embedding 失败：文档标 failed，ES 不写，异常上抛")
    void embeddingFailureMarksFailed() {
        stubChunksReadBack(2);
        when(embeddingGateway.embed(anyList()))
                .thenThrow(new EmbeddingException("百炼服务不可用"));

        RagException e = assertThrows(RagException.class,
                () -> service.ingest(cmd("算法.txt", "personal", "alice", TEXTBOOK)));
        assertTrue(e.getMessage().contains("百炼服务不可用"), "错误原因必须透传给前端: " + e.getMessage());

        verify(docMapper).markFailed(eq(7L), anyString());
        verify(indexWriter, never()).write(anyList(), anyList(), any());
        // MySQL 切片不回滚：正文还在，reindex 可以重放，不必让用户重新上传
        verify(chunkMapper, never()).deleteByDocId(anyLong());
    }

    @Test
    @DisplayName("ES 写入失败：文档标 failed，切片留在 pending 等重放")
    void esFailureMarksFailed() {
        stubChunksReadBack(2);
        stubEmbedding(2);
        when(indexWriter.write(anyList(), anyList(), any()))
                .thenThrow(new RagException("写入 ES 失败: connection refused"));

        assertThrows(RagException.class, () -> service.ingest(cmd("算法.txt", "personal", "alice", TEXTBOOK)));

        verify(docMapper).markFailed(eq(7L), anyString());
        verify(chunkMapper, never()).markIndexedBatch(anyList(), anyString());
    }

    @Test
    @DisplayName("校验：不支持的扩展名在解析前就拒，不落库")
    void rejectsBadExtension() {
        RagException e = assertThrows(RagException.class,
                () -> service.ingest(cmd("病毒.exe", "personal", "alice", TEXTBOOK)));

        assertTrue(e.getMessage().contains("扩展名"));
        verify(docMapper, never()).insertReturningId(any());
        verify(persister, never()).persist(any(), anyList());
    }

    @Test
    @DisplayName("校验：personal 必须有登录身份")
    void personalRequiresOwner() {
        RagException e = assertThrows(RagException.class,
                () -> service.ingest(cmd("算法.txt", "personal", "", TEXTBOOK)));

        assertTrue(e.getMessage().contains("登录身份"));
    }

    @Test
    @DisplayName("校验：非法 scope 直接拒")
    void rejectsBadScope() {
        RagException e = assertThrows(RagException.class,
                () -> service.ingest(cmd("算法.txt", "public", "alice", TEXTBOOK)));

        assertTrue(e.getMessage().contains("scope"));
    }

    @Test
    @DisplayName("校验：超过大小上限直接拒")
    void rejectsOversize() {
        props.getIngest().setMaxFileBytes(10);

        RagException e = assertThrows(RagException.class,
                () -> service.ingest(cmd("算法.txt", "personal", "alice", TEXTBOOK)));

        assertTrue(e.getMessage().contains("大小上限"));
    }

    @Test
    @DisplayName("校验：空文件直接拒")
    void rejectsEmptyFile() {
        assertThrows(RagException.class,
                () -> service.ingest(cmd("空.txt", "personal", "alice", "")));
    }

    @Test
    @DisplayName("official 入库 owner_name 归一为空串（唯一键去重的前提）")
    void officialOwnerNormalized() {
        stubChunksReadBack(2);
        stubEmbedding(2);

        service.ingest(cmd("算法.txt", "official", "", TEXTBOOK));

        ArgumentCaptor<RagDocument> captor = ArgumentCaptor.forClass(RagDocument.class);
        verify(persister).persist(captor.capture(), anyList());
        assertEquals("", captor.getValue().getOwnerName());
        assertEquals(RagDocument.SCOPE_OFFICIAL, captor.getValue().getScope());
    }

    @Test
    @DisplayName("用户显式给 title 时不被解析结果覆盖")
    void explicitTitleWins() {
        stubChunksReadBack(2);
        stubEmbedding(2);
        IngestCommand cmd = new IngestCommand("算法.txt", "text/plain", "我的自定义标题", null,
                "personal", "alice", null, TEXTBOOK.getBytes(StandardCharsets.UTF_8));

        RagDocument doc = service.ingest(cmd);

        assertEquals("我的自定义标题", doc.getTitle());
    }

    @Test
    @DisplayName("subjectId 缺省按 0（全学科通用）入库")
    void subjectIdDefaultsToZero() {
        stubChunksReadBack(2);
        stubEmbedding(2);
        IngestCommand cmd = new IngestCommand("算法.txt", "text/plain", null, null,
                "personal", "alice", null, TEXTBOOK.getBytes(StandardCharsets.UTF_8));

        service.ingest(cmd);

        ArgumentCaptor<RagDocument> captor = ArgumentCaptor.forClass(RagDocument.class);
        verify(persister).persist(captor.capture(), anyList());
        assertEquals(0L, captor.getValue().getSubjectId());
    }

    @Test
    @DisplayName("fileHash 是稳定 SHA-256：同内容两次计算结果一致")
    void hashIsStable() {
        String h1 = RagDocumentService.sha256(TEXTBOOK.getBytes(StandardCharsets.UTF_8));
        String h2 = RagDocumentService.sha256(TEXTBOOK.getBytes(StandardCharsets.UTF_8));
        String h3 = RagDocumentService.sha256((TEXTBOOK + "多一个字").getBytes(StandardCharsets.UTF_8));

        assertEquals(64, h1.length(), "SHA-256 十六进制应为 64 位，与 CHAR(64) 列宽一致");
        assertEquals(h1, h2);
        assertNotNull(h3);
        assertTrue(!h1.equals(h3), "内容不同 hash 必须不同");
        assertTrue(h1.matches("[0-9a-f]{64}"), "必须是小写十六进制");
    }

    @Test
    @DisplayName("reindex 只重放未进 ES 的切片，不重复 embedding 已索引部分")
    void reindexOnlyPending() {
        RagDocument doc = new RagDocument();
        doc.setId(7L);
        doc.setFileName("算法.txt");
        doc.setScope("personal");
        doc.setOwnerName("alice");
        when(docMapper.selectById(7L)).thenReturn(doc);

        // 3 片里只有 1 片没进 ES
        RagChunk pending = new RagChunk();
        pending.setId(903L);
        pending.setContent("未索引的切片");
        pending.setTokenCount(5);
        when(chunkMapper.findNotIndexed(7L)).thenReturn(List.of(pending));
        when(embeddingGateway.embed(anyList())).thenReturn(List.of(new float[]{0.5f}));
        when(chunkMapper.countByDocId(7L)).thenReturn(3);
        RagChunk full = new RagChunk();
        full.setTokenCount(10);
        when(chunkMapper.findByDocId(7L)).thenReturn(List.of(full, full, pending));

        RagDocument result = service.reindex(7L);

        assertEquals(RagDocument.ST_INDEXED, result.getStatus());
        assertEquals(3, result.getChunkCount());
        // 只对 1 条 pending 切片做 embedding，而不是全部 3 条
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingGateway).embed(captor.capture());
        assertEquals(List.of("未索引的切片"), captor.getValue());
        verify(chunkMapper).markIndexedBatch(eq(List.of(903L)), eq(RagChunk.IX_INDEXED));
    }

    @Test
    @DisplayName("reindex 没有待补切片时直接返回，不打 embedding")
    void reindexNoopWhenAllIndexed() {
        RagDocument doc = new RagDocument();
        doc.setId(7L);
        when(docMapper.selectById(7L)).thenReturn(doc);
        when(chunkMapper.findNotIndexed(7L)).thenReturn(List.of());

        RagDocument result = service.reindex(7L);

        assertEquals(doc, result);
        verify(embeddingGateway, never()).embed(anyList());
    }
}
