package com.example.kb.domain;

import java.time.OffsetDateTime;

/**
 * 访客记录。
 *
 * <p>核心字段是 {@code ip} + {@code createdAt}；归属地五个字段是异步补的，
 * 刚插入时都是 null，{@code geoStatus} 为 PENDING。</p>
 */
public class KbVisit {

    private Long id;
    private String ip;
    private String path;
    private String question;
    private String country;
    private String region;
    private String city;
    private String isp;
    private String userAgent;
    private String referer;
    private boolean admin;
    private String geoStatus;
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
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

    public String getIsp() {
        return isp;
    }

    public void setIsp(String isp) {
        this.isp = isp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getReferer() {
        return referer;
    }

    public void setReferer(String referer) {
        this.referer = referer;
    }

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }

    public String getGeoStatus() {
        return geoStatus;
    }

    public void setGeoStatus(String geoStatus) {
        this.geoStatus = geoStatus;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /** 拼一个给人看的地点串，如「中国 山西 晋中」，全空时返回 null */
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
