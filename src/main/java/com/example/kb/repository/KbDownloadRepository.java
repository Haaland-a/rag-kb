package com.example.kb.repository;

import com.example.kb.domain.KbDownload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * kb_download 表访问：简历 / 作品集下载项。
 */
@Repository
public class KbDownloadRepository {

    private static final String COLUMNS =
            "id, title, url, file_type, file_size, download_count, sort_order, visible, created_at";

    private final JdbcTemplate jdbc;

    public KbDownloadRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public long insert(KbDownload d) {
        org.springframework.jdbc.support.KeyHolder holder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kb_download (title, url, file_type, file_size, sort_order, visible) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, d.getTitle());
            ps.setString(2, d.getUrl());
            ps.setString(3, d.getFileType());
            if (d.getFileSize() == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            }
            else {
                ps.setLong(4, d.getFileSize());
            }
            ps.setInt(5, d.getSortOrder());
            ps.setBoolean(6, d.isVisible());
            return ps;
        }, holder);
        return holder.getKey().longValue();
    }

    /** 站主视角：全部（含隐藏） */
    public List<KbDownload> findAll() {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_download ORDER BY sort_order, id DESC",
                this::mapRow);
    }

    /** 访客视角：只返回可见的 */
    public List<KbDownload> findVisible() {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_download WHERE visible = true "
                        + "ORDER BY sort_order, id DESC",
                this::mapRow);
    }

    public Optional<KbDownload> findById(long id) {
        List<KbDownload> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_download WHERE id = ?", this::mapRow, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 改标题 / 排序 / 可见性。null 表示不改 */
    public void updateMeta(long id, String title, Integer sortOrder, Boolean visible) {
        jdbc.update(
                "UPDATE kb_download SET "
                        + "title = COALESCE(?, title), "
                        + "sort_order = COALESCE(?, sort_order), "
                        + "visible = COALESCE(?, visible) "
                        + "WHERE id = ?",
                title, sortOrder, visible, id);
    }

    /**
     * 下载计数 +1。
     *
     * <p>⚠️ 用 {@code download_count = download_count + 1} 而不是「先查再写」：
     * 两个访客同时点下载，先查再写会丢一次计数（读到的都是旧值）。
     * SQL 层的自增是原子的。</p>
     */
    public void incrementDownload(long id) {
        jdbc.update("UPDATE kb_download SET download_count = download_count + 1 WHERE id = ?", id);
    }

    public int deleteById(long id) {
        return jdbc.update("DELETE FROM kb_download WHERE id = ?", id);
    }

    private KbDownload mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        KbDownload d = new KbDownload();
        d.setId(rs.getLong("id"));
        d.setTitle(rs.getString("title"));
        d.setUrl(rs.getString("url"));
        d.setFileType(rs.getString("file_type"));
        long size = rs.getLong("file_size");
        d.setFileSize(rs.wasNull() ? null : size);
        d.setDownloadCount(rs.getLong("download_count"));
        d.setSortOrder(rs.getInt("sort_order"));
        d.setVisible(rs.getBoolean("visible"));
        Timestamp ts = rs.getTimestamp("created_at");
        d.setCreatedAt(ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC));
        return d;
    }
}
