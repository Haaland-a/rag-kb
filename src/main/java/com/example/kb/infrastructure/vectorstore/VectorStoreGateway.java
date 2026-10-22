package com.example.kb.infrastructure.vectorstore;

import com.example.kb.domain.po.KbChunk;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PgVectorStore 的封装层（第 1 周的关键抽象）。
 *
 * <p>为什么要有这一层，而不是到处调 {@link VectorStore}：</p>
 * <ul>
 *   <li><b>metadata key 约定集中</b>：PgVectorStore 的 metadata 是 jsonb，
 *       {@code documentId} / {@code chunkId} 这两个关联键一旦散落在各业务类里，
 *       拼错一个字母就静默查不到数据（json path 过滤失败不报错，只返回空）。</li>
 *   <li><b>UUID 约束集中</b>：PgIdType.UUID 下 {@code doAdd} 走
 *       {@code UUID.fromString(document.getId())}，id 不是合法 UUID 字符串启动即炸。
 *       统一在这里生成，业务层不用关心。</li>
 *   <li><b>第 2 周换实现只改一个文件</b>：混合检索（向量 + pg_trgm + RRF）要自己写 SQL，
 *       或者迁到「完全自建 kb_chunk 表」，调用方都不动。</li>
 * </ul>
 */
@Component
public class VectorStoreGateway {

    /** 关联键：结构化 kb_chunk.id */
    public static final String META_CHUNK_ID = "chunkId";
    /** 关联键：kb_document.id */
    public static final String META_DOCUMENT_ID = "documentId";
    /** 免回查冗余：SSE sources 事件直接渲染用 */
    public static final String META_FILENAME = "filename";
    public static final String META_PAGE_NUMBER = "pageNumber";
    public static final String META_HEADING_PATH = "headingPath";

    private final VectorStore vectorStore;
    private final String tableName;
    private final int topK;
    private final double similarityThreshold;

    public VectorStoreGateway(
            VectorStore vectorStore,
            @Value("${rag.vector-store.table-name}") String tableName,
            @Value("${rag.retrieval.top-k}") int topK,
            @Value("${rag.retrieval.similarity-threshold}") double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.tableName = tableName;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * 写入一批 chunk。
     *
     * <p>注意：PgVectorStore 内部会调 embeddingModel.embed()，也就是说
     * embedding 是这里发生的，调用方不需要自己 embed。</p>
     *
     * @param chunks 已切好的块，必须已设置 documentId
     */
    public void add(List<KbChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<Document> documents = chunks.stream()
                .map(this::toDocument)
                .toList();
        vectorStore.add(documents);
    }

    /**
     * 按 documentId 删除该文档的全部向量。
     *
     * <p>用 {@link FilterExpressionBuilder#eq} 而不是裸字符串，避免手拼 json path 出错
     * （json path 过滤失败不报错，只静默返回空，极难排查）。</p>
     */
    public void deleteByDocumentId(long documentId) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        vectorStore.delete(builder.eq(META_DOCUMENT_ID, documentId).build());
    }

    /** 按 vectorId 精确删单个向量（重新索引单块时用） */
    public void deleteByVectorId(String vectorId) {
        vectorStore.delete(List.of(vectorId));
    }

    /**
     * 向量检索，可按 documentIds 过滤。
     *
     * @param documentIds 为空 = 全库检索；非空 = 只在这些文档里找
     * @return Document 列表，metadata 里的 META_* key 由 toDocument 保证存在
     */
    public List<Document> search(String query, List<Long> documentIds) {
        return search(query, documentIds, topK);
    }

    /**
     * 同上，但覆盖 topK（前端可以按需调整召回数）。
     *
     * <p>⚠️ topK 只影响召回，不相似度阈值；阈值仍走配置，避免前端把阈值调到 0
     * 导致一次召回几千条垃圾结果。</p>
     */
    public List<Document> search(String query, List<Long> documentIds, int topK) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold);
        if (documentIds != null && !documentIds.isEmpty()) {
            FilterExpressionBuilder feb = new FilterExpressionBuilder();
            builder.filterExpression(feb.in(META_DOCUMENT_ID, documentIds.toArray()).build());
        }
        return vectorStore.similaritySearch(builder.build());
    }

    /**
     * 同上，但显式指定相似度阈值（覆盖配置值）。
     *
     * <p>⚠️ 只在「没命中时降级重试」这一个场景用，所以它只接受一个
     * 固定的小阈值，不对外暴露给前端。前端的 topK 可以调，阈值不行 ——
     * 阈值调成 0 等于把整张向量表捞出来塞进 prompt。</p>
     *
     * <p>为什么需要降级：像「这里都讲了些什么？」这种概括性问题，
     * 语义上和任何一段具体资料都不接近，实测余弦相似度只有 0.22~0.23，
     * 而正常问题的阈值是 0.35。于是召回为空 → prompt 里没有任何资料
     * → 模型只能老实回答「资料中未提及」。问题不在模型，在召回。</p>
     *
     * @param threshold 覆盖配置的相似度阈值，内部夹逼到 (0, 1]
     */
    public List<Document> searchWithThreshold(String query, List<Long> documentIds,
                                              int topK, double threshold) {
        double safe = Math.max(0.01, Math.min(threshold, 1.0));
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(safe);
        if (documentIds != null && !documentIds.isEmpty()) {
            FilterExpressionBuilder feb = new FilterExpressionBuilder();
            builder.filterExpression(feb.in(META_DOCUMENT_ID, documentIds.toArray()).build());
        }
        return vectorStore.similaritySearch(builder.build());
    }

    /** 配置里的相似度阈值，给降级逻辑判断「值不值得放宽」用 */
    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    /**
     * KbChunk → Spring AI Document，并把 metadata key 约定落到这一处。
     *
     * <p>id 必须是合法 UUID 字符串（PgVectorStore 的 UUID.fromString 会解析它）。
     * 用 UUID.randomUUID() 生成后回填 chunk.vectorId，业务侧落库时一起存，
     * 这样 kb_chunk.vector_id ↔ vector_store.id 双向可查。</p>
     */
    public Document toDocument(KbChunk chunk) {
        String vectorId = UUID.randomUUID().toString();
        chunk.setVectorId(vectorId);

        // ⚠️ 不能直接 put：Spring AI 的 Document 构造器里是
        //    Assert.noNullElements(metadata.values(), "metadata cannot have null values")，
        //    只要有一个 value 是 null 就直接抛 IllegalArgumentException，整个文档索引失败。
        //
        //    而 null 是常态而非异常：PDF 走 PagePdfDocumentReader 拿不到标题层级，
        //    headingPath 恒为 null；TXT 根本没有 heading_path；pageNumber 在 reader
        //    没给页码时也是 null。所以这里必须「有值才放」。
        //
        //    少放字段不影响溯源：ICitationResolver 发现 metadata 缺字段时会回查
        //    kb_chunk 补上（见 ICitationResolver.toSourceRef 的兜底分支）。
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, META_CHUNK_ID, chunk.getId());
        putIfNotNull(metadata, META_DOCUMENT_ID, chunk.getDocumentId());
        putIfNotNull(metadata, META_FILENAME, chunk.getFilename());
        putIfNotNull(metadata, META_PAGE_NUMBER, chunk.getPageNumber());
        putIfNotNull(metadata, META_HEADING_PATH, chunk.getHeadingPath());

        return Document.builder()
                .id(vectorId)
                .text(chunk.getContent())
                .metadata(metadata)
                .build();
    }

    public String getTableName() {
        return tableName;
    }

    /** 只放有值的 key —— Document 的 metadata 不允许 null value */
    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
