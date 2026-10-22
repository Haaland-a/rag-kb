package com.example.kb.domain.vo;

/**
 * 分页包装。
 *
 * <p>为什么不用「一个 Map 装 items/total」：字段名拼错编译期发现不了。
 * 这个项目里已经有 {@code ChunkPage} / {@code VisitPage} 两个同款，
 * 模式统一比省一个文件重要。</p>
 *
 * @param items 当前页数据
 * @param total 总条数
 * @param limit 本页上限（服务端夹过边界后的值，不是前端传来的原始值）
 * @param offset 偏移量（同上）
 */
public record MomentPage(java.util.List<MomentDto> items, long total, int limit, int offset) {
}
