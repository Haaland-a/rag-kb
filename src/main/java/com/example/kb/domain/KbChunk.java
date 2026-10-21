package com.example.kb.domain;

/**
 * kb_chunk 行 = 结构化元数据（引用溯源的数据源）。
 *
 * <p>⚠️ filename 这个字段在 {@link #vectorId} 落库前由调用方从 KbDocument 拷贝进来，
 * 因为 {@code VectorStoreGateway.toDocument()} 要把 filename/pageNumber/headingPath
 * 一起塞进 PgVectorStore 的 metadata，这样 SSE 的 sources 事件可以直接渲染，
 * 不用回查 kb_chunk 表。落库后 filename 在 kb_chunk 里没有对应列，仅作内存载体；
 * pageNumber / headingPath 是落库列。</p>
 */
public class KbChunk {

    private Long id;
    private Long documentId;
    private int chunkIndex;
    /** 内存载体：来自 KbDocument.filename，不落 kb_chunk 表 */
    private String filename;
    /** 内存载体 + 落库列 */
    private Integer pageNumber;
    /** 内存载体 + 落库列 */
    private String headingPath;
    private String content;
    /** 本地估算值（content.length() × 0.6），非真实 token 数 */
    private int tokenCount;
    private String contentHash;
    /** 关联 vector_store.id（UUID 字符串），由 VectorStoreGateway.toDocument() 生成 */
    private String vectorId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(Integer pageNumber) {
        this.pageNumber = pageNumber;
    }

    public String getHeadingPath() {
        return headingPath;
    }

    public void setHeadingPath(String headingPath) {
        this.headingPath = headingPath;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(int tokenCount) {
        this.tokenCount = tokenCount;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getVectorId() {
        return vectorId;
    }

    public void setVectorId(String vectorId) {
        this.vectorId = vectorId;
    }
}
