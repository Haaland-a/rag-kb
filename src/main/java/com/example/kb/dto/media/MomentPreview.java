package com.example.kb.dto.media;

import com.example.kb.dto.comment.CommentDto;

import java.util.List;

/**
 * 主页上一条动态的预览：动态本体 + 前几条公开留言 + 留言总数。
 *
 * <p>为什么「预览留言」和「留言总数」要一起返回，而不是让前端拿个数自己去查：
 * 主页一次展示 20 条动态，如果每条都要单独请求留言，就是 20 个额外请求，
 * 4G 网络下首屏会明显卡顿。服务端一次批量查完（一条 SQL 带窗口函数）
 * 再分发到各条动态上，代价恒定。</p>
 *
 * <p>⚠️ {@code commentTotal} 和 {@code comments.size()} 通常不相等 ——
 * 前者是全量总数（角标用），后者只取前几条（{@code HOME_PREVIEW_LIMIT}）。
 * 前端不要用 {@code comments.length} 当角标，否则会出现
 * 「角标写 3、点进去却有 12 条」的错觉。</p>
 *
 * @param moment       动态本体（访客视角）
 * @param comments     前几条访客可见留言
 * @param commentTotal 访客可见留言总数
 */
public record MomentPreview(MomentDto moment,
                            List<CommentDto> comments,
                            long commentTotal) {
}
