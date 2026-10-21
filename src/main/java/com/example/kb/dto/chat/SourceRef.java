package com.example.kb.dto.chat;

/**
 * SSE 里的引用溯源条目（sources 事件）。
 *
 * <p>字段全部来自 kb_chunk 结构化列 + vector_store metadata 冗余，
 * 不需要回查数据库（这是 md 第 6.5 节「免回查冗余」决策的落地）。</p>
 *
 * <p>score = 1 - cosine distance（PgVectorStore 的 COSINE_DISTANCE SQL 返回
 * {@code embedding <=> ? AS distance}，越小越像，取 1-distance 转成「越大越像」）。</p>
 */
public class SourceRef {

    /** kb_chunk.id，前端点引用时用它调 /api/documents/{docId}/chunks 定位 */
    private Long chunkId;
    private Long documentId;
    private String filename;
    private Integer pageNumber;
    private String headingPath;
    /** 1 - cosine distance，越大越相似 */
    private Double score;
    /** 块内容预览（截断到 200 字，完整内容由详情接口给） */
    private String preview;

    public Long getChunkId() {
        return chunkId;
    }

    public void setChunkId(Long chunkId) {
        this.chunkId = chunkId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
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

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getPreview() {
        return preview;
    }

    public void setPreview(String preview) {
        this.preview = preview;
    }
}
