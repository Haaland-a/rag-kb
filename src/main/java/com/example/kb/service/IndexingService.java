package com.example.kb.service;

import com.example.kb.config.AsyncConfig;
import com.example.kb.domain.KbChunk;
import com.example.kb.domain.KbDocument;
import com.example.kb.infrastructure.parser.Chunker;
import com.example.kb.infrastructure.parser.DocumentParser;
import com.example.kb.infrastructure.vectorstore.VectorStoreGateway;
import com.example.kb.repository.KbChunkRepository;
import com.example.kb.repository.KbDocumentRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/**
 * 索引进度流水线：上传文件 → 解析 → 切块 → 落 kb_chunk → 写 vector_store。
 *
 * <p>双轨落库的顺序很关键（改顺序会留下孤儿数据）：</p>
 * <ol>
 *   <li>先 insert kb_chunk，<b>拿到自增 id</b>；</li>
 *   <li>再调 {@link VectorStoreGateway#add} —— 它内部把 chunk.id 塞进 metadata 的
 *       chunkId，并生成 UUID 回填 chunk.vectorId；</li>
 *   <li>最后按 chunkIndex 把 vectorId 批量 UPDATE 回 kb_chunk.vector_id。</li>
 * </ol>
 *
 * <p>反过来（先写向量再落库）的话，第 2 步失败会留下没有 kb_chunk 行对应的孤儿向量，
 * 而向量表没有外键，永远清不掉。</p>
 *
 * <p>@Async 走 indexingExecutor 线程池（见 AsyncConfig），上传接口不等它。</p>
 */
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;
    private final VectorStoreGateway vectorStoreGateway;
    private final DocumentParser parser;
    private final Chunker chunker;

    public IndexingService(KbDocumentRepository documentRepository,
                           KbChunkRepository chunkRepository,
                           VectorStoreGateway vectorStoreGateway,
                           DocumentParser parser,
                           Chunker chunker) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.vectorStoreGateway = vectorStoreGateway;
        this.parser = parser;
        this.chunker = chunker;
    }

    /**
     * 同步版索引（重索引接口用）。
     *
     * @param storedPath 已落地的临时文件路径（调用方负责最后删除）
     */
    @Transactional
    public void indexBlocking(long documentId, Path storedPath, String fileType) throws IOException {
        KbDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("文档不存在: " + documentId));

        documentRepository.updateStatus(documentId, "PARSING", null);
        List<Document> parsed = parser.parse(storedPath, fileType);
        if (doc.getPageCount() == null && !parsed.isEmpty()) {
            documentRepository.updatePageCount(documentId, parsed.size());
        }

        documentRepository.updateStatus(documentId, "INDEXING", null);
        List<KbChunk> chunks = chunker.chunk(documentId, doc.getFilename(), parsed);
        if (chunks.isEmpty()) {
            documentRepository.updateStatus(documentId, "FAILED", "解析结果为空，文件可能无文本层（扫描件 PDF？）");
            return;
        }

        // 1) 先落结构化表，拿自增 id
        chunkRepository.insertAll(chunks);
        // 2) 再写向量（内部生成 UUID 并回填 chunk.vectorId）
        vectorStoreGateway.add(chunks);
        // 3) 回填关联键
        for (KbChunk c : chunks) {
            chunkRepository.updateVectorId(c.getId(), c.getVectorId());
        }
        documentRepository.updateChunkCount(documentId, chunks.size());
        documentRepository.updateStatus(documentId, "READY", null);
        log.info("索引完成 docId={} chunks={}", documentId, chunks.size());
    }

    /**
     * 异步版索引（上传接口用）。
     *
     * <p>⚠️ @Async 方法抛异常不会传播到调用方，所以这里自己 try/catch 落 FAILED 状态，
     * 否则前端会看到一条永远 PENDING 的记录。</p>
     *
     * <p>⚠️ @Async + @Transactional 一起用时，事务在调用方线程就已经提交了
     * （代理在进入异步方法前返回），所以异步方法内部不用再标 @Transactional，
     * 每一步的状态更新都是独立短事务，这正是我们要的（失败时能看到停在哪一步）。</p>
     *
     * <p>⚠️ 这也是为什么 indexBlocking 上的 @Transactional 只在同步入口生效：
     * 重索引（reindex）是同步调用，能享受整段事务回滚；上传是异步调用，
     * 事务实际不生效，靠的是一旦失败把状态改成 FAILED。</p>
     */
    @Async(AsyncConfig.INDEXING_EXECUTOR)
    public void indexAsync(long documentId, Path storedPath, String fileType) {
        boolean success = false;
        try {
            // 先清掉可能残留的旧切块：同一个 docId 走重试时（上传重传 / 重建索引），
            // 上一次失败已经 insertAll 过的行还在，不清就会翻倍。
            chunkRepository.deleteByDocumentId(documentId);
            indexBlocking(documentId, storedPath, fileType);
            success = true;
        }
        catch (Exception e) {
            log.error("索引失败 docId={}", documentId, e);
            // 失败时把本次已插入的切块清掉。它们既没进向量库、也不会被
            // updateChunkCount 记上，留在表里只会让详情页显示一堆「有内容但搜不到」的块。
            deleteQuietlyChunks(documentId);
            documentRepository.updateStatus(documentId, "FAILED", rootMessage(e));
        }
        finally {
            // ⚠️ 只有成功才删临时文件。
            // 原来是无条件删，导致失败文档的原文件也没了 —— 而重建索引
            // （DocumentService.reindex）正是靠这个文件重跑，结果失败一次就永久卡死，
            // 界面上只能看到「原文件已被清理，无法重索引」，用户除了删掉重传别无他法。
            if (success) {
                deleteQuietly(storedPath);
            }
        }
    }

    /** 清切块失败不该盖住真正的索引异常，所以单独吞掉 */
    private void deleteQuietlyChunks(long documentId) {
        try {
            chunkRepository.deleteByDocumentId(documentId);
        }
        catch (Exception e) {
            log.warn("清理残留切块失败 docId={}", documentId, e);
        }
    }

    /** 重新索引：删旧 chunk + 旧向量，再走同一套流程 */
    public void reindex(long documentId, Path storedPath, String fileType) throws IOException {
        vectorStoreGateway.deleteByDocumentId(documentId);
        chunkRepository.deleteByDocumentId(documentId);
        documentRepository.updateChunkCount(documentId, 0);
        indexBlocking(documentId, storedPath, fileType);
    }

    /** 删除某文档的全部向量（文档删除时用） */
    public void deleteVectorsOf(long documentId) {
        vectorStoreGateway.deleteByDocumentId(documentId);
    }

    /** 整篇 SHA-256，上传查重用 */
    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 不支持 SHA-256", e);
        }
    }

    public static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        }
        catch (IOException e) {
            log.warn("临时文件删除失败: {}", p, e);
        }
    }
}
