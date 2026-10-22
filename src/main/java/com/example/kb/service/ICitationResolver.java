package com.example.kb.service;

import com.example.kb.domain.po.KbChunk;
import com.example.kb.domain.vo.SourceRef;
import com.example.kb.infrastructure.vectorstore.VectorStoreGateway;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

public interface ICitationResolver {

    /**
     * @param documents VectorStoreGateway.search 的返回
     * @param chunkById 可选：按 chunkId 索引的 kb_chunk 行，用于兜底回查
     */
    public List<SourceRef> resolve(List<Document> documents, Map<Long, KbChunk> chunkById);

}
