package com.example.kb.repository;

import com.example.kb.domain.KbDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * kb_document 表访问。
 *
 * <p>用 JdbcTemplate 不用 JPA：见 KbDocument 类注释（2C2G 内存账）。
 * 所有 SQL 集中在此，业务类不许拼 SQL。</p>
 */
@Repository
public class KbDocumentRepository {

    private final JdbcTemplate jdbc;

    public KbDocumentRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 插入并回填自增 id */
    public long insert(KbDocument doc) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kb_document (filename, content_hash, file_type, file_size, page_count, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, doc.getFilename());
            ps.setString(2, doc.getContentHash());
            ps.setString(3, doc.getFileType());
            ps.setLong(4, doc.getFileSize());
            if (doc.getPageCount() == null) {
                ps.setNull(5, Types.INTEGER);
            }
            else {
                ps.setInt(5, doc.getPageCount());
            }
            ps.setString(6, doc.getStatus());
            return ps;
        }, holder);
        return holder.getKey().longValue();
    }

    public Optional<KbDocument> findById(long id) {
        List<KbDocument> rows = jdbc.query(
                "SELECT id, filename, content_hash, file_type, file_size, page_count, chunk_count, "
                        + "status, error_msg, created_at, updated_at FROM kb_document WHERE id = ?",
                this::mapRow, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 上传查重：整篇 SHA-256 相同直接复用已有文档 */
    public Optional<KbDocument> findByContentHash(String contentHash) {
        List<KbDocument> rows = jdbc.query(
                "SELECT id, filename, content_hash, file_type, file_size, page_count, chunk_count, "
                        + "status, error_msg, created_at, updated_at FROM kb_document WHERE content_hash = ?",
                this::mapRow, contentHash);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<KbDocument> findAll() {
        return jdbc.query(
                "SELECT id, filename, content_hash, file_type, file_size, page_count, chunk_count, "
                        + "status, error_msg, created_at, updated_at FROM kb_document ORDER BY id DESC",
                this::mapRow);
    }

    public void updateStatus(long id, String status, String errorMsg) {
        jdbc.update("UPDATE kb_document SET status = ?, error_msg = ?, updated_at = now() WHERE id = ?",
                status, errorMsg, id);
    }

    public void updatePageCount(long id, int pageCount) {
        jdbc.update("UPDATE kb_document SET page_count = ?, updated_at = now() WHERE id = ?", pageCount, id);
    }

    public void updateChunkCount(long id, int chunkCount) {
        jdbc.update("UPDATE kb_document SET chunk_count = ?, updated_at = now() WHERE id = ?", chunkCount, id);
    }

    public void deleteById(long id) {
        // kb_chunk 有 ON DELETE CASCADE，但 vector_store 里的向量没有外键，
        // 必须先由调用方（IndexingService）删向量再删文档，否则留下孤儿向量
        jdbc.update("DELETE FROM kb_document WHERE id = ?", id);
    }

    private KbDocument mapRow(ResultSet rs, int rowNum) throws SQLException {
        KbDocument d = new KbDocument();
        d.setId(rs.getLong("id"));
        d.setFilename(rs.getString("filename"));
        d.setContentHash(rs.getString("content_hash"));
        d.setFileType(rs.getString("file_type"));
        d.setFileSize(rs.getLong("file_size"));
        int pageCount = rs.getInt("page_count");
        d.setPageCount(rs.wasNull() ? null : pageCount);
        d.setChunkCount(rs.getInt("chunk_count"));
        d.setStatus(rs.getString("status"));
        d.setErrorMsg(rs.getString("error_msg"));
        d.setCreatedAt(toOffset(rs.getTimestamp("created_at")));
        d.setUpdatedAt(toOffset(rs.getTimestamp("updated_at")));
        return d;
    }

    private static OffsetDateTime toOffset(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}
