package com.example.kb.repository;

import com.example.kb.domain.KbChat;
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

/**
 * kb_chat 表访问。
 *
 * <p>⚠️ 归属地不做成这里的字段，而是 {@link #findRecent} 用 LATERAL 子查询
 * 从 kb_visit 取<b>同一 IP 最近一次</b>的归属地。理由：问答记录和访问记录
 * 是两条独立写入的行，同一个 IP 的归属地只该由 GeoIpService 补一次。
 * 存两份就要维护两处补全逻辑，早晚不同步。</p>
 */
@Repository
public class KbChatRepository {

    private static final String COLUMNS =
            "id, ip, question, answer, sources, source_count, elapsed_ms, answer_chars, "
                    + "complete, is_admin, created_at";

    private final JdbcTemplate jdbc;

    public KbChatRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * 插入一条问答记录，回填自增 id。
     *
     * <p>⚠️ {@code new String[]{"id"}} 而不是 {@code RETURN_GENERATED_KEYS}：
     * PostgreSQL 驱动对后者返回整行，KeyHolder.getKey() 会抛
     * {@code InvalidDataAccessApiUsageException}。这是本项目踩过两次的坑。</p>
     */
    public long insert(KbChat c) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kb_chat (ip, question, answer, sources, source_count, "
                            + "elapsed_ms, answer_chars, complete, is_admin) "
                            + "VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, c.getIp());
            ps.setString(2, c.getQuestion());
            ps.setString(3, c.getAnswer());
            ps.setString(4, c.getSourcesJson() == null ? "[]" : c.getSourcesJson());
            ps.setInt(5, c.getSourceCount());
            setNullableInt(ps, 6, c.getElapsedMs());
            setNullableInt(ps, 7, c.getAnswerChars());
            ps.setBoolean(8, c.isComplete());
            ps.setBoolean(9, c.isAdmin());
            return ps;
        }, holder);
        return holder.getKey().longValue();
    }

    /**
     * 更新一条已插入的记录（流式回答结束后回填全文）。
     *
     * <p>⚠️ 为什么是「先 insert 再 update」而不是「生成完一次性 insert」：
     * 访客可能在回答生成到一半时关掉页面。如果只在最后写库，那些记录
     * 一条都不会留下 —— 但「他问了什么」恰恰是站主最想看的。
     * 先插入拿到 id，再逐段累加更新，中途断开也能留下已生成的部分。</p>
     */
    public void finish(long id, String answer, int answerChars,
                       int sourceCount, Integer elapsedMs, boolean complete) {
        jdbc.update("UPDATE kb_chat SET answer = ?, answer_chars = ?, source_count = ?, "
                        + "elapsed_ms = ?, complete = ? WHERE id = ?",
                answer, answerChars, sourceCount, elapsedMs, complete, id);
    }

    /**
     * 后台列表：倒序分页，带归属地。
     *
     * <p>SQL 里的 {@code LEFT JOIN LATERAL} 是这个查询的关键：
     * 对每条问答记录，找同一 IP 在 kb_visit 里最新的一条带归属地的访问，
     * 取出 country/region/city。写成普通 JOIN 会一对多放大行数，
     * 写成子查询再拼 <code>IN</code> 会退化成 N 次往返。</p>
     *
     * @param onlyVisitor true 时排除站主自己的提问（站主后台看「访客都问什么」时不希望混进自己调试的问句）
     */
    public List<ChatWithLocation> findRecent(int limit, int offset, boolean onlyVisitor) {
        String where = onlyVisitor ? " WHERE c.is_admin = false " : " ";
        String sql = "SELECT c.id, c.ip, c.question, c.answer, c.sources, c.source_count, "
                + "c.elapsed_ms, c.answer_chars, c.complete, c.is_admin, c.created_at, "
                + "g.country, g.region, g.city "
                + "FROM kb_chat c "
                + "LEFT JOIN LATERAL ("
                + "  SELECT v.country, v.region, v.city FROM kb_visit v "
                + "  WHERE v.ip = c.ip AND v.geo_status = 'DONE' "
                + "  ORDER BY v.created_at DESC LIMIT 1"
                + ") g ON true "
                + where
                + "ORDER BY c.created_at DESC LIMIT ? OFFSET ?";
        return jdbc.query(sql, (rs, rowNum) -> {
            KbChat c = mapRow(rs, rowNum);
            return new ChatWithLocation(c,
                    rs.getString("country"), rs.getString("region"), rs.getString("city"));
        }, limit, offset);
    }

    public long count(boolean onlyVisitor) {
        String sql = onlyVisitor
                ? "SELECT count(*) FROM kb_chat WHERE is_admin = false"
                : "SELECT count(*) FROM kb_chat";
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    /** 单条详情（站主点开看全文用） */
    public java.util.Optional<ChatWithLocation> findById(long id) {
        String sql = "SELECT c.id, c.ip, c.question, c.answer, c.sources, c.source_count, "
                + "c.elapsed_ms, c.answer_chars, c.complete, c.is_admin, c.created_at, "
                + "g.country, g.region, g.city "
                + "FROM kb_chat c "
                + "LEFT JOIN LATERAL ("
                + "  SELECT v.country, v.region, v.city FROM kb_visit v "
                + "  WHERE v.ip = c.ip AND v.geo_status = 'DONE' "
                + "  ORDER BY v.created_at DESC LIMIT 1"
                + ") g ON true "
                + "WHERE c.id = ?";
        List<ChatWithLocation> rows = jdbc.query(sql, (rs, rowNum) -> {
            KbChat c = mapRow(rs, rowNum);
            return new ChatWithLocation(c,
                    rs.getString("country"), rs.getString("region"), rs.getString("city"));
        }, id);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    /** 删一条 */
    public int deleteById(long id) {
        return jdbc.update("DELETE FROM kb_chat WHERE id = ?", id);
    }

    /** 清空（站主想重置统计时用） */
    public int deleteAll() {
        return jdbc.update("DELETE FROM kb_chat");
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer v)
            throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.INTEGER);
        } else {
            ps.setInt(idx, v);
        }
    }

    private KbChat mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        KbChat c = new KbChat();
        c.setId(rs.getLong("id"));
        c.setIp(rs.getString("ip"));
        c.setQuestion(rs.getString("question"));
        c.setAnswer(rs.getString("answer"));
        c.setSourcesJson(rs.getString("sources"));
        c.setSourceCount(rs.getInt("source_count"));
        int elapsed = rs.getInt("elapsed_ms");
        c.setElapsedMs(rs.wasNull() ? null : elapsed);
        int chars = rs.getInt("answer_chars");
        c.setAnswerChars(rs.wasNull() ? null : chars);
        c.setComplete(rs.getBoolean("complete"));
        c.setAdmin(rs.getBoolean("is_admin"));
        Timestamp ts = rs.getTimestamp("created_at");
        c.setCreatedAt(ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC));
        return c;
    }

    /**
     * 问答记录 + 关联出来的归属地。
     *
     * <p>⚠️ 组合类放在 Repository：它是 SQL join 的直接产物，
     * 只在「问答 + 那个 IP 在哪儿」这个场景下有意义
     * （和 {@code KbMomentCommentRepository.CommentWithMoment} 同一个理由）。</p>
     */
    public record ChatWithLocation(KbChat chat, String country, String region, String city) {

        /** 拼成「中国 山西 晋中」，全空返回 null */
        public String locationText() {
            StringBuilder sb = new StringBuilder();
            for (String part : new String[]{country, region, city}) {
                if (part != null && !part.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(part);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
    }
}
