package com.example.kb.service.impl;

import com.example.kb.domain.po.KbChunk;
import com.example.kb.domain.po.KbDocument;
import com.example.kb.domain.vo.ChunkDto;
import com.example.kb.domain.vo.ChunkPage;
import com.example.kb.domain.vo.DocumentDto;
import com.example.kb.mapper.KbChunkMapper;
import com.example.kb.mapper.KbDocumentMapper;
import com.example.kb.service.IDocumentService;
import com.example.kb.service.IIndexingService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DocumentServiceImpl implements IDocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final IIndexingService indexingService;

    public DocumentServiceImpl(KbDocumentMapper documentMapper,
                           KbChunkMapper chunkMapper,
                           IIndexingService indexingService) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.indexingService = indexingService;
    }

    /**
     * 列表。返回 DTO 而不是实体：实体上的 contentHash 属于内部字段，
     * 不该出现在接口响应里（见 {@link DocumentDto} 的注释）。
     */
    public List<DocumentDto> listAll() {
        return documentMapper.findAll().stream().map(DocumentDto::of).toList();
    }

    /** 单篇。返回 DTO，理由同上。 */
    public Optional<DocumentDto> findById(long id) {
        return documentMapper.findById(id).map(DocumentDto::of);
    }

    /**
     * 分页取 chunk（详情页翻页用）。
     *
     * <p>⚠️ 原来 {@link #chunksOf(long)} 一次返回全部 chunk。一篇 300 页的 PDF
     * 切出 800 块，每块五百字，一次吐出去是 400KB 的 JSON —— 2C2G 服务器
     * 上没必要为一个翻页视图扛这个。分页按需取。</p>
     *
     * @return {@link ChunkPage}，字段名是编译期契约，与前端 DocumentDetailView 对齐
     */
    public ChunkPage chunksPage(long id, int page, int size) {
        int safeSize = Math.min(Math.max(1, size), 200);
        int safePage = Math.max(1, page);
        int offset = (safePage - 1) * safeSize;
        List<ChunkDto> items = chunkMapper.findByDocumentId(id, safeSize, offset)
                .stream().map(ChunkDto::of).toList();
        long total = chunkMapper.countByDocumentId(id);
        return new ChunkPage(items, total, safePage, safeSize);
    }

    /**
     * 上传 + 触发异步索引。
     *
     * @return docId（前端拿它轮询状态）
     */
    public long upload(String originalFilename, String fileType, byte[] content, Path uploadDir) throws IOException {
        String hash = IIndexingService.sha256(content);

        Optional<KbDocument> existing = documentMapper.findByContentHash(hash);
        if (existing.isPresent()) {
            KbDocument d = existing.get();
            // ⚠️ 失败/卡住的文档不能直接复用：原逻辑只要 contentHash 相同就返回旧 docId，
            //    而旧文档已经是 FAILED，前端拿到的还是一条失败记录 —— 用户唯一的自救手段
            //    「重新上传同一个文件」被这条查重挡死了。所以对未就绪的状态走重试：
            //    删掉旧记录（连同旧切块），当成新上传重新走一遍。
            boolean retryable = "FAILED".equals(d.getStatus()) || "PENDING".equals(d.getStatus());
            if (retryable) {
                log.info("重复上传命中未就绪文档，重试索引 docId={} status={}", d.getId(), d.getStatus());
                indexingService.deleteVectorsOf(d.getId());
                chunkMapper.deleteByDocumentId(d.getId());
                documentMapper.deleteById(d.getId());
            }
            else {
                log.info("重复上传，复用已有文档 docId={} status={}", d.getId(), d.getStatus());
                return d.getId();
            }
        }

        Files.createDirectories(uploadDir);
        // 文件名带 UUID 前缀：防中文/空格文件名在某些文件系统上的问题，也防同名覆盖
        Path stored = uploadDir.resolve(UUID.randomUUID() + "-" + sanitize(originalFilename));
        Files.write(stored, content);

        KbDocument doc = new KbDocument();
        doc.setFilename(originalFilename);
        doc.setContentHash(hash);
        doc.setFileType(fileType);
        doc.setFileSize((long) content.length);
        doc.setStatus("PENDING");
        // ⚠️ insert() 返回的是受影响行数（int），不是自增 id；id 由 MP 回填到实体上
        documentMapper.insert(doc);
        long docId = doc.getId();

        indexingService.indexAsync(docId, stored, fileType);
        log.info("已接收上传 docId={} file={}", docId, originalFilename);
        return docId;
    }

    /**
     * 删除文档：先删向量（vector_store 无外键，不删就成孤儿），再删行
     * （kb_chunk 有 ON DELETE CASCADE 跟着没）。
     */
    public void delete(long id) {
        Optional<KbDocument> doc = documentMapper.findById(id);
        if (doc.isEmpty()) {
            throw new IllegalArgumentException("文档不存在: " + id);
        }
        indexingService.deleteVectorsOf(id);
        documentMapper.deleteById(id);
        log.info("已删除文档 docId={}", id);
    }

    /**
     * 重新索引（同步）：文件在上传时已落 uploadDir，但 IIndexingService 索引成功后会删掉它。
     * 所以这里要求文件还在 —— 第 1 周的重索引语义是「对 READY 文档重跑」，
     * 文件已不在时返回 409 让前端提示重新上传。
     */
    public void reindex(long id, Path uploadDir) {
        Optional<KbDocument> doc = documentMapper.findById(id);
        if (doc.isEmpty()) {
            throw new IllegalArgumentException("文档不存在: " + id);
        }
        Path stored = findStoredFile(uploadDir, doc.get());
        if (stored == null) {
            throw new IllegalStateException("原文件已被清理，无法重索引，请重新上传");
        }
        try {
            indexingService.reindex(id, stored, doc.get().getFileType());
        }
        catch (IOException e) {
            throw new IllegalStateException("重索引失败: " + e.getMessage(), e);
        }
    }

    private Path findStoredFile(Path uploadDir, KbDocument doc) {
        if (!Files.isDirectory(uploadDir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(uploadDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("-" + sanitize(doc.getFilename())))
                    .findFirst()
                    .orElse(null);
        }
        catch (IOException e) {
            log.warn("扫描上传目录失败: {}", uploadDir, e);
            return null;
        }
    }

    private static String sanitize(String filename) {
        if (filename == null) {
            return "unnamed";
        }
        // 只留安全字符，路径分隔符和 .. 一律换掉
        return filename.replaceAll("[^a-zA-Z0-9.\\u4e00-\\u9fff_-]", "_");
    }
}
