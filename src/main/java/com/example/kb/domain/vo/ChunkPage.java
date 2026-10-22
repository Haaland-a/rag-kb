package com.example.kb.domain.vo;

import java.util.List;

/**
 * 切块分页结果（{@code GET /api/documents/{id}/chunks} 的 data）。
 *
 * <p>⚠️ 原来返回 {@code Map.of("items", ..., "total", ..., "page", ..., "size", ...)}，
 * 前端取 {@code res.data.items} 或 {@code res.items} 全靠约定，写错一个字母
 * 就是一片空白。换成 record 后字段名是编译期契约。</p>
 *
 * @param items 当前页的 chunk（已转 DTO，不含内部字段）
 * @param total 总块数（前端算总页数）
 * @param page  当前页码（从 1 开始）
 * @param size  每页条数
 */
public record ChunkPage(List<ChunkDto> items, long total, int page, int size) {
}
