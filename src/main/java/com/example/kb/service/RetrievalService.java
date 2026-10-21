package com.example.kb.service;

import com.example.kb.dto.chat.SourceRef;
import com.example.kb.domain.KbChunk;
import com.example.kb.repository.KbChunkRepository;
import com.example.kb.infrastructure.vectorstore.VectorStoreGateway;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索：query → 命中的 chunk（带引用信息）。
 *
 * <p>第 1 周只做向量一路（md 里的混合检索 + RRF 是第 2 周）。
 * 关键词那一路的 SQL 已经建好索引并写在 {@code KbChunkRepository#keywordSearch}，
 * 接进来时只改这个类，Controller / Service 不用动。</p>
 *
 * <p>设计要点：一次检索同时产出「给前端的引用列表」和「给 prompt 的原文」，
 * 两者必须来自同一次相似度搜索 —— 分两次搜，文档在两次之间被删/改就会出现
 * 引用编号和 prompt 内容对不上的 bug。</p>
 */
@Service
public class RetrievalService {

    private final VectorStoreGateway vectorStoreGateway;
    private final KbChunkRepository chunkRepository;
    private final CitationResolver citationResolver;

    public RetrievalService(VectorStoreGateway vectorStoreGateway,
                            KbChunkRepository chunkRepository,
                            CitationResolver citationResolver) {
        this.vectorStoreGateway = vectorStoreGateway;
        this.chunkRepository = chunkRepository;
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
    public record Retrieved(List<SourceRef> sources, List<Document> context) {
    }

    /** 按 chunkId 建索引，CitationResolver 兜底回查用 */
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
        for (KbChunk c : chunkRepository.findByIds(chunkIds)) {
            index.put(c.getId(), c);
        }
        return index;
    }
}
