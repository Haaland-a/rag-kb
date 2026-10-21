package com.example.kb.dto.media;

/**
 * 一条日常动态（含评论数）。
 *
 * @param id        动态 id
 * @param content   文字内容，纯图/纯视频动态可能为 null
 * @param mediaUrl  图片或视频地址，纯文字动态为 null
 * @param mediaType {@code IMAGE} / {@code VIDEO} / null
 * @param width     媒体宽度，前端占位用
 * @param height    媒体高度，前端占位用
 * @param size      文件字节数
 * @param synced    <b>是否已同步到照片墙</b>。前端据此在编辑框里回显勾选状态
 * @param visible   是否在主页可见
 * @param createdAt 发布时间（ISO-8601，前端自己格式化）
 * @param comments  评论数。<b>只给站主看</b>；访客视角恒为 0，
 *                  因为访客根本不知道有评论这回事（见 {@code MomentService}）
 */
public record MomentDto(long id,
                        String content,
                        String mediaUrl,
                        String mediaType,
                        Integer width,
                        Integer height,
                        Long size,
                        boolean synced,
                        boolean visible,
                        String createdAt,
                        long comments) {
}
