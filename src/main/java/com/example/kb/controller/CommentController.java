package com.example.kb.controller;

import com.example.kb.common.R;
import com.example.kb.domain.vo.CommentDto;
import com.example.kb.domain.dto.CommentRequests;
import com.example.kb.infrastructure.geo.ClientIpResolver;
import com.example.kb.service.ICommentService;
import com.example.kb.service.IVisitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 留言板 + 动态评论接口。
 *
 * <h3>路径分成两组，公开的带 {@code /public/} 分段</h3>
 * <pre>
 * 访客可用（AuthInterceptor 白名单）：
 *   GET  /api/comments/public                留言板列表
 *   POST /api/comments/public                提交留言（游客也能发）
 *   GET  /api/comments/public/moments/{id}   某条动态下的评论
 *   POST /api/comments/public/moments/{id}   给某条动态留言
 *
 * 需要登录（站主，AuthInterceptor 的 ADMIN_PATHS 拦）：
 *   GET    /api/comments                      后台列表（含悄悄话、含已屏蔽）
 *   PUT    /api/comments/{id}                 改自己的留言
 *   DELETE /api/comments/{id}                 删自己的留言
 *   PUT    /api/comments/{id}/status          屏蔽 / 恢复
 *   GET    /api/comments/unread               未读数
 *   POST   /api/comments/read                 标记已读
 * </pre>
 *
 * <p>⚠️ 公开端点虽然叫 public，但<b>不是无差别返全量</b>：列表接口在
 * Service 里按「看的人是谁」过滤（游客看不到悄悄话，也看不到别人被屏蔽的
 * 留言）。白名单只管「不用登录」，可见性由业务层负责，两件事不要混。</p>
 *
 * <h3>为什么「修改/删除」路径不带 /public/</h3>
 * <p>游客改不了自己发的留言（没有稳定身份，无法证明归属）。只有注册用户
 * 能改，而注册用户天然带着 token，所以走需要登录的那组。这样白名单里少一条，
 * 也少一条被误放行的可能。</p>
 */
@RestController
@RequestMapping("/api/comments")
public class CommentController {

    private final ICommentService commentService;
    private final ClientIpResolver ipResolver;
    private final IVisitService visitService;

    public CommentController(ICommentService commentService,
                             ClientIpResolver ipResolver,
                             IVisitService visitService) {
        this.commentService = commentService;
        this.ipResolver = ipResolver;
        this.visitService = visitService;
    }

    // ==================== 访客可用的部分 ====================

    /**
     * 留言板列表。
     *
     * <p>⚠️ 带 token 访问时能看到「自己写的被屏蔽的留言」，不带 token 只能看
     * 公开且正常的。所以这个端点虽然免登录，但<b>要读一下身份</b> ——
     * 这正是 AuthInterceptor 先认人、再判路径的设计。</p>
     */
    @GetMapping("/public")
    public R board(HttpServletRequest request,
                   @RequestParam(defaultValue = "20") int limit,
                   @RequestParam(defaultValue = "0") int offset) {
        return R.ok(commentService.board(
                currentUserId(request), limit, offset, isAdmin(request)));
    }

    /** 某条动态下的评论 */
    @GetMapping("/public/moments/{momentId}")
    public R momentComments(@PathVariable long momentId, HttpServletRequest request) {
        List<CommentDto> items = commentService.momentComments(
                momentId, currentUserId(request), isAdmin(request));
        return R.ok(items);
    }

    /** 给留言板留言 */
    @PostMapping("/public")
    public R submitToBoard(HttpServletRequest request,
                           @Valid @RequestBody CommentRequests.Submit body) {
        return R.ok(doSubmit(null, request, body));
    }

    /** 给某条动态留言 */
    @PostMapping("/public/moments/{momentId}")
    public R submitToMoment(@PathVariable long momentId, HttpServletRequest request,
                            @Valid @RequestBody CommentRequests.Submit body) {
        return R.ok(doSubmit(momentId, request, body));
    }

    // ==================== 需要登录的部分 ====================

