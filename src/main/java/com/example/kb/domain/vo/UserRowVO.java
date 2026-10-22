package com.example.kb.domain.vo;

/**
 * 用户列表里的一行（站主专用）。
 *
 * <p>⚠️ 这个 VO 里<b>没有</b> {@code passwordHash}，也没有 {@code deviceId}。
 * 前者是绝对不能发出去的东西；后者是你用来防重复注册的指纹，暴露给前端也只是让
 * 别人更容易复制。列表页要看的是「谁注册了、什么时候、从哪来」。</p>
 *
 * <p>为什么不用 {@code KbUser} 实体直接返回：实体上有 passwordHash，一旦某天有人
 * 顺手写了 {@code R.ok(users)} 就把散列发出去了。让实体不出 Service 层，
 * Controller 只能拿到这个 VO，从类型上就不可能泄露。</p>
 */
public class UserRowVO {

    private Long id;
    private String username;
    private String displayName;

    /** 注册时间（服务器时区，yyyy-MM-dd HH:mm:ss） */
    private String createdAt;

    /** 最近一次登录时间；从没登录过是 null */
    private String lastLoginAt;

    /** 注册来源 IP；老数据可能为 null */
    private String registerIp;

    /** 是不是站主（用户名匹配 rag.admin.username） */
    private boolean admin;

    /** 有没有绑设备指纹。为 false 说明是手工建的账号或早期数据 */
    private boolean deviceBound;

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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(String lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public String getRegisterIp() {
        return registerIp;
    }

    public void setRegisterIp(String registerIp) {
        this.registerIp = registerIp;
    }

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }

    public boolean isDeviceBound() {
        return deviceBound;
    }

    public void setDeviceBound(boolean deviceBound) {
        this.deviceBound = deviceBound;
    }
}
