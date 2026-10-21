package com.example.kb.dto.media;

import com.example.kb.domain.KbDownload;

/**
 * 简历 / 作品集下载项。
 *
 * @param id            下载项 id
 * @param title         展示名，如「Java 后端简历」
 * @param url           文件地址
 * @param fileType      pdf / docx / zip
 * @param fileSize      字节数
 * @param downloadCount 被下载次数
 * @param sortOrder     排序值
 * @param visible       是否在主页显示
 * @param createdAt     上传时间
 */
public record DownloadDto(long id,
                          String title,
                          String url,
                          String fileType,
                          Long fileSize,
                          long downloadCount,
                          int sortOrder,
                          boolean visible,
                          String createdAt) {

    public static DownloadDto of(KbDownload d) {
        return new DownloadDto(
                d.getId() == null ? 0L : d.getId(),
                d.getTitle(),
                d.getUrl(),
                d.getFileType(),
                d.getFileSize(),
                d.getDownloadCount(),
                d.getSortOrder(),
                d.isVisible(),
                d.getCreatedAt() == null ? null : d.getCreatedAt().toString());
    }
}
