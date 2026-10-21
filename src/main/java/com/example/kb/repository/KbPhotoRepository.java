package com.example.kb.repository;

import com.example.kb.domain.KbPhoto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * kb_photo 表访问。
 *
 * <p>⚠️ 所有查询都分「站主视角」和「访客视角」：
 * 站主能看到 {@code visible=false} 的（后台列表要能管理隐藏项），
 * 访客只能看到 {@code visible=true} 的。这个区分必须在 SQL 里做，
 * 不能查出来再在 Java 里 filter —— 否则忘一次就把隐藏照片漏给访客了。</p>
 */
@Repository
public class KbPhotoRepository {

    private static final String COLUMNS =
            "id, url, caption, sort_order, visible, width, height, file_size, created_at";

    private final JdbcTemplate jdbc;

    public KbPhotoRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 插入并回填自增 id */
    public long insert(KbPhoto photo) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kb_photo (url, caption, sort_order, visible, width, height, file_size) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, photo.getUrl());
            ps.setString(2, photo.getCaption());
            ps.setInt(3, photo.getSortOrder());
            ps.setBoolean(4, photo.isVisible());
            setNullableInt(ps, 5, photo.getWidth());
            setNullableInt(ps, 6, photo.getHeight());
            if (photo.getFileSize() == null) {
                ps.setNull(7, java.sql.Types.BIGINT);
            }
            else {
                ps.setLong(7, photo.getFileSize());
            }
            return ps;
        }, holder);
        return holder.getKey().longValue();
    }

    /**
     * 站主视角：全部照片（含隐藏），按排序 + id 倒序。
     *
     * @param limit 上限，0 表示不限
     */
    public List<KbPhoto> findAll(int limit) {
        String sql = "SELECT " + COLUMNS + " FROM kb_photo ORDER BY sort_order, id DESC";
        if (limit > 0) {
            return jdbc.query(sql + " LIMIT ?", this::mapRow, limit);
        }
        return jdbc.query(sql, this::mapRow);
    }

    /** 访客视角：只返回可见的，主页用 */
    public List<KbPhoto> findVisible(int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_photo WHERE visible = true "
                        + "ORDER BY sort_order, id DESC LIMIT ?",
                this::mapRow, limit);
    }

    public Optional<KbPhoto> findById(long id) {
        List<KbPhoto> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_photo WHERE id = ?", this::mapRow, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 改说明 / 排序 / 可见性。为 null 的字段不改（前端只送改动的部分） */
    public void updateMeta(long id, String caption, Integer sortOrder, Boolean visible) {
        jdbc.update(
                "UPDATE kb_photo SET "
                        + "caption = COALESCE(?, caption), "
                        + "sort_order = COALESCE(?, sort_order), "
                        + "visible = COALESCE(?, visible) "
                        + "WHERE id = ?",
                caption, sortOrder, visible, id);
    }

    public int deleteById(long id) {
        return jdbc.update("DELETE FROM kb_photo WHERE id = ?", id);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM kb_photo", Long.class);
        return n == null ? 0 : n;
    }

    private void setNullableInt(PreparedStatement ps, int idx, Integer v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.INTEGER);
        }
        else {
            ps.setInt(idx, v);
        }
    }

    private KbPhoto mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        KbPhoto p = new KbPhoto();
        p.setId(rs.getLong("id"));
        p.setUrl(rs.getString("url"));
        p.setCaption(rs.getString("caption"));
        p.setSortOrder(rs.getInt("sort_order"));
        p.setVisible(rs.getBoolean("visible"));
        int w = rs.getInt("width");
        p.setWidth(rs.wasNull() ? null : w);
        int h = rs.getInt("height");
        p.setHeight(rs.wasNull() ? null : h);
        long size = rs.getLong("file_size");
        p.setFileSize(rs.wasNull() ? null : size);
        Timestamp ts = rs.getTimestamp("created_at");
        p.setCreatedAt(ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC));
        return p;
    }
}
