package com.example.kb.dto.comment;

/**
 * 一条留言的对外视图。
 *
 * <h3>⚠️ 字段是按「看的人是谁」裁剪的，同一行数据可能变出三种 DTO</h3>
 * <table border="1">
 *   <caption>可见性规则</caption>
 *   <tr><th>看的人</th><th>能看到什么</th></tr>
 *   <tr><td>游客</td><td>PUBLIC + VISIBLE 的留言。看不到悄悄话，看不到被屏蔽的</td></tr>
 *   <tr><td>留言作者</td><td>上面那些 + 自己写的（含被屏蔽的，会看到「已被站主隐藏」标记）</td></tr>
 *   <tr><td>站主</td><td>全部，包括悄悄话和所有人的被屏蔽留言</td></tr>
 * </table>
 *
 * <p>所以 {@code hidden} 和 {@code privateNote} 这两个字段只在特定视角下有意义，
 * 其他视角恒为 false / null。把它们留着而不是分两个 DTO：前端渲染逻辑
 * 只需要一套，少写一遍模板。</p>
 *
 * @param id       留言 id
 * @param momentId 挂在哪条动态下；null 表示留言板
 * @param nickname 显示名。游客是自填昵称或「匿名访客」，注册用户是账号昵称
 * @param content  正文
 * @param author    作者信息，见 {@link Author}
 * @param mine      是不是「正在看的人」自己写的 —— 前端靠它决定显不显示编辑/删除
 * @param hidden    被站主屏蔽了吗（只有作者本人和站主会看到 true）
 * @param read      站主视角下：这条读过没有。给访客的视角恒为 true（跟他无关）
 * @param visibility PUBLIC / PRIVATE（悄悄话）。只有站主看到的视角下才给出真实值
 * @param createdAt 发布时间（服务器时区）
 * @param replies   一层回复。不做楼中楼
 */
public record CommentDto(
        long id,
        Long momentId,
        String nickname,
        String content,
        Author author,
        boolean mine,
        boolean hidden,
        boolean read,
        String visibility,
        String createdAt,
        java.util.List<CommentDto> replies) {

    /**
     * 作者信息。
     *
     * <p>⚠️ 游客的 {@code userId} 为 null，且<b>不返回 IP</b>：
     * 公开别人的 IP 是隐私问题。IP 只给站主在后台看。</p>
     *
     * @param userId   注册用户 id；null = 游客
     * @param username 登录名；游客为 null
     * @param registered 是否注册用户 —— 前端据此显示「注册用户」标签
     */
    public record Author(Long userId, String username, boolean registered) {
    }
}
