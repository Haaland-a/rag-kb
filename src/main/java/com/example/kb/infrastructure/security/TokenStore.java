package com.example.kb.infrastructure.security;

import com.example.kb.domain.po.KbUser;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录 token 存储（纯内存）。
 *
 * <p>设计取舍：</p>
 * <ul>
 *   <li><b>不落库</b>：token 表要定期清理、要处理并发踢下线、要考虑多节点共享。
 *       单机单人演示场景，一个 ConcurrentHashMap 全部搞定，零维护成本。</li>
 *   <li><b>重启即失效</b>：用户重新登录一次即可。对「自己的知识库」这是可接受的，
 *       反而更安全（不可能有长期有效的泄漏 token）。</li>
 *   <li><b>过期时间 7 天</b>：滑动过期——每次带 token 的请求都刷新，活跃用户永不用重登，
 *       七天不用的自动清掉。</li>
 * </ul>
 *
 * <p>⚠️ 多实例部署时这个方案会坏（token 在 A 节点，请求打到 B 节点就 401）。
 * 本项目是单 jar 单进程，第 2 周若上多副本，把这里换成 Redis 即可，接口不用变。</p>
 */
@Component
public class TokenStore {

    /** 一条登录会话 */
    public record Session(long userId, String username, String displayName, Instant expiresAt) {

        public boolean expired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    private static final long TTL_DAYS = 7;

    /** 128 位随机数 → Base64 URL 安全编码（无 padding），22 字符 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 登录成功时创建会话，返回 token */
    public String create(KbUser user) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session session = new Session(user.getId(), user.getUsername(), user.getDisplayName(),
                Instant.now().plusSeconds(TTL_DAYS * 24 * 3600));
        sessions.put(token, session);
        return token;
    }

    /**
     * 查会话并滑动续期。
     *
     * <p>⚠️ 顺手清理过期项：Map 会随登录次数单调增长，虽然 7 天后失效，
     * 但失效项不会自己消失。放在查找路径里做惰性清理，不需要起定时任务。</p>
     */
    public Optional<Session> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (session.expired(now)) {
            sessions.remove(token);
            return Optional.empty();
        }
        // 滑动过期：续 7 天
        Session renewed = new Session(session.userId(), session.username(),
                session.displayName(), now.plusSeconds(TTL_DAYS * 24 * 3600));
        sessions.put(token, renewed);
        return Optional.of(renewed);
    }

    public void remove(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** 踢掉某用户的全部会话（改密码后用） */
    public void removeByUserId(long userId) {
        sessions.entrySet().removeIf(e -> e.getValue().userId() == userId);
    }

    public int size() {
        return sessions.size();
    }
}
