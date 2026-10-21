package com.example.kb.domain;

import java.time.OffsetDateTime;

/**
 * kb_moment 行：一条日常动态。
 *
 * <p>一条动态有三种形态：</p>
 * <ul>
 *   <li><b>纯文字</b>：{@code mediaUrl} 为空</li>
 *   <li><b>图/视频 + 文字</b>：{@code mediaUrl} 有值，{@code content} 可能有也可能没有</li>
 * </ul>
 *
 * <p>⚠️ {@code syncedPhotoId} 是「同步到照片墙」这个动作的凭据：
 * 发布时勾了「同步到照片墙」，就在 kb_photo 里插一条，把 id 记在这里。
 * 删动态时靠它把那张照片也删掉 —— 否则照片墙会留下一条没有出处的孤图，
 * 站主以后在照片墙里看到它都不知道是从哪来的。</p>
 *
 * <p>⚠️ 反过来<b>不成立</b>：站主在照片墙单独删掉那张图时，动态本身不动，
 * 只是把它在这里的 {@code syncedPhotoId} 置空（见 {@code MomentService}）。
 * 动态是主体，照片墙是它的一个投影，投影可以没，主体不能没。</p>
 */
public class KbMoment {

    /** 媒体类型常量。用字符串而不是 enum：DB 里存的是 TEXT，enum 要多一层转换 */
    public static final String MEDIA_IMAGE = "IMAGE";
    public static final String MEDIA_VIDEO = "VIDEO";

    private Long id;

    /** 文字内容，可为空（纯图/纯视频动态） */
    private String content;

    /** 媒体地址：图片或视频的 URL，纯文字为空 */
    private String mediaUrl;

    /** {@link #MEDIA_IMAGE} 或 {@link #MEDIA_VIDEO}，纯文字为 null */
    private String mediaType;

    /** 媒体宽高：前端用来占位，避免加载时布局跳动。读不出来可为 null */
    private Integer mediaWidth;
    private Integer mediaHeight;

    private Long mediaSize;

    /** 同步到照片墙后对应的 kb_photo.id，没同步为 null */
    private Long syncedPhotoId;

    /** 是否在主页可见 */
    private boolean visible = true;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMediaUrl() {
        return mediaUrl;
    }

    public void setMediaUrl(String mediaUrl) {
        this.mediaUrl = mediaUrl;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public Integer getMediaWidth() {
        return mediaWidth;
    }

    public void setMediaWidth(Integer mediaWidth) {
        this.mediaWidth = mediaWidth;
    }

    public Integer getMediaHeight() {
        return mediaHeight;
    }

    public void setMediaHeight(Integer mediaHeight) {
        this.mediaHeight = mediaHeight;
    }

    public Long getMediaSize() {
        return mediaSize;
    }

    public void setMediaSize(Long mediaSize) {
        this.mediaSize = mediaSize;
    }

    public Long getSyncedPhotoId() {
        return syncedPhotoId;
    }

    public void setSyncedPhotoId(Long syncedPhotoId) {
        this.syncedPhotoId = syncedPhotoId;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
