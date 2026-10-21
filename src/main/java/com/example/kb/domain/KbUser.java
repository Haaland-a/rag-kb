package com.example.kb.domain;

import java.time.OffsetDateTime;

/**
 * kb_user 行 = 一个能登录的账号。
 *
 * <p>⚠️ passwordHash 存的是 BCrypt 散列，不是明文。
 * BCrypt 自带随机盐，同一个密码两次散列结果不同，所以「忘记密码」只能重置不能找回。</p>
 */
public class KbUser {

    private Long id;
    private String username;
    /** BCrypt 散列，禁止提供 getter 给业务层打印（日志泄漏） */
    private String passwordHash;
    private String displayName;
    /**
     * 注册时的浏览器设备指纹。
     *
     * <p>用于「同一设备不能注册多账号」。数据库层有部分唯一索引
     * {@code uk_kb_user_device} 兜底，所以这个值一旦写入就基本等于该设备的身份。</p>
     *
     * <p>⚠️ 早期手工建的账号（比如管理员）是 null —— 唯一索引对 null 不生效，
     * 不影响多行共存。</p>
     */
    private String deviceId;
    /** 注册时的 IP，配合 deviceId 做第二道软限制 */
    private String registerIp;
    private OffsetDateTime createdAt;
    private OffsetDateTime lastLoginAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(OffsetDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getRegisterIp() {
        return registerIp;
    }

    public void setRegisterIp(String registerIp) {
        this.registerIp = registerIp;
    }
}
