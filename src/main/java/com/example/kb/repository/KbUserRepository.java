package com.example.kb.repository;

import com.example.kb.domain.KbUser;
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
 * kb_user 表访问。
 *
 * <p>用 JdbcTemplate 不用 JPA：和 KbDocumentRepository 同一个理由（2C2G 内存账）。
 * 所有 SQL 集中在此，业务类不许拼 SQL。</p>
 */
@Repository
public class KbUserRepository {

    private final JdbcTemplate jdbc;

    public KbUserRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public long insert(KbUser user) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kb_user (username, password_hash, display_name, device_id, register_ip) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getPasswordHash());
            if (user.getDisplayName() == null) {
                ps.setNull(3, Types.VARCHAR);
            }
            else {
                ps.setString(3, user.getDisplayName());
            }
            if (user.getDeviceId() == null) {
                ps.setNull(4, Types.VARCHAR);
            }
            else {
                ps.setString(4, user.getDeviceId());
            }
            if (user.getRegisterIp() == null) {
                ps.setNull(5, Types.VARCHAR);
            }
            else {
                ps.setString(5, user.getRegisterIp());
            }
            return ps;
        }, holder);
        return holder.getKey().longValue();
    }

    /**
     * 该设备指纹是否已经注册过账号。
     *
     * <p>「同一设备不能注册多账号」的<b>硬约束</b>所在：数据库层还有
     * 一个部分唯一索引 {@code uk_kb_user_device} 兜底。这里先查一次是为了
     * 给出人能看懂的提示，而不是让用户看到 SQL 唯一约束冲突的报错。</p>
     */
    public boolean existsByDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM kb_user WHERE device_id = ?", Long.class, deviceId);
        return n != null && n > 0;
    }

    /**
     * 同 IP 在指定小时数内注册过几个账号。
     *
     * <p>配合设备指纹构成两道闸：设备指纹挡「同一浏览器」，IP 挡
     * 「清缓存换指纹后重来」。⚠️ 只能算软限制 —— 动态 IP、校园网 NAT
     * 都会误伤，所以阈值给得宽松（默认 24 小时 2 个），宁可漏也不要把
     * 正常用户挡在门外。</p>
     */
    public long countByRegisterIpSince(String ip, int hours) {
        if (ip == null || ip.isBlank()) {
            return 0;
        }
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM kb_user WHERE register_ip = ? "
                        + "AND created_at > now() - (? || ' hours')::interval",
                Long.class, ip, hours);
        return n == null ? 0 : n;
    }

    /**
     * 按用户名查（登录入口）。
     *
     * <p>不抛「用户不存在」，交给调用方统一返回「用户名或密码错误」——
     * 否则等于告诉攻击者「这个账号存在」，是常见的用户枚举漏洞。</p>
     */
    public Optional<KbUser> findByUsername(String username) {
        List<KbUser> rows = jdbc.query(
                "SELECT id, username, password_hash, display_name, created_at, last_login_at "
                        + "FROM kb_user WHERE username = ?",
                this::mapRow, username);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<KbUser> findById(long id) {
        List<KbUser> rows = jdbc.query(
                "SELECT id, username, password_hash, display_name, created_at, last_login_at "
                        + "FROM kb_user WHERE id = ?",
                this::mapRow, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM kb_user", Long.class);
        return n == null ? 0 : n;
    }

    public void updatePasswordHash(long id, String passwordHash) {
        jdbc.update("UPDATE kb_user SET password_hash = ? WHERE id = ?", passwordHash, id);
    }

    public void updateLastLoginAt(long id) {
        jdbc.update("UPDATE kb_user SET last_login_at = now() WHERE id = ?", id);
    }

    /**
     * 删掉除指定用户名以外的所有用户，返回删除行数。
     *
     * <p>给 {@link AdminInitializer} 用，保证「本站只有一个用户」。
     * ⚠️ 破坏性操作，调用点必须清楚自己在干什么。</p>
     */
    public int deleteAllExcept(String keepUsername) {
        return jdbc.update("DELETE FROM kb_user WHERE username <> ?", keepUsername);
    }

    private KbUser mapRow(ResultSet rs, int rowNum) throws SQLException {
        KbUser u = new KbUser();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setDisplayName(rs.getString("display_name"));
        u.setCreatedAt(toOffset(rs.getTimestamp("created_at")));
        u.setLastLoginAt(toOffset(rs.getTimestamp("last_login_at")));
        return u;
    }

    private static OffsetDateTime toOffset(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}
