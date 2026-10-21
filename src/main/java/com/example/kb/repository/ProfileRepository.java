package com.example.kb.repository;

import com.example.kb.domain.KbProfile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * kb_profile 单行表访问。
 *
 * <p>⚠️ JSONB 的三个字段（about / links / tags）在这里完成
 * JSON ↔ Java 对象的转换。业务层只见到 {@code List<String>} 和
 * {@code List<KbProfile.Link>}，不需要知道它们在库里是 jsonb。</p>
 *
 * <p>⚠️ 为什么不直接在 SQL 里用 {@code jsonb_array_elements_text} 展开：
 * 一行数据、三个小数组，展开成多表联查比在内存里 parse 更慢也更难读。
 * 真正的理由是「这个站只有一行 profile」—— 任何为了压榨性能而牺牲
 * 可读性的写法在這裡都不划算。</p>
 */
@Repository
public class ProfileRepository {

    private static final Logger log = LoggerFactory.getLogger(ProfileRepository.class);

    private static final String COLUMNS =
            "id, owner, avatar_text, avatar_url, tagline, intro, about, links, tags, updated_at";

    /** about / tags 是字符串数组，links 是对象数组 */
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<KbProfile.Link>> LINK_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ProfileRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
    }

    /** 读那一行。表还是空的时候返回 empty，调用方据此回落到配置文件 */
    public Optional<KbProfile> find() {
        List<KbProfile> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_profile WHERE id = 1", this::mapRow);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 整行覆盖写入（不存在就插）。
     *
     * <p>⚠️ 用 {@code ON CONFLICT (id) DO UPDATE} 而不是「先查再决定 insert/update」：
     * 后者有竞态（两个请求同时发现不存在，都去 insert，第二个报主键冲突）。
     * 虽然这个站只有站主一个人会点保存，但写对了不花钱。</p>
     */
    public void save(KbProfile p) {
        jdbc.update(
                "INSERT INTO kb_profile (id, owner, avatar_text, avatar_url, tagline, intro, about, links, tags, updated_at) "
                        + "VALUES (1, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, now()) "
                        + "ON CONFLICT (id) DO UPDATE SET "
                        + "owner = EXCLUDED.owner, "
                        + "avatar_text = EXCLUDED.avatar_text, "
                        + "avatar_url = EXCLUDED.avatar_url, "
                        + "tagline = EXCLUDED.tagline, "
                        + "intro = EXCLUDED.intro, "
                        + "about = EXCLUDED.about, "
                        + "links = EXCLUDED.links, "
                        + "tags = EXCLUDED.tags, "
                        + "updated_at = now()",
                p.getOwner(), p.getAvatarText(), p.getAvatarUrl(), p.getTagline(), p.getIntro(),
                json(p.getAbout()), json(p.getLinks()), json(p.getTags()));
    }

    /**
     * 删掉那一行，让「关于我」回到「听配置文件」的状态。
     *
     * <p>⚠️ 这是有意的破坏性操作，只由站主主动触发（后台的「恢复配置文件内容」按钮）。
     * 不提供「部分清空」，因为整行删除的语义最干净：删了就等于从没编辑过。</p>
     */
    public int delete() {
        return jdbc.update("DELETE FROM kb_profile WHERE id = 1");
    }

    /** 序列化失败不能让人存不了关于我 —— 退回空数组，至少其他字段能存下 */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception e) {
            log.warn("profile 字段序列化失败，按空数组处理：{}", e.getMessage());
            return "[]";
        }
    }

    private List<String> readStringList(String raw) {
        return readJson(raw, STRING_LIST, List.of());
    }

    private List<KbProfile.Link> readLinkList(String raw) {
        return readJson(raw, LINK_LIST, List.of());
    }

    /**
     * 反序列化失败返回兜底值。
     *
     * <p>⚠️ 必须容错：数据库里的 jsonb 万一被手工改坏了，
     * 整个主页就会白屏。降级成「这一块没内容」远好于「整站打不开」。</p>
     */
    private <T> T readJson(String raw, TypeReference<T> type, T fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(raw, type);
        }
        catch (Exception e) {
            log.warn("profile JSON 解析失败（已按空处理）：{}", e.getMessage());
            return fallback;
        }
    }

    private KbProfile mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        KbProfile p = new KbProfile();
        p.setId(rs.getInt("id"));
        p.setOwner(rs.getString("owner"));
        p.setAvatarText(rs.getString("avatar_text"));
        p.setAvatarUrl(rs.getString("avatar_url"));
        p.setTagline(rs.getString("tagline"));
        p.setIntro(rs.getString("intro"));
        p.setAbout(readStringList(rs.getString("about")));
        p.setLinks(readLinkList(rs.getString("links")));
        p.setTags(readStringList(rs.getString("tags")));
        Timestamp ts = rs.getTimestamp("updated_at");
        p.setUpdatedAt(ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC));
        return p;
    }
}
