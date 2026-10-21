package com.example.kb.dto.document;

import com.example.kb.domain.KbDocument;

/**
 * 文档列表/详情响应。
 *
 * <p>⚠️ 为什么不直接返回 {@link KbDocument}：</p>
 * <ol>
 *   <li><b>contentHash 是内部字段</b>。整篇 SHA-256 用来查重，前端不需要，
 *       返回出去等于把「这份文件的具体版本」告诉了外部。将来如果做
 *       「公开文档库」，别人能靠 hash 反查你是否收录过某份文件。</li>
 *   <li><b>实体和接口契约绑死</b>。数据库加一列（比如软删除标记），
 *       接口响应会跟着变，前端可能因为多一个 null 字段崩掉。</li>
 *   <li>这是标准分层的基本要求：Controller 返回 DTO，Entity 不出 Repository 之外。</li>
 * </ol>
 *
 * @param id         文档 id
 * @param filename   原始文件名
 * @param fileType   pdf / md / txt
 * @param fileSize   字节数
 * @param pageCount  页数（PDF 才有，其余为 null）
 * @param chunkCount 切出的块数
 * @param status     PENDING / PARSING / INDEXING / READY / FAILED
 * @param errorMsg   失败原因（成功时为 null）
 * @param createdAt  上传时间（ISO-8601）
 * @param updatedAt  最后更新时间（ISO-8601）
 */
public record DocumentDto(long id,
                          String filename,
                          String fileType,
                          long fileSize,
                          Integer pageCount,
                          int chunkCount,
                          String status,
                          String errorMsg,
                          String createdAt,
                          String updatedAt) {

    /**
     * 实体 → DTO。
     *
     * <p>时间转成 ISO-8601 字符串：前端用 {@code new Date(iso)} 能直接解析。
     * 如果直接返回 {@code OffsetDateTime}，Jackson 也会序列化成同样的字符串，
     * 但显式转换让「时间以什么格式出现在接口里」变成可由代码审查的事实，
     * 而不是依赖 Jackson 的全局配置。</p>
     */
    public static DocumentDto of(KbDocument d) {
        return new DocumentDto(
                d.getId() == null ? 0L : d.getId(),
                d.getFilename(),
                d.getFileType(),
                d.getFileSize() == null ? 0L : d.getFileSize(),
                d.getPageCount(),
                d.getChunkCount(),
                d.getStatus(),
                d.getErrorMsg(),
                d.getCreatedAt() == null ? null : d.getCreatedAt().toString(),
                d.getUpdatedAt() == null ? null : d.getUpdatedAt().toString());
    }
}