    /**
     * 后台列表：含悄悄话、含已屏蔽、含游客留言。
     *
     * @param scope all / board / moment 三选一，不传等价于 all。
     *              ⚠️ 曾经是布尔 {@code boardOnly}，只能表达「留言板」和「不是留言板」，
     *              没法表达「只要动态评论」，前端只能拉全量再 filter，分页和 total 都对不上
     */
    @GetMapping
    public R adminList(HttpServletRequest request,
                       @RequestParam(defaultValue = "20") int limit,
                       @RequestParam(defaultValue = "0") int offset,
                       @RequestParam(required = false) Boolean unreadOnly,
                       @RequestParam(required = false) String scope) {
        requireAdmin(request);
        return R.ok(commentService.adminList(limit, offset, unreadOnly, scope));
    }

    /** 改自己的留言。站主可以改任何一条（用于修掉明显不合适的内容） */
    @PutMapping("/{id}")
    public R edit(@PathVariable long id, HttpServletRequest request,
                  @Valid @RequestBody CommentRequests.Edit body) {
        requireLogin(request);
        commentService.edit(id, requireUserId(request), isAdmin(request), body.getContent());
        return R.ok(null);
    }

    /** 删自己的留言。站主能删任何一条 */
    @DeleteMapping("/{id}")
    public R delete(@PathVariable long id, HttpServletRequest request) {
        requireLogin(request);
        commentService.delete(id, requireUserId(request), isAdmin(request));
        return R.ok(null);
    }

    /**
     * 站主屏蔽 / 恢复一条留言。
     *
     * <p>⚠️ 必须是站主而不是普通用户：屏蔽是内容治理动作，
     * 让留言者自己屏蔽自己的留言没有意义（那叫删除）。</p>
     */
    @PutMapping("/{id}/status")
    public R setStatus(@PathVariable long id, HttpServletRequest request,
                       @Valid @RequestBody CommentRequests.StatusChange body) {
        requireAdmin(request);
        commentService.setStatus(id, body.getStatus());
        return R.ok(null);
    }

    @GetMapping("/unread")
    public R unread(HttpServletRequest request) {
        requireAdmin(request);
        return R.ok(Map.of("unread", commentService.unreadCount()));
    }

    /** 标记已读。不传 id 表示全部已读 */
    @PostMapping("/read")
    public R markRead(HttpServletRequest request,
                      @RequestParam(required = false) Long id) {
        requireAdmin(request);
        int n = commentService.markRead(id);
        return R.ok(Map.of("updated", n));
    }

    // ==================== 内部 ====================

    private ICommentService.SubmitResult doSubmit(Long momentId, HttpServletRequest request,
                                                 CommentRequests.Submit body) {
        String ip = ipResolver.resolve(request, visitService.isTrustProxy());
        return commentService.submit(momentId, body,
                currentUserId(request), currentUsername(request), ip);
    }

    private static Long currentUserId(HttpServletRequest request) {
        Object attr = request.getAttribute("userId");
        return attr instanceof Long id ? id : null;
    }

    private static String currentUsername(HttpServletRequest request) {
        Object attr = request.getAttribute("username");
        return attr instanceof String s ? s : null;
    }

    /**
     * 是不是站主。
     *
     * <p>⚠️ 直接读拦截器挂上去的 {@code admin} attribute，不再查库也不比对
     * 用户名。判断逻辑集中在 {@link com.example.kb.config.AuthInterceptor} 一处，
     * 这里只消费结论 —— 否则「谁是站主」这件事会在五个 Controller 里
     * 各写一遍，改规则时漏一处就是权限漏洞。</p>
     */
    private static boolean isAdmin(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute("admin"));
    }

    private static void requireAdmin(HttpServletRequest request) {
        requireLogin(request);
        if (!isAdmin(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有站主能做这个操作");
        }
    }

    private static void requireLogin(HttpServletRequest request) {
        if (!Boolean.TRUE.equals(request.getAttribute("authenticated"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要先登录");
        }
    }

    private static long requireUserId(HttpServletRequest request) {
        Object attr = request.getAttribute("userId");
        if (attr instanceof Long id) {
            return id;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要先登录");
    }
}
