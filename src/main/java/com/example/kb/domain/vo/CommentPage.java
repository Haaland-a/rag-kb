package com.example.kb.domain.vo;

import java.util.List;

/**
 * 留言分页结果。
 *
 * @param items   当前页
 * @param total   总数（按当前视角过滤后的）
 * @param limit   每页条数
 * @param offset  偏移
 * @param notice  给提交者看的提示。只在提交接口返回，列表接口为 null
 */
public record CommentPage(
        List<CommentDto> items,
        long total,
        int limit,
        int offset,
        String notice) {

    public static CommentPage of(List<CommentDto> items, long total, int limit, int offset) {
        return new CommentPage(items, total, limit, offset, null);
    }
}
