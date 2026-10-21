package com.example.kb.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Spring AI Bean 装配。
 *
 * <p>关键约束（来自 spring-ai 1.1.8 sources 实测 + 2026-10-19 编译踩坑，勿凭记忆改）：</p>
 * <ul>
 *   <li>{@code OpenAiApi.builder().baseUrl(...).apiKey(...)} 指向硅基流动，
 *       embedding 用 BAAI/bge-m3，chat 用 DeepSeek-V3。</li>
 *   <li>{@code OpenAiEmbeddingModel} <b>没有 static builder()</b>，只能 new，
 *       构造器 {@code (OpenAiApi, MetadataMode, OpenAiEmbeddingOptions)}。
 *       {@code MetadataMode} 在 {@code org.springframework.ai.document} 包。</li>
 *   <li>{@link PgVectorStore#builder} 是包级可见的静态方法，参数 {@code (JdbcTemplate, EmbeddingModel)}，
 *       builder 链式方法名：schemaName / vectorTableName / dimensions / distanceType /
 *       indexType / maxDocumentBatchSize / initializeSchema。</li>
 *   <li>{@code PgDistanceType} / {@code PgIndexType} 是 <b>嵌套枚举</b>
 *       （{@code PgVectorStore.PgDistanceType}），没有顶层同名类。</li>
 *   <li>{@code afterPropertiesSet()} 会自建 vector_store 表 + HNSW 索引，列固定
 *       {@code id uuid / content text / metadata json / embedding vector(dim)}，
 *       所以 kb_chunk 的结构化列必须另建表，见 db/schema.sql。</li>
 * </ul>
 *
 * <p>注意：这里<b>不</b>定义 ChatModel / ChatClient bean —— spring-ai-starter-model-openai
 * 的自动配置（OpenAiChatAutoConfiguration）已经按 spring.ai.openai.* 配好了，
 * 再定义同名 bean 会覆盖导致启动报错。业务层直接注入 {@code ChatClient.Builder}。</p>
 *
 * <p>⚠️ 这里的 OpenAiApi bean 会覆盖自动配置的那个，所以 chat 与 embedding
 * 共用同一个 baseUrl + apiKey（硅基流动两者都提供，没问题）。</p>
 */
@Configuration
public class AiConfig {

    /**
     * OpenAI 兼容 API 客户端（硅基流动）。
     */
    @Bean
    public OpenAiApi openAiApi(
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey) {
        return OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
    }

    /**
     * Embedding 模型：BAAI/bge-m3，1024 维。
     *
     * <p>⚠️ dimensions 必须与 {@link #pgVectorStore} 的 .dimensions() 一致，
     * 也必须与数据库里已建索引的 vector(dim) 一致，三者错一个 insert 就炸。</p>
     */
    @Bean
    public OpenAiEmbeddingModel embeddingModel(
            OpenAiApi openAiApi,
            @Value("${spring.ai.openai.embedding.options.model}") String embeddingModel,
            @Value("${spring.ai.openai.embedding.options.dimensions}") int dimensions) {
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(embeddingModel)
                .dimensions(dimensions)
                .build();
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, options);
    }

    /**
     * pgvector 向量库。
     *
     * <p>initializeSchema(true)：启动时自动建 vector_store 表 + HNSW 索引。
     * 换模型必须同时改 dimensions 并删表重建。</p>
     *
     * <p>distanceType / indexType 写死而不用 @Value：可选值只有三个（EUCLIDEAN /
     * NEGATIVE_INNER_PRODUCT / COSINE）和两个（HNSW / IVFFLAT），写死比留配置项
     * 更防误配 —— 改这两个必须同时改 schema.sql 和已建索引，属于代码级变更。</p>
     */
    @Bean
    public PgVectorStore pgVectorStore(
            DataSource dataSource,
            OpenAiEmbeddingModel embeddingModel,
            @Value("${rag.vector-store.schema-name}") String schemaName,
            @Value("${rag.vector-store.table-name}") String tableName,
            @Value("${spring.ai.openai.embedding.options.dimensions}") int dimensions,
            @Value("${rag.vector-store.max-document-batch-size}") int maxDocumentBatchSize) {
        return PgVectorStore.builder(new JdbcTemplate(dataSource), embeddingModel)
                .schemaName(schemaName)
                .vectorTableName(tableName)
                // 手动指定，避免调 embeddingModel.dimensions() 白跑一次真实 embedding 请求
                .dimensions(dimensions)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .maxDocumentBatchSize(maxDocumentBatchSize)
                .initializeSchema(true)
                .build();
    }
}
