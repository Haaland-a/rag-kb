package com.example.kb.repository;

import com.example.kb.domain.KbVisit;
import com.example.kb.dto.visit.VisitorSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 访客日志读写。
 *
 * <p>⚠️ 这张表会随时间无限增长。当前没做自动清理 —— 2C2G 磁盘 40G，
 * 每行按 300 字节算，100 万行才 300MB，够用很久。要清理时加一条
 * {@code DELETE FROM kb_visit WHERE created_at < now() - interval '90 days'} 的定时任务即可。</p>
 */
@Repository
public class VisitRepository {

    private final JdbcTemplate jdbc;

    public VisitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 列顺序必须与 {@link #MAPPER} 一致 */
    private static final String COLUMNS =
            "id, ip, path, question, country, region, city, isp, user_agent, referer, "
                    + "is_admin, geo_status, created_at";

    private static final RowMapper<KbVisit> MAPPER = (rs, rowNum) -> {
        KbVisit v = new KbVisit();
        v.setId(rs.getLong("id"));
        v.setIp(rs.getString("ip"));
        v.setPath(rs.getString("path"));
        v.setQuestion(rs.getString("question"));
        v.setCountry(rs.getString("country"));
        v.setRegion(rs.getString("region"));
        v.setCity(rs.getString("city"));
        v.setIsp(rs.getString("isp"));
        v.setUserAgent(rs.getString("user_agent"));
        v.setReferer(rs.getString("referer"));
        v.setAdmin(rs.getBoolean("is_admin"));
        v.setGeoStatus(rs.getString("geo_status"));
        Timestamp ts = rs.getTimestamp("created_at");
        v.setCreatedAt(ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC));
        return v;
    };

    /**
     * 插入一条访问记录，回填自增 id。
     *
     * <p>⚠️ 用 {@code new String[]{"id"}} 而不是 {@code RETURN_GENERATED_KEYS}：
     * PostgreSQL 驱动对后者返回整行，Spring 的 KeyHolder.getKey() 会抛
     * {@code InvalidDataAccessApiUsageException: The getKey method should only be
     * used when a single key is returned}。这是踩过的坑。</p>
     */
    public long insert(KbVisit v) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kb_visit (ip, path, question, user_agent, referer, is_admin) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, v.getIp());
            ps.setString(2, v.getPath());
            ps.setString(3, v.getQuestion());
            ps.setString(4, v.getUserAgent());
            ps.setString(5, v.getReferer());
            ps.setBoolean(6, v.isAdmin());
            return ps;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入访客记录未返回主键");
        }
        long id = key.longValue();
        v.setId(id);
        return id;
    }

    /** 后台补归属地用：取一批还没查过归属地的记录 */
    public List<KbVisit> findPendingGeo(int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_visit WHERE geo_status = 'PENDING' "
                        + "ORDER BY id LIMIT ?",
                MAPPER, limit);
    }

    /** 归属地查到了，写回五个字段并把状态改成 DONE */
    public void updateGeo(long id, String country, String region, String city, String isp) {
        jdbc.update("UPDATE kb_visit SET country = ?, region = ?, city = ?, isp = ?, "
                        + "geo_status = 'DONE' WHERE id = ?",
                country, region, city, isp, id);
    }

    /** 归属地查不到（内网 IP / 接口全挂），标记 FAILED 避免每轮重复扫同一行 */
    public void markGeoFailed(long id) {
        jdbc.update("UPDATE kb_visit SET geo_status = 'FAILED' WHERE id = ?", id);
    }

    /** 后台列表用，倒序分页 */
    public List<KbVisit> findRecent(int limit, int offset) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_visit ORDER BY created_at DESC, id DESC "
                        + "LIMIT ? OFFSET ?",
                MAPPER, limit, offset);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM kb_visit", Long.class);
        return n == null ? 0 : n;
    }

    /**
     * 提问总次数。
     *
     * <p>只算 {@code question IS NOT NULL} 的行 —— 单纯的「访问主页」也会写一条
     * kb_visit（path 为 /api/chat），只有真发了问题才带 question 字段。</p>
     */
    public long countQuestions() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM kb_visit WHERE question IS NOT NULL", Long.class);
        return n == null ? 0 : n;
    }

    /** 按 IP 聚合，看谁来得最勤 —— 站主最关心的那张表 */
    public List<VisitorSummary> summarizeByIp(int limit) {
        return jdbc.query(
                "SELECT ip, count(*) AS hits, max(created_at) AS last_seen, "
                        + "  (array_agg(country ORDER BY created_at DESC))[1] AS country, "
                        + "  (array_agg(region  ORDER BY created_at DESC))[1] AS region, "
                        + "  (array_agg(city    ORDER BY created_at DESC))[1] AS city "
                        + "FROM kb_visit WHERE is_admin = false "
                        + "GROUP BY ip ORDER BY max(created_at) DESC LIMIT ?",
                (rs, rowNum) -> {
                    VisitorSummary s = new VisitorSummary();
                    s.setIp(rs.getString("ip"));
                    s.setHits(rs.getLong("hits"));
                    Timestamp ts = rs.getTimestamp("last_seen");
                    s.setLastSeen(ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC));
                    s.setCountry(rs.getString("country"));
                    s.setRegion(rs.getString("region"));
                    s.setCity(rs.getString("city"));
                    return s;
                },
                limit);
    }

    /** 内部用：按 id 取一条，排查时方便 */
    public Optional<KbVisit> findById(long id) {
        List<KbVisit> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_visit WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
