package com.example.kb.domain;

import java.time.OffsetDateTime;

/**
 * kb_photo 行：主页照片墙的一张图。
 *
 * <p>⚠️ 图片本体不在数据库里，只在 {@code url} 存地址（本地 /media/... 或 OSS 的
 * https://...）。数据库存二进制图会撑爆备份、拖慢查询，而且 2C2G 上 pg 的
 * shared_buffers 只有 128MB，放图等于把缓存全挤掉。</p>
 */
public class KbPhoto {

    private Long id;
    /** 图片地址：/media/photos/xxx.jpg 或 https://bucket.oss/... */
    private String url;
    /** 一句话说明，可为空 */
    private String caption;
    /** 排序，越小越靠前 */
    private int sortOrder;
    /** 是否在主页显示 */
    private boolean visible = true;
    /** 宽高：前端用来占位，避免图片加载时布局跳动。读不出来时可为 null */
    private Integer width;
    private Integer height;
    private Long fileSize;
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
