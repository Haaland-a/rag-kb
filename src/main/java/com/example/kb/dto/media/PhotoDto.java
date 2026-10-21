package com.example.kb.dto.media;

import com.example.kb.domain.KbPhoto;

/**
 * 照片墙一张图（主页 + 后台共用）。
 *
 * <p>为什么主页和后台共用一个 DTO：字段完全一样，只有「查哪些行」不同
 * （访客看 visible=true，站主看全部）。分两个 DTO 只会让前端多写一套解析。</p>
 *
 * @param id        照片 id
 * @param url       图片地址（本地 /media/... 或 OSS https URL）
 * @param caption   一句话说明，可为空
 * @param sortOrder 排序值，越小越靠前
 * @param visible   是否在主页显示
 * @param width     图片宽度（可为 null，前端据此占位）
 * @param height    图片高度
 * @param fileSize  字节数
 * @param createdAt 上传时间
 */
public record PhotoDto(long id,
                       String url,
                       String caption,
                       int sortOrder,
                       boolean visible,
                       Integer width,
                       Integer height,
                       Long fileSize,
                       String createdAt) {

    public static PhotoDto of(KbPhoto p) {
        return new PhotoDto(
                p.getId() == null ? 0L : p.getId(),
                p.getUrl(),
                p.getCaption(),
                p.getSortOrder(),
                p.isVisible(),
                p.getWidth(),
                p.getHeight(),
                p.getFileSize(),
                p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
    }
}
