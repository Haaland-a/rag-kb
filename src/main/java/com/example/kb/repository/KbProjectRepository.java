package com.example.kb.repository;

import com.example.kb.domain.KbProject;
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
 * kb_project 表访问。
 *
 * <p>⚠️ 站主视角（{@link #findAll}）与访客视角（{@link #findVisible}）在 SQL 层就分开，
 * 理由同 {@link KbPhotoRepository}。</p>
 */
@Repository
public class KbProjectRepository {

    private static final String COLUMNS =
            "id, title, description, cover_url, tech, repo_url, demo_url, "
                    + "sort_order, visible, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public KbProjectRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public long insert(KbProject p) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kb_project (title, description, cover_url, tech, repo_url, demo_url, "
                            + "sort_order, visible) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, p.getTitle());
            ps.setString(2, p.getDescription());
            ps.setString(3, p.getCoverUrl());
            ps.setString(4, p.getTech());
            ps.setString(5, p.getRepoUrl());
            ps.setString(6, p.getDemoUrl());
            ps.setInt(7, p.getSortOrder());
            ps.setBoolean(8, p.isVisible());
            return ps;
        }, holder);
        return holder.getKey().longValue();
    }

    /** 站主视角：全部项目（含隐藏） */
    public List<KbProject> findAll() {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_project ORDER BY sort_order, id DESC",
                this::mapRow);
    }

    /** 访客视角：只返回可见的 */
    public List<KbProject> findVisible() {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_project WHERE visible = true "
                        + "ORDER BY sort_order, id DESC",
                this::mapRow);
    }

    public Optional<KbProject> findById(long id) {
        List<KbProject> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_project WHERE id = ?", this::mapRow, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 整体更新。
     *
     * <p>⚠️ 用「整体覆盖」而不是 COALESCE 局部更新：项目卡片字段之间有关联
     * （比如清空封面图），局部更新无法区分「没传这个字段」和「要把它改成 null」。</p>
     */
    public void update(KbProject p) {
        jdbc.update(
                "UPDATE kb_project SET title = ?, description = ?, cover_url = ?, tech = ?, "
                        + "repo_url = ?, demo_url = ?, sort_order = ?, visible = ?, updated_at = now() "
                        + "WHERE id = ?",
                p.getTitle(), p.getDescription(), p.getCoverUrl(), p.getTech(),
                p.getRepoUrl(), p.getDemoUrl(), p.getSortOrder(), p.isVisible(), p.getId());
    }

    public int deleteById(long id) {
        return jdbc.update("DELETE FROM kb_project WHERE id = ?", id);
    }

    private KbProject mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        KbProject p = new KbProject();
        p.setId(rs.getLong("id"));
        p.setTitle(rs.getString("title"));
        p.setDescription(rs.getString("description"));
        p.setCoverUrl(rs.getString("cover_url"));
        p.setTech(rs.getString("tech"));
        p.setRepoUrl(rs.getString("repo_url"));
        p.setDemoUrl(rs.getString("demo_url"));
        p.setSortOrder(rs.getInt("sort_order"));
        p.setVisible(rs.getBoolean("visible"));
        Timestamp c = rs.getTimestamp("created_at");
        p.setCreatedAt(c == null ? null : c.toInstant().atOffset(ZoneOffset.UTC));
        Timestamp u = rs.getTimestamp("updated_at");
        p.setUpdatedAt(u == null ? null : u.toInstant().atOffset(ZoneOffset.UTC));
        return p;
    }
}
