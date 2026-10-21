package com.example.kb.dto.visit;

import java.time.OffsetDateTime;

/**
 * 按 IP 聚合后的访客概要（{@code GET /api/visit/summary} 的每一项）。
 *
 * <p>⚠️ 这个类原来叫 {@code VisitRepository.VisitorSummary}，是<b>仓库层的内部类</b>。
 * 它其实是「接口响应」，被 Controller 直接返回给前端，属于 DTO 混进 Repository。
 * 现在独立到 {@code dto.visit}，Repository 只负责填充它。</p>
 *
 * <p>字段类型保持 {@link OffsetDateTime}（与原来一致）：Jackson 会序列化成
 * ISO-8601 字符串，前端 {@code new Date(iso)} 能直接解析。</p>
 */
public class VisitorSummary {

    private String ip;
    private long hits;
    private OffsetDateTime lastSeen;
    private String country;
    private String region;
    private String city;

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public long getHits() {
        return hits;
    }

    public void setHits(long hits) {
        this.hits = hits;
    }

    public OffsetDateTime getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(OffsetDateTime lastSeen) {
        this.lastSeen = lastSeen;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }
}
