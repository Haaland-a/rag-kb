package com.example.kb.service;

import com.example.kb.config.AsyncConfig;
import com.example.kb.domain.po.KbChunk;
import com.example.kb.domain.po.KbDocument;
import com.example.kb.infrastructure.parser.Chunker;
import com.example.kb.infrastructure.parser.DocumentParser;
import com.example.kb.infrastructure.vectorstore.VectorStoreGateway;
import com.example.kb.mapper.KbChunkMapper;
import com.example.kb.mapper.KbDocumentMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

public interface IIndexingService {

    /**
     * 同步版索引（重索引接口用）。
     *
     * @param storedPath 已落地的临时文件路径（调用方负责最后删除）
     */
    @Transactional
    public void indexBlocking(long documentId, Path storedPath, String fileType) throws IOException;

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
    public void indexAsync(long documentId, Path storedPath, String fileType);

    /** 重新索引：删旧 chunk + 旧向量，再走同一套流程 */
    public void reindex(long documentId, Path storedPath, String fileType) throws IOException;

    /** 删除某文档的全部向量（文档删除时用） */
    public void deleteVectorsOf(long documentId);

    /**
     * 整篇 SHA-256，上传查重用。
     *
     * <p>⚠️ 这是静态工具方法，本来不该放在 Service 接口上。但上传链路里
     * {@code DocumentServiceImpl} 要在「同一事务里先算哈希再查重」，
     * 让接口暴露它比给每个调用方单独造一个 HashUtil 更省事。</p>
     *
     * <p>⚠️ 静态方法不会被实现类「实现」，如果实现类里也写一个同名 static，
     * 两者互不相干、各自能用，编译不报错但容易改错一边 —— 所以这里只留接口上的
     * 这一份，实现类里那份已删。</p>
     */
    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }

}
