package com.example.kb.service.impl;

import com.example.kb.domain.po.KbChunk;
import com.example.kb.domain.vo.SourceRef;
import com.example.kb.infrastructure.vectorstore.VectorStoreGateway;
import com.example.kb.mapper.KbChunkMapper;
import com.example.kb.service.ICitationResolver;
import com.example.kb.service.IRetrievalService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

@Service
public class RetrievalServiceImpl implements IRetrievalService {

    private final VectorStoreGateway vectorStoreGateway;
    private final KbChunkMapper chunkMapper;
    private final ICitationResolver citationResolver;

    public RetrievalServiceImpl(VectorStoreGateway vectorStoreGateway,
                            KbChunkMapper chunkMapper,
                            ICitationResolver citationResolver) {
        this.vectorStoreGateway = vectorStoreGateway;
        this.chunkMapper = chunkMapper;
        this.citationResolver = citationResolver;
    }

    /**
     * 检索一次，同时产出引用列表和 prompt 原文。
     *
     * <p>为什么要捆绑返回而不是分两次调 {@code retrieve} / {@code retrieveForPrompt}：
     * 两次搜索之间文档可能被删除，前端渲染的 sources 数组和 LLM 看到的上下文
     * 就对不上了（引用编号指向空内容）。一次检索是唯一正确做法。</p>
     *
     * @param documentIds 为空 = 全库；非空 = 只在这些文档里检索
     * @param topK        null = 用配置默认值
     */
    public Retrieved retrieve(String query, List<Long> documentIds, Integer topK) {
        List<Document> hits = topK == null
                ? vectorStoreGateway.search(query, documentIds)
                : vectorStoreGateway.search(query, documentIds, topK);
        List<SourceRef> sources = citationResolver.resolve(hits, loadChunkIndex(hits));
        return new Retrieved(sources, hits);
    }

    /**
     * 一次检索的产物：{@code sources} 给前端渲染引用卡片，
     * {@code context} 给 LLM 组 prompt，两者下标一一对应。
     */

    /** 按 chunkId 建索引，ICitationResolver 兜底回查用 */
    private Map<Long, KbChunk> loadChunkIndex(List<Document> hits) {
        List<Long> chunkIds = hits.stream()
                .map(d -> d.getMetadata().get(VectorStoreGateway.META_CHUNK_ID))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::longValue)
                .distinct()
                .toList();
        if (chunkIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, KbChunk> index = new HashMap<>();
        for (KbChunk c : chunkMapper.findByIds(chunkIds)) {
            index.put(c.getId(), c);
        }
        return index;
    }
}
