package com.example.kb.service;

import com.example.kb.domain.po.KbChunk;
import com.example.kb.domain.vo.SourceRef;
import com.example.kb.infrastructure.vectorstore.VectorStoreGateway;
import com.example.kb.mapper.KbChunkMapper;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

public interface IRetrievalService {

    /**
     * 一次检索的产物：{@code sources} 给前端渲染引用卡片，
     * {@code context} 给 LLM 组 prompt，两者下标一一对应。
     */
    /**
     * 一次检索的产物：{@code sources} 给前端渲染引用卡片，
     * {@code context} 给 LLM 组 prompt，两者下标一一对应。
     *
     * @param degraded 是否走了降级路径（主阈值没命中，放宽到 fallback 阈值又查了一次）。
     *                 前端可以用它做一点提示，比如「没找到很贴切的段落，下面是从最相关的内容里总结的」。
     */
    public record Retrieved(List<SourceRef> sources, List<Document> context, boolean degraded) {
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
    public Retrieved retrieve(String query, List<Long> documentIds, Integer topK);

    /**
     * 同上，但允许调用方传一个更低的阈值（{@code null} = 用配置值）。
     *
     * <p>⚠️ 阈值只降低不放宽到 0：{@code ChatServiceImpl} 在「兜底问题」
     * 和「本轮没召回」时各用一次，都是传固定的 0.15。
     */
    public Retrieved retrieve(String query, List<Long> documentIds, Integer topK, Double threshold);

}
