package com.example.kb.domain;

import java.time.OffsetDateTime;

/**
 * 一条留言/评论。
 *
 * <p>⚠️ 一张表两个场景，靠 {@code momentId} 区分：</p>
 * <ul>
 *   <li>{@code momentId == null} → 留言板上的留言（不属于任何动态）</li>
 *   <li>{@code momentId != null} → 某条动态下的评论</li>
 * </ul>
 *
 * <p>这样做而不是拆两张表：两种场景的字段 90% 重合（内容、作者、可见性、
 * 状态、回复关系、已读），拆开就要维护两套 Repository 和两套 Controller。
 * 代价是每个查询都得带上 {@code moment_id IS NULL} 或 {@code IS NOT NULL}
 * 的条件 —— 比维护两套便宜。</p>
 *
 * <p>⚠️ {@code userId == null} 表示游客（未注册）。这类留言不可编辑、
 * 不可删除、不可找回，界面上会明确告诉留言的人。</p>
 */
public class KbComment {

    /** 对外可见 */
    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    /** 悄悄话：只有站主看得到 */
    public static final String VISIBILITY_PRIVATE = "PRIVATE";

    /** 正常展示 */
    public static final String STATUS_VISIBLE = "VISIBLE";
    /** 站主屏蔽：别人看不到，留言者自己仍能看到 */
    public static final String STATUS_HIDDEN = "HIDDEN";

    public static final String DEFAULT_NICKNAME = "匿名访客";

    private Long id;
    /** null = 留言板；非 null = 挂在某条动态下 */
    private Long momentId;
    /** null = 游客；非 null = 注册用户 */
    private Long userId;
    private String nickname;
    private String content;
    private String ip;
    private String deviceId;
    private String visibility = VISIBILITY_PUBLIC;
    private String status = STATUS_VISIBLE;
    /** null = 顶层；非 null = 回复某条留言 */
    private Long parentId;
    private boolean read;
    private OffsetDateTime createdAt;

    /**
     * 拼成给人看的地点文本。
     *
     * <p>⚠️ 留言本身不带归属地（那是 kb_visit 的职责），这个方法
     * 只是给「需要展示地点的场景」留个位置，目前调用方传的是 null。</p>
     */
    public String getVisibilityOrDefault() {
        return visibility == null || visibility.isBlank() ? VISIBILITY_PUBLIC : visibility;
    }

    public String getStatusOrDefault() {
        return status == null || status.isBlank() ? STATUS_VISIBLE : status;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMomentId() {
        return momentId;
    }

    public void setMomentId(Long momentId) {
        this.momentId = momentId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
