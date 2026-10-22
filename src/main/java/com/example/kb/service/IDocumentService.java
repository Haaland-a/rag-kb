package com.example.kb.service;

import com.example.kb.domain.po.KbChunk;
import com.example.kb.domain.po.KbDocument;
import com.example.kb.domain.vo.ChunkDto;
import com.example.kb.domain.vo.ChunkPage;
import com.example.kb.domain.vo.DocumentDto;
import com.example.kb.mapper.KbChunkMapper;
import com.example.kb.mapper.KbDocumentMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

public interface IDocumentService {

    /**
     * 列表。返回 DTO 而不是实体：实体上的 contentHash 属于内部字段，
     * 不该出现在接口响应里（见 {@link DocumentDto} 的注释）。
     */
    public List<DocumentDto> listAll();

    /** 单篇。返回 DTO，理由同上。 */
    public Optional<DocumentDto> findById(long id);

    /**
     * 分页取 chunk（详情页翻页用）。
     *
     * <p>⚠️ 原来 {@link #chunksOf(long)} 一次返回全部 chunk。一篇 300 页的 PDF
     * 切出 800 块，每块五百字，一次吐出去是 400KB 的 JSON —— 2C2G 服务器
     * 上没必要为一个翻页视图扛这个。分页按需取。</p>
     *
     * @return {@link ChunkPage}，字段名是编译期契约，与前端 DocumentDetailView 对齐
     */
    public ChunkPage chunksPage(long id, int page, int size);

    /**
     * 上传 + 触发异步索引。
     *
     * @return docId（前端拿它轮询状态）
     */
    public long upload(String originalFilename, String fileType, byte[] content, Path uploadDir) throws IOException;

    /**
     * 删除文档：先删向量（vector_store 无外键，不删就成孤儿），再删行
     * （kb_chunk 有 ON DELETE CASCADE 跟着没）。
     */
    public void delete(long id);

    /**
     * 重新索引（同步）：文件在上传时已落 uploadDir，但 IIndexingService 索引成功后会删掉它。
     * 所以这里要求文件还在 —— 第 1 周的重索引语义是「对 READY 文档重跑」，
     * 文件已不在时返回 409 让前端提示重新上传。
     */
    public void reindex(long id, Path uploadDir);

}
