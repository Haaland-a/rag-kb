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

    /** 降级检索时若调用方没给 topK，用这个值（和 rag.retrieval.top-k 的默认值一致） */
    private static final int FALLBACK_TOP_K = 5;

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
        return new Retrieved(sources, hits, false);
    }

    /**
     * 同上，但允许传一个更低的相似度阈值。
     *
     * <p>⚠️ 两个阈值都不命中时返回空列表，<b>不会</b>兜底返回「最像的那一条」。
     * 空列表 → prompt 里没有资料 → 模型回答「资料中未提及」。
     * 硬塞几条不相关的段落反而更糟：模型会一本正经地拿不相干内容回答，
     * 人看了比「不知道」更难受。</p>
     *
     * <p>这个方法只为「概括性问题」存在：像「这里都讲了些什么？」这种问题，
     * 语义上和任何一段具体资料都不接近（实测最相近的一段余弦相似度只有
     * 0.2271），正常阈值 0.35 一定过不了。降级到 0.15 能召回几条，模型就能
     * 总结出「这个站里有哪些资料」。</p>
     *
     * @param threshold 覆盖配置阈值；必须落在 (0, 1]，越界由 Gateway 夹逼
     */
    public Retrieved retrieve(String query, List<Long> documentIds, Integer topK, Double threshold) {
        int limit = topK == null ? FALLBACK_TOP_K : topK;
        List<Document> hits = vectorStoreGateway.searchWithThreshold(
                query, documentIds, limit, threshold);
        List<SourceRef> sources = citationResolver.resolve(hits, loadChunkIndex(hits));
        return new Retrieved(sources, hits, true);
    }

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
