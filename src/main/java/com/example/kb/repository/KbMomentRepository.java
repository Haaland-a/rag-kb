package com.example.kb.repository;

import com.example.kb.domain.KbMoment;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * kb_moment 表访问。
 *
 * <p>⚠️ 站主视角（{@link #findAll}）与访客视角（{@link #findVisible}）
 * 在 SQL 层就分开，理由同 {@link KbPhotoRepository}：查出来再在 Java 里
 * filter 一遍，哪天忘了就是「隐藏动态漏到主页」。</p>
 */
@Repository
public class KbMomentRepository {

    private static final String COLUMNS =
            "id, content, media_url, media_type, media_width, media_height, media_size, "
                    + "synced_photo_id, visible, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public KbMomentRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public long insert(KbMoment m) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kb_moment (content, media_url, media_type, media_width, media_height, "
                            + "media_size, synced_photo_id, visible) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, m.getContent());
            ps.setString(2, m.getMediaUrl());
            ps.setString(3, m.getMediaType());
            setNullableInt(ps, 4, m.getMediaWidth());
            setNullableInt(ps, 5, m.getMediaHeight());
            setNullableLong(ps, 6, m.getMediaSize());
            setNullableLong(ps, 7, m.getSyncedPhotoId());
            ps.setBoolean(8, m.isVisible());
            return ps;
        }, holder);
        return holder.getKey().longValue();
    }

    /** 站主视角：全部动态，倒序 */
    public List<KbMoment> findAll(int limit, int offset) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_moment ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapRow, limit, offset);
    }

    /** 访客视角：只看可见的 */
    public List<KbMoment> findVisible(int limit, int offset) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_moment WHERE visible = true "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapRow, limit, offset);
    }

    public Optional<KbMoment> findById(long id) {
        List<KbMoment> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_moment WHERE id = ?", this::mapRow, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 改文字 / 可见性。
     *
     * <p>⚠️ 不提供「换媒体文件」的更新：要换图就是删了重发。
     * 媒体文件换了以后旧文件要删、宽高要重算、照片墙的同步关系要重建，
     * 比删了重发复杂得多，收益为零。</p>
     */
    public void updateContent(long id, String content, Boolean visible) {
        jdbc.update(
                "UPDATE kb_moment SET "
                        + "content = ?, "
                        + "visible = COALESCE(?, visible), "
                        + "updated_at = now() "
                        + "WHERE id = ?",
                content, visible, id);
    }

    /** 记录「这条动态同步到了照片墙的哪张图」 */
    public void updateSyncedPhoto(long id, Long photoId) {
        jdbc.update("UPDATE kb_moment SET synced_photo_id = ? WHERE id = ?", photoId, id);
    }

    /**
     * 反查：某张照片墙的图是从哪条动态同步过去的。
     *
     * <p>用途：站主在照片墙单独删掉那张图时，要把动态上的引用置空。
     * 返回 List 而不是 Optional 是因为理论上不该有重复，
     * 但数据库没有唯一约束，万一有历史脏数据也能全部清理掉而不是抛异常。</p>
     */
    public List<KbMoment> findBySyncedPhotoId(long photoId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_moment WHERE synced_photo_id = ?",
                this::mapRow, photoId);
    }

    public int deleteById(long id) {
        return jdbc.update("DELETE FROM kb_moment WHERE id = ?", id);
    }

    public long count(boolean visibleOnly) {
        Long n = jdbc.queryForObject(
                visibleOnly ? "SELECT count(*) FROM kb_moment WHERE visible = true"
                        : "SELECT count(*) FROM kb_moment",
                Long.class);
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

    private void setNullableLong(PreparedStatement ps, int idx, Long v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.BIGINT);
        }
        else {
            ps.setLong(idx, v);
        }
    }

    private KbMoment mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        KbMoment m = new KbMoment();
        m.setId(rs.getLong("id"));
        m.setContent(rs.getString("content"));
        m.setMediaUrl(rs.getString("media_url"));
        m.setMediaType(rs.getString("media_type"));
        int w = rs.getInt("media_width");
        m.setMediaWidth(rs.wasNull() ? null : w);
        int h = rs.getInt("media_height");
        m.setMediaHeight(rs.wasNull() ? null : h);
        long size = rs.getLong("media_size");
        m.setMediaSize(rs.wasNull() ? null : size);
        long photoId = rs.getLong("synced_photo_id");
        m.setSyncedPhotoId(rs.wasNull() ? null : photoId);
        m.setVisible(rs.getBoolean("visible"));
        Timestamp created = rs.getTimestamp("created_at");
        m.setCreatedAt(created == null ? null : created.toInstant().atOffset(ZoneOffset.UTC));
        Timestamp updated = rs.getTimestamp("updated_at");
        m.setUpdatedAt(updated == null ? null : updated.toInstant().atOffset(ZoneOffset.UTC));
        return m;
    }
}
