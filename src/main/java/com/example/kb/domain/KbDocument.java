package com.example.kb.domain;

import java.time.OffsetDateTime;

/**
 * kb_document 行。
 *
 * <p>不用 JPA 用 JDBC：2C2G 上少一个 Hibernate 的运行时开销，且本项目的 SQL 很直白
 * （ insert + 按 id 查 + 按 status 查），JPA 的收益抵不过它的内存和启动成本。</p>
 */
public class KbDocument {

    private Long id;
    private String filename;
    /** 整篇 SHA-256，重复上传直接返回已有 docId */
    private String contentHash;
    /** pdf / md / txt */
    private String fileType;
    private Long fileSize;
    private Integer pageCount;
    private int chunkCount;
    /** PENDING / PARSING / INDEXING / READY / FAILED */
    private String status;
    private String errorMsg;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
