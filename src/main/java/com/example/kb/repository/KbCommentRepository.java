package com.example.kb.repository;

import com.example.kb.domain.KbComment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
 * kb_comment 表访问（留言板 + 动态评论共用）。
 *
 * <h3>⚠️ 三条可见性规则必须写在 SQL 里，不能在 Java 里 filter</h3>
 * <ol>
 *   <li><b>PUBLIC + VISIBLE</b> —— 所有人可见</li>
 *   <li><b>PRIVATE</b>（悄悄话）—— 只有站主可见。查询时对访客必须排除，
 *       否则「悄悄话」这个名字就是假的</li>
 *   <li><b>HIDDEN</b>（被屏蔽）—— 除了留言者本人和站主，别人看不到。
 *       留言者仍能看到自己那条，这样他知道自己被屏蔽了，
 *       而不是以为系统出 bug 了</li>
 * </ol>
 *
 * <p>为什么必须在 SQL 里：如果在 Service 里查出全量再过滤，只要有一次
 * 忘记 filter，悄悄话就直接暴露了。放在 SQL 里，调用方拿不到「不该拿到的行」，
 * 从数据层面就不可能泄露。</p>
 *
 * <h3>为什么访客视角要多一个 currentUserId 参数</h3>
 * <p>规则 3 要求「留言者仍能看到自己那条」，所以查询必须知道「看的人是谁」。
 * 游客传 null，注册用户传自己的 id。这不是可选的优化，而是可见性规则的
 * 组成部分 —— 少了它就违反需求。</p>
 */
@Repository
public class KbCommentRepository {

    private static final String COLUMNS =
            "id, moment_id, user_id, nickname, content, ip, device_id, "
                    + "visibility, status, parent_id, is_read, created_at";

    /** 站主后台的范围取值：全部 / 只要留言板 / 只要动态评论 */
    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_BOARD = "board";
    public static final String SCOPE_MOMENT = "moment";

    /**
     * 访客可见性条件。
     *
     * <p>拼进 WHERE 的片段。{@code userId} 为 null（游客）时只保留
     * 「PUBLIC + VISIBLE」，有值时额外放宽到「自己写的」。</p>
     *
     * <p>⚠️ 这里的 {@code ?} 参数顺序与 {@link #bindVisibility} 严格对应，
     * 改一处必须改另一处，否则会把 id 绑到 status 上。</p>
     */
    private static final String VISIBLE_CLAUSE =
            " AND (visibility = 'PUBLIC' AND status = 'VISIBLE'"
                    + " OR (user_id IS NOT NULL AND user_id = ?))";

    private final JdbcTemplate jdbc;

    public KbCommentRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // ==================== 写入 ====================

