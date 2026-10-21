package com.example.kb.infrastructure.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 IP 的滑动窗口限流。
 *
 * <p>为什么是「滑动窗口」不是「固定窗口」：固定窗口（比如每分钟重置计数）有边界突刺问题 ——
 * 用户在 12:00:59 打满配额、12:01:00 又满配额，相当于 1 秒内允许 2 倍流量。
 * 滑动窗口记录每次请求的时间戳，天然没有这个问题。</p>
 *
 * <p>为什么不用 Guava RateLimiter / Bucket4j：需求是「每小时 N 次」这种粗粒度配额，
 * 不是每秒几十次的精细限速。一个 {@code ArrayDeque<Long>} 记时间戳就够，
 * 少引一个依赖 = 2C2G 上少几百 KB 常量池 + 少一个潜在的升级风险。</p>
 *
 * <p>⚠️ <b>这是单机内存限流</b>。重启后计数清零，多实例部署时各算各的。
 * 对个人站够用；真要严格限制得上 Redis（第 2 周如果被刷爆再换）。</p>
 *
 * <p>⚠️ <b>按 IP 限流可被绕过</b>：换 IP、用代理都能重置换算。
 * 这是「防止单个访客把免费额度刷光」的防护，不是安全边界。
 * 真正的兜底是模型侧的免费额度上限。</p>
 */
@Component
public class IpRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(IpRateLimiter.class);

    /** 每小时允许的问答次数。可通过 rag.rate-limit.chat-per-hour 调整 */
    private final int chatPerHour;

    /** 每 IP 的请求时间戳队列（毫秒）。只保留窗口内的，超窗的从头部剔除 */
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /** 窗口长度：1 小时 */
    private static final long WINDOW_MS = 60L * 60L * 1000L;

    public IpRateLimiter(@Value("${rag.rate-limit.chat-per-hour:20}") int chatPerHour) {
        this.chatPerHour = Math.max(1, chatPerHour);
        log.info("问答限流已启用：每 IP 每小时 {} 次", this.chatPerHour);
    }

    /**
     * 尝试消费一次配额。
     *
     * @return {@code null} 表示允许；否则返回一句话说明为什么拒绝（直接回给前端）
     */
    public String tryConsumeChat(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> queue = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());

        synchronized (queue) {
            // 剔除窗口外的时间戳
            while (!queue.isEmpty() && now - queue.peekFirst() > WINDOW_MS) {
                queue.pollFirst();
            }
            if (queue.size() >= chatPerHour) {
                long oldest = queue.peekFirst();
                long waitSec = Math.max(1, (WINDOW_MS - (now - oldest)) / 1000);
                long waitMin = Math.max(1, waitSec / 60);
                return "提问太频繁了，每小时最多 " + chatPerHour + " 次，约 " + waitMin + " 分钟后可以继续";
            }
            queue.addLast(now);
            return null;
        }
    }

    /** 查询某 IP 当前已用配额，给前端显示「今日还剩 x 次」 */
    public int remainingChat(String ip) {
        Deque<Long> queue = hits.get(ip);
        if (queue == null) {
            return chatPerHour;
        }
        long now = System.currentTimeMillis();
        synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst() > WINDOW_MS) {
                queue.pollFirst();
            }
            return Math.max(0, chatPerHour - queue.size());
        }
    }

    public int getChatPerHour() {
        return chatPerHour;
    }

    /**
     * 每 10 分钟清理一次空队列。
     *
     * <p>为什么需要：{@code computeIfAbsent} 只增不减，被扫描器扫过的 IP
     * 会永久占一个空 Deque。几百个无所谓，几十万个就是内存泄漏。</p>
     */
    @Scheduled(fixedDelay = 10L * 60L * 1000L, initialDelay = 60L * 1000L)
    public void cleanup() {
        long now = System.currentTimeMillis();
        int before = hits.size();
        hits.entrySet().removeIf(entry -> {
            Deque<Long> q = entry.getValue();
            synchronized (q) {
                while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) {
                    q.pollFirst();
                }
                return q.isEmpty();
            }
        });
        int removed = before - hits.size();
        if (removed > 0) {
            log.debug("限流器清理了 {} 个空 IP 队列，剩余 {} 个", removed, hits.size());
        }
    }

    /** 仅供测试/排查 */
    long windowMillis() {
        return WINDOW_MS;
    }

    /** 仅供排查：当前被跟踪的 IP 数 */
    public int trackedIps() {
        return hits.size();
    }

    static Instant nowInstant() {
        return Instant.now();
    }
}
