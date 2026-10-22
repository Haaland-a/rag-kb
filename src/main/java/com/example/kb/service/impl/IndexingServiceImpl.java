package com.example.kb.service.impl;

import com.example.kb.config.AsyncConfig;
import com.example.kb.domain.po.KbChunk;
import com.example.kb.domain.po.KbDocument;
import com.example.kb.infrastructure.parser.Chunker;
import com.example.kb.infrastructure.parser.DocumentParser;
import com.example.kb.infrastructure.vectorstore.VectorStoreGateway;
import com.example.kb.mapper.KbChunkMapper;
import com.example.kb.mapper.KbDocumentMapper;
import com.example.kb.service.IDocumentService;
import com.example.kb.service.IIndexingService;
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

@Service
public class IndexingServiceImpl implements IIndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingServiceImpl.class);

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final VectorStoreGateway vectorStoreGateway;
    private final DocumentParser parser;
    private final Chunker chunker;

    public IndexingServiceImpl(KbDocumentMapper documentMapper,
                           KbChunkMapper chunkMapper,
                           VectorStoreGateway vectorStoreGateway,
                           DocumentParser parser,
                           Chunker chunker) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
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
        KbDocument doc = documentMapper.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("文档不存在: " + documentId));

        documentMapper.updateStatus(documentId, "PARSING", null);
        List<Document> parsed = parser.parse(storedPath, fileType);
        if (doc.getPageCount() == null && !parsed.isEmpty()) {
            documentMapper.updatePageCount(documentId, parsed.size());
        }

        documentMapper.updateStatus(documentId, "INDEXING", null);
        List<KbChunk> chunks = chunker.chunk(documentId, doc.getFilename(), parsed);
        if (chunks.isEmpty()) {
            documentMapper.updateStatus(documentId, "FAILED", "解析结果为空，文件可能无文本层（扫描件 PDF？）");
            return;
        }

        // 1) 先落结构化表，拿自增 id
        chunkMapper.insertAll(chunks);
        // 2) 再写向量（内部生成 UUID 并回填 chunk.vectorId）
        vectorStoreGateway.add(chunks);
        // 3) 回填关联键
        for (KbChunk c : chunks) {
            chunkMapper.updateVectorId(c.getId(), c.getVectorId());
        }
        documentMapper.updateChunkCount(documentId, chunks.size());
        documentMapper.updateStatus(documentId, "READY", null);
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
            chunkMapper.deleteByDocumentId(documentId);
            indexBlocking(documentId, storedPath, fileType);
            success = true;
        }
        catch (Exception e) {
            log.error("索引失败 docId={}", documentId, e);
            // 失败时把本次已插入的切块清掉。它们既没进向量库、也不会被
            // updateChunkCount 记上，留在表里只会让详情页显示一堆「有内容但搜不到」的块。
            deleteQuietlyChunks(documentId);
            documentMapper.updateStatus(documentId, "FAILED", rootMessage(e));
        }
        finally {
            // ⚠️ 只有成功才删临时文件。
            // 原来是无条件删，导致失败文档的原文件也没了 —— 而重建索引
            // （IDocumentService.reindex）正是靠这个文件重跑，结果失败一次就永久卡死，
            // 界面上只能看到「原文件已被清理，无法重索引」，用户除了删掉重传别无他法。
            if (success) {
                deleteQuietly(storedPath);
            }
        }
    }

    /** 清切块失败不该盖住真正的索引异常，所以单独吞掉 */
    private void deleteQuietlyChunks(long documentId) {
        try {
            chunkMapper.deleteByDocumentId(documentId);
        }
        catch (Exception e) {
            log.warn("清理残留切块失败 docId={}", documentId, e);
        }
    }

    /** 重新索引：删旧 chunk + 旧向量，再走同一套流程 */
    public void reindex(long documentId, Path storedPath, String fileType) throws IOException {
        vectorStoreGateway.deleteByDocumentId(documentId);
        chunkMapper.deleteByDocumentId(documentId);
        documentMapper.updateChunkCount(documentId, 0);
        indexBlocking(documentId, storedPath, fileType);
    }

    /** 删除某文档的全部向量（文档删除时用） */
    public void deleteVectorsOf(long documentId) {
        vectorStoreGateway.deleteByDocumentId(documentId);
    }

    /**
     * 整篇 SHA-256，上传查重用。
     *
     * <p>⚠️ 实现在 {@link IIndexingService#sha256(byte[])}，这里只是转发。
     * 静态方法不参与接口实现，两处各写一份实现看着一样、改了一边漏另一边才是真事故，
     * 所以只留接口那一份。</p>
     */
    public static String sha256(byte[] content) {
        return IIndexingService.sha256(content);
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