    /**
     * 插入一条留言，回填自增 id。
     *
     * <p>⚠️ {@code new String[]{"id"}} 而不是 {@code RETURN_GENERATED_KEYS}：
     * PG 驱动对后者返回整行，{@code getKey()} 会抛
     * {@code InvalidDataAccessApiUsageException}。</p>
     */
    public long insert(KbComment c) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kb_comment (moment_id, user_id, nickname, content, ip, "
                            + "device_id, visibility, status, parent_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            setNullableLong(ps, 1, c.getMomentId());
            setNullableLong(ps, 2, c.getUserId());
            ps.setString(3, c.getNickname());
            ps.setString(4, c.getContent());
            ps.setString(5, c.getIp());
            ps.setString(6, c.getDeviceId());
            ps.setString(7, c.getVisibilityOrDefault());
            ps.setString(8, c.getStatusOrDefault());
            setNullableLong(ps, 9, c.getParentId());
            return ps;
        }, holder);
        return holder.getKey().longValue();
    }

    /** 站主屏蔽/恢复一条留言 */
    public int updateStatus(long id, String status) {
        return jdbc.update("UPDATE kb_comment SET status = ? WHERE id = ?", status, id);
    }

    /**
     * 作者改自己的留言正文。
     *
     * <p>⚠️ 不加 {@code WHERE user_id = ?} 之类的条件 —— 归属校验在
     * {@link com.example.kb.service.CommentService} 里已经做过了（它要区分
     * 「游客不可改」「非作者不可改」「站主可改」三种情况，SQL 表达不了）。
     * Repository 只负责按 id 改，权限判断不散到两层去。</p>
     */
    public int updateContent(long id, String content) {
        return jdbc.update("UPDATE kb_comment SET content = ? WHERE id = ?", content, id);
    }

    /** 标记已读。传 null 表示全部 */
    public int markRead(Long id) {
        if (id == null) {
            return jdbc.update("UPDATE kb_comment SET is_read = TRUE WHERE is_read = FALSE");
        }
        return jdbc.update("UPDATE kb_comment SET is_read = TRUE WHERE id = ?", id);
    }

    /** 删一条（回复靠外键级联一起删） */
    public int deleteById(long id) {
        return jdbc.update("DELETE FROM kb_comment WHERE id = ?", id);
    }

    // ==================== 访客视角 ====================

    /**
     * 留言板（{@code moment_id IS NULL}）的访客视角分页。
     *
     * @param viewerId 看的人是谁（null = 游客）。用于「自己写的屏蔽留言仍可见」
     */
    public List<KbComment> findBoardForVisitor(Long viewerId, int limit, int offset) {
        String sql = "SELECT " + COLUMNS + " FROM kb_comment "
                + "WHERE moment_id IS NULL" + VISIBLE_CLAUSE
                + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return jdbc.query(sql, rowMapper,
                viewerIdOrZero(viewerId), limit, offset);
    }

    public long countBoardForVisitor(Long viewerId) {
        String sql = "SELECT count(*) FROM kb_comment "
                + "WHERE moment_id IS NULL" + VISIBLE_CLAUSE;
        Long n = jdbc.queryForObject(sql, Long.class, viewerIdOrZero(viewerId));
        return n == null ? 0 : n;
    }

    /** 某条动态下的评论，访客视角 */
    public List<KbComment> findMomentCommentsForVisitor(long momentId, Long viewerId, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM kb_comment "
                + "WHERE moment_id = ?" + VISIBLE_CLAUSE
                + " ORDER BY created_at ASC LIMIT ?";
        return jdbc.query(sql, rowMapper,
                momentId, viewerIdOrZero(viewerId), limit);
    }

    /**
     * 某条动态下的评论，站主视角（含悄悄话与已屏蔽）。
     *
     * <p>⚠️ 不要用「拉全站留言再 filter」代替：留言攒到几百条以后每次看一条
     * 动态都要把整张表捞进内存，而且 MAX_PAGE_LIMIT 一截断就静默丢数据。</p>
     */
    public List<KbComment> findMomentCommentsForAdmin(long momentId, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM kb_comment "
                + "WHERE moment_id = ? ORDER BY created_at ASC LIMIT ?";
        return jdbc.query(sql, rowMapper, momentId, limit);
    }

    public long countMomentComments(long momentId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM kb_comment WHERE moment_id = ?", Long.class, momentId);
        return n == null ? 0 : n;
    }

    // ==================== 站主视角 ====================

    /**
     * 全部留言（含悄悄话、含已屏蔽），站主后台用。
     *
     * <p>⚠️ 范围参数是三态的 {@code scope}（all / board / moment），不是布尔
     * {@code boardOnly}。原来的布尔只有「是不是留言板」两个状态，表达不了
     * 「只要动态评论」—— 传 false 时既不是「只要留言板」，也不该是「全部」，
     * 于是前端只能拉全量再 filter，而 total 和分页还是错的：第一页 20 条
     * 如果全是留言板留言，切到「动态评论」就是一片空白，用户会以为没数据。</p>
     */
    public List<KbComment> findAllForAdmin(int limit, int offset, Boolean unreadOnly,
                                           String scope) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM kb_comment WHERE 1 = 1");
        if (Boolean.TRUE.equals(unreadOnly)) {
            sql.append(" AND is_read = FALSE");
        }
        appendScopeClause(sql, scope);
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        return jdbc.query(sql.toString(), rowMapper, limit, offset);
    }

    public long countForAdmin(Boolean unreadOnly, String scope) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM kb_comment WHERE 1 = 1");
        if (Boolean.TRUE.equals(unreadOnly)) {
            sql.append(" AND is_read = FALSE");
        }
        appendScopeClause(sql, scope);
        Long n = jdbc.queryForObject(sql.toString(), Long.class);
        return n == null ? 0 : n;
    }

    /** 列表和计数共用同一段范围条件，避免两处写法漂移导致 total 与 items 对不上 */
    private static void appendScopeClause(StringBuilder sql, String scope) {
        if (SCOPE_BOARD.equalsIgnoreCase(scope)) {
            sql.append(" AND moment_id IS NULL");
        } else if (SCOPE_MOMENT.equalsIgnoreCase(scope)) {
            sql.append(" AND moment_id IS NOT NULL");
        }
        // SCOPE_ALL 或不认识的取值：不加条件，返回全部
    }

    public long countUnread() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM kb_comment WHERE is_read = FALSE", Long.class);
        return n == null ? 0 : n;
    }

    public Optional<KbComment> findById(long id) {
        List<KbComment> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_comment WHERE id = ?", rowMapper, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 某个设备指纹发过多少条留言。
     *
     * <p>用于站主后台：同一设备换昵称刷留言时，一眼能看出是同一伙人。</p>
     */
    public long countByDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return 0;
        }
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM kb_comment WHERE device_id = ?", Long.class, deviceId);
        return n == null ? 0 : n;
    }

    // ==================== 内部 ====================

    /**
     * 游客传 null 时绑 0（不会等于任何真实 user_id）。
     *
     * <p>⚠️ 不能绑 null：SQL 里 {@code user_id = NULL} 恒为 null（不是 false），
     * 整个 OR 条件会退化成 null 而不是匹配「PUBLIC + VISIBLE」那半边。
     * 这是 SQL 三值逻辑的经典坑。</p>
     */
    private static Long viewerIdOrZero(Long viewerId) {
        return viewerId == null ? 0L : viewerId;
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, Types.BIGINT);
        } else {
            ps.setLong(idx, v);
        }
    }

    private final RowMapper<KbComment> rowMapper = (rs, rowNum) -> {
        KbComment c = new KbComment();
        c.setId(rs.getLong("id"));
        c.setMomentId(nullableLong(rs, "moment_id"));
        c.setUserId(nullableLong(rs, "user_id"));
        c.setNickname(rs.getString("nickname"));
        c.setContent(rs.getString("content"));
        c.setIp(rs.getString("ip"));
        c.setDeviceId(rs.getString("device_id"));
        c.setVisibility(rs.getString("visibility"));
        c.setStatus(rs.getString("status"));
        c.setParentId(nullableLong(rs, "parent_id"));
        c.setRead(rs.getBoolean("is_read"));
        Timestamp ts = rs.getTimestamp("created_at");
        c.setCreatedAt(ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC));
        return c;
    };

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
