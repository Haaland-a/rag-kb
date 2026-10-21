package com.example.kb.infrastructure.geo;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 取客户端真实 IP。
 *
 * <p>为什么不能直接用 {@code request.getRemoteAddr()}：</p>
 * <ul>
 *   <li>服务跑在 Docker 容器里，{@code getRemoteAddr()} 拿到的是 docker 网关地址
 *       （172.17.0.1），所有人看起来都是同一个 IP</li>
 *   <li>如果前面挂了 nginx / 云厂商负载均衡，同样拿到的是代理地址</li>
 *   <li>云厂商的负载均衡会把真实 IP 放在 {@code X-Forwarded-For} 里</li>
 * </ul>
 *
 * <p>⚠️ <b>安全提醒</b>：{@code X-Forwarded-For} 是客户端可伪造的请求头，
 * 只有在可信代理后面才能信它。</p>
 *
 * <p>当前部署是 <b>nginx 反代</b>：公网 80/443 → nginx →
 * {@code 127.0.0.1:8080}。nginx 配置里把 XFF 写成
 * {@code proxy_set_header X-Forwarded-For $remote_addr}（<b>覆盖</b>而不是
 * {@code $proxy_add_x_forwarded_for} 追加），因此到达这里的 XFF 一定是
 * nginx 看到的真实对端地址，客户端自带的假 XFF 已被丢弃。
 * 这里取第一段是对的。</p>
 *
 * <p>反过来说：如果哪天有人把 nginx 配置改成追加模式，或者 8080 又暴露到
 * 公网，这段代码就会变成限流绕过的入口 —— 两个条件缺一不可。</p>
 */
@Component
public class ClientIpResolver {

    /** XFF 是逗号分隔的链，第一个是最初的客户端，后面是各级代理 */
    public String resolve(HttpServletRequest request, boolean trustProxy) {
        if (trustProxy) {
            String xff = request.getHeader("X-Forwarded-For");
            String fromXff = firstIp(xff);
            if (fromXff != null) {
                return fromXff;
            }
            // 有些代理只写 X-Real-IP
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return request.getRemoteAddr();
    }

    /** 从 "1.2.3.4, 5.6.7.8, ..." 里取第一个合法 IP */
    private static String firstIp(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        for (String part : header.split(",")) {
            String candidate = part.trim();
            // "unknown" 是某些代理在拿不到 IP 时写的占位符
            if (!candidate.isEmpty() && !"unknown".equalsIgnoreCase(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
