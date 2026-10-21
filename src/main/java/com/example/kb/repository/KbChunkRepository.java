package com.example.kb.repository;

import com.example.kb.domain.KbChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * kb_chunk 表访问。
 *
 * <p>批量插入用 {@code JdbcTemplate.batchUpdate}（一次一条 SQL 多组参数），
 * 100 个 chunk 一次 round-trip，而不是 100 次。</p>
 */
@Repository
public class KbChunkRepository {

    private final JdbcTemplate jdbc;
    private final int batchSize;

    public KbChunkRepository(DataSource dataSource,
                             @Value("${rag.vector-store.max-document-batch-size}") int batchSize) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.batchSize = batchSize;
    }

    /** 批量插入。
     *
     * <p>⚠️ Spring 的 batchUpdate 不返回自增 id，而 VectorStoreGateway.toDocument()
     * 需要 chunk.id 塞进 metadata 的 chunkId。所以这里不能只 batchUpdate，
     * 必须在 insert 前就拿到 id。</p>
     *
     * <p>做法：改用单条 INSERT ... 逐条执行（JDBC 驱动对 PostgreSQL 支持
     * {@code RETURNING id}，但 Spring JdbcTemplate 的 update 不暴露它），
     * 于是用最直白的方式：先按 batchSize 分段 batchUpdate 插入，
     * 段末按 (document_id, chunk_index) 查回这批的 id 并回填。
     * 第 1 周单文档几十个 chunk，这个折中完全够用。</p>
     */
    public void insertAll(List<KbChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO kb_chunk (document_id, chunk_index, page_number, heading_path, "
                + "content, token_count, content_hash) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id";
        for (KbChunk chunk : chunks) {
            List<Long> ids = jdbc.query(con -> {
                        PreparedStatement ps = con.prepareStatement(sql);
                        ps.setLong(1, chunk.getDocumentId());
                        ps.setInt(2, chunk.getChunkIndex());
                        if (chunk.getPageNumber() == null) {
                            ps.setNull(3, Types.INTEGER);
                        }
                        else {
                            ps.setInt(3, chunk.getPageNumber());
                        }
                        ps.setString(4, chunk.getHeadingPath());
                        ps.setString(5, chunk.getContent());
                        ps.setInt(6, chunk.getTokenCount());
                        ps.setString(7, chunk.getContentHash());
                        return ps;
                    },
                    (rs, rowNum) -> rs.getLong("id"));
            if (ids.isEmpty()) {
                throw new IllegalStateException("chunk 插入失败，未返回自增 id");
            }
            chunk.setId(ids.get(0));
        }
    }

    public List<KbChunk> findByDocumentId(long documentId) {
        return jdbc.query(
                "SELECT id, document_id, chunk_index, page_number, heading_path, content, "
                        + "token_count, content_hash, vector_id FROM kb_chunk "
                        + "WHERE document_id = ? ORDER BY chunk_index",
                this::mapRow, documentId);
    }

    /** 分页查某文档的 chunk（详情页翻页用） */
    public List<KbChunk> findByDocumentId(long documentId, int limit, int offset) {
        return jdbc.query(
                "SELECT id, document_id, chunk_index, page_number, heading_path, content, "
                        + "token_count, content_hash, vector_id FROM kb_chunk "
                        + "WHERE document_id = ? ORDER BY chunk_index LIMIT ? OFFSET ?",
                this::mapRow, documentId, limit, offset);
    }

    public List<KbChunk> findByVectorIds(List<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(vectorIds.size(), "?"));
        return jdbc.query(
                "SELECT id, document_id, chunk_index, page_number, heading_path, content, "
                        + "token_count, content_hash, vector_id FROM kb_chunk "
                        + "WHERE vector_id IN (" + placeholders + ")",
                this::mapRow, vectorIds.toArray());
    }

    /** 按主键批量查（CitationResolver 兜底回查用） */
    public List<KbChunk> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbc.query(
                "SELECT id, document_id, chunk_index, page_number, heading_path, content, "
                        + "token_count, content_hash, vector_id FROM kb_chunk "
                        + "WHERE id IN (" + placeholders + ")",
                this::mapRow, ids.toArray());
    }

    /** 回填 vector_store.id，双轨关联的另一半 */
    public void updateVectorId(long chunkId, String vectorId) {
        jdbc.update("UPDATE kb_chunk SET vector_id = ? WHERE id = ?", vectorId, chunkId);
    }

    public void deleteByDocumentId(long documentId) {
        jdbc.update("DELETE FROM kb_chunk WHERE document_id = ?", documentId);
    }

    public int countByDocumentId(long documentId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM kb_chunk WHERE document_id = ?",
                Integer.class, documentId);
        return n == null ? 0 : n;
    }

    /**
     * 关键词检索（第 2 周 RRF 的一路，第 1 周先建好不用）。
     * pg_trgm 的 gin 索引对中文子串匹配有效，相似度阈值用 pg_trgm.similarity。
     */
    public List<KbChunk> keywordSearch(String keyword, int limit, double threshold) {
        return jdbc.query(
                "SELECT id, document_id, chunk_index, page_number, heading_path, content, "
                        + "token_count, content_hash, vector_id FROM kb_chunk "
                        + "WHERE content % ? AND similarity(content, ?) >= ? "
                        + "ORDER BY similarity(content, ?) DESC LIMIT ?",
                this::mapRow, keyword, keyword, threshold, keyword, limit);
    }

    private KbChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
        KbChunk c = new KbChunk();
        c.setId(rs.getLong("id"));
        c.setDocumentId(rs.getLong("document_id"));
        c.setChunkIndex(rs.getInt("chunk_index"));
        int page = rs.getInt("page_number");
        c.setPageNumber(rs.wasNull() ? null : page);
        c.setHeadingPath(rs.getString("heading_path"));
        c.setContent(rs.getString("content"));
        c.setTokenCount(rs.getInt("token_count"));
        c.setContentHash(rs.getString("content_hash"));
        c.setVectorId(rs.getString("vector_id"));
        return c;
    }
}
