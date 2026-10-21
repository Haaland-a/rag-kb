package com.example.kb.dto.media;

import com.example.kb.dto.comment.CommentDto;

import java.util.List;

/**
 * 单条动态的详情页数据：动态本体 + 它下面访客可见的全部留言。
 *
 * <p>为什么不直接复用 {@code /api/moments/public} 的列表项再让前端多调一次
 * {@code /api/comments/public/moments/{id}}：那样进详情页要打两个请求，
 * 而且中间会有一帧「动态已渲染、留言区空白」的闪烁。详情页是一个页面，
 * 一个请求拿齐是更自然的边界。</p>
 *
 * @param moment       动态本体（访客视角，不含评论数/同步状态等站主信息）
 * @param comments     访客可见的留言（含作者自己发的、被屏蔽但属于他的那几条）
 * @param commentTotal 访客可见的留言总数
 */
public record MomentDetail(MomentDto moment,
                           List<CommentDto> comments,
                           long commentTotal) {
}
