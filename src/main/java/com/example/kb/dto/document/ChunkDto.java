package com.example.kb.dto.document;

import com.example.kb.domain.KbChunk;

/**
 * 切块响应（详情页表格用）。
 *
 * <p>⚠️ 为什么不直接返回 {@link KbChunk}：实体上有 {@code filename} 字段
 * 是「内存载体不落库」的冗余字段，还有 {@code contentHash} 是内部字段。
 * 直接返回会让前端看到一个跟当前视图无关的 filename（每个块都带一遍文件名），
 * 既浪费带宽又容易让人误以为那是「块所属文件」以外的含义。</p>
 *
 * @param id          chunk 主键
 * @param chunkIndex  在文档内的序号（从 0 开始连续）
 * @param pageNumber  所在页码（PDF 才有）
 * @param headingPath 标题路径，如「第三章 &gt; 3.2 部署」
 * @param content     块正文
 * @param tokenCount  估算 token 数
 * @param vectorId    向量表里的 UUID（前 8 位给前端展示，完整值做 tooltip）
 */
public record ChunkDto(long id,
                       int chunkIndex,
                       Integer pageNumber,
                       String headingPath,
                       String content,
                       int tokenCount,
                       String vectorId) {

    public static ChunkDto of(KbChunk c) {
        return new ChunkDto(
                c.getId(),
                c.getChunkIndex(),
                c.getPageNumber(),
                c.getHeadingPath(),
                c.getContent(),
                c.getTokenCount(),
                c.getVectorId());
    }
}
