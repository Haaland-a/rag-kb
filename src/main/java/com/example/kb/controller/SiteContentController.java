package com.example.kb.controller;

import com.example.kb.common.R;
import com.example.kb.dto.comment.CommentDto;
import com.example.kb.dto.comment.CommentPage;
import com.example.kb.dto.comment.CommentRequests;
import com.example.kb.dto.media.MomentDetail;
import com.example.kb.dto.media.MomentDto;
import com.example.kb.dto.media.MomentPage;
import com.example.kb.dto.media.SiteContentRequests;
import com.example.kb.dto.site.ProfileDto;
import com.example.kb.infrastructure.geo.ClientIpResolver;
import com.example.kb.repository.KbCommentRepository;
import com.example.kb.service.CommentService;
import com.example.kb.service.MediaService;
import com.example.kb.service.MomentService;
import com.example.kb.service.ProfileService;
import com.example.kb.service.VisitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 「关于我」和「日常动态」的接口层。
 *
 * <h2>路径设计</h2>
 *
 * <p>公开可见的走 {@code /api/moments/public/**} —— 这个前缀在
 * {@code AuthInterceptor.PUBLIC_PREFIXES} 里，匿名可访问。</p>
 *
 * <p>站主专属的走其余路径，{@link com.example.kb.config.AuthInterceptor}
 * 不会自动拦（{@code /api/moments} 不在 ADMIN_PATHS 里），所以这里
 * <b>每个站主端点都必须自己检查 {@code authenticated} attribute</b>。
 * ⚠️ 这是刻意选择的：整块前缀拦截和单端点检查二选一，前者容易漏配
 * 某个子路径（比如 upload），后者漏检查会直接暴露数据。
 * 为了不让「漏检查」这种失误发生，所有站主端点统一调用
 * {@link #requireAdmin(HttpServletRequest)}，它在未登录时直接抛异常。</p>
 *
 * <p>另外 {@code /api/site/profile} 放在了 ADMIN_PATHS 的 {@code /api/site}
 * 前缀下吗？没有 —— {@code /api/site} 不在 ADMIN_PATHS 里（主页要匿名可读）。
 * 所以 profile 的 GET/PUT 也走同样的显式检查。</p>
 */
@RestController
@RequestMapping("/api")
public class SiteContentController {

    private static final Logger log = LoggerFactory.getLogger(SiteContentController.class);

    /** 动态列表默认分页 */
    private static final int DEFAULT_LIMIT = 20;

    private final ProfileService profileService;
    private final MomentService momentService;
    private final MediaService mediaService;
    private final CommentService commentService;
    private final VisitService visitService;
    private final ClientIpResolver ipResolver;

    public SiteContentController(ProfileService profileService,
                                 MomentService momentService,
                                 MediaService mediaService,
                                 CommentService commentService,
                                 VisitService visitService,
                                 ClientIpResolver ipResolver) {
        this.profileService = profileService;
        this.momentService = momentService;
        this.mediaService = mediaService;
        this.commentService = commentService;
        this.visitService = visitService;
        this.ipResolver = ipResolver;
    }

    // ==================== 关于我 ====================

    /**
     * 站主视角读「关于我」（含 {@code fromConfig} 标记，前端据此提示
     * 「当前用的是配置文件里的默认内容」）。
     */
    @GetMapping("/site/profile")
    public R profile(HttpServletRequest request) {
        requireAdmin(request);
        return R.ok(profileService.resolve());
    }

    /** 保存「关于我」—— 整块覆盖 */
    @PutMapping("/site/profile")
    public R saveProfile(@Valid @RequestBody SiteContentRequests.ProfileSave req,
                         HttpServletRequest request) {
        requireAdmin(request);
        List<ProfileService.LinkInputLike> links = req.getLinks() == null ? List.of()
                : req.getLinks().stream()
                        .map(l -> new ProfileService.LinkInputLike(l.getLabel(), l.getUrl()))
                        .toList();
        ProfileDto saved = profileService.save(req.getOwner(), req.getAvatarText(),
                req.getAvatarUrl(), req.getTagline(), req.getIntro(), req.getAbout(),
                links, req.getTags());
        return R.ok(saved);
    }

    /** 恢复成配置文件里的默认内容（删掉数据库里那行） */
    @PostMapping("/site/profile/reset")
    public R resetProfile(HttpServletRequest request) {
        requireAdmin(request);
        return R.ok(profileService.resetToConfig());
    }

    /**
     * 上传头像图片，返回 {@code {url}}。
     *
     * <p>为什么单独一个端点而不是复用 {@code /api/media/photos}：
     * 头像不是照片墙的一部分，不能出现在相册里，删照片墙也不能删它。
     * 走的存储目录也不同（{@code avatars/}）。</p>
     *
     * <p>前端拿到 url 之后要自己调 {@code PUT /api/site/profile} 把它存下来 ——
     * 这里只负责「把文件放上去」，不顺手改数据库。理由是站主可能传完发现
     * 不好看想换一张，如果上传即生效，中间那几张都会短暂出现在主页上。</p>
     */
    @PostMapping("/site/profile/avatar")
    public R uploadAvatar(@RequestPart("file") MultipartFile file, HttpServletRequest request) {
        requireAdmin(request);
        try {
            String url = mediaService.uploadAvatar(file);
            return R.ok(java.util.Map.of("url", url));
        }
        catch (java.io.IOException e) {
            log.error("头像上传失败", e);
            throw new IllegalStateException("头像上传失败：" + e.getMessage());
        }
    }

    // ==================== 日常动态：访客 ====================

    /**
     * 访客视角的动态流（主页「最近的日常」和 {@code /moments} 页都用它）。
     *
     * <p>⚠️ 返回的 {@link MomentDto} 里 {@code comments} 恒为 0 且
     * {@code synced}/{@code visible} 都是固定值 —— 服务端根本不查这些站主字段，
     * 不是前端藏起来。</p>
     */
    @GetMapping("/moments/public")
    public R publicMoments(HttpServletRequest request) {
        return R.ok(momentService.listForVisitor());
    }

    /**
     * 单条动态的详情页数据（动态 + 它下面访客可见的全部留言）。
     *
     * <p>为什么要有这个接口：主页和列表页只给每条动态几条留言预览，
     * 想看全部就得进详情页；详情页如果复用列表项再单独调留言接口，
     * 会多一次往返，还会闪一下空留言区。</p>
     *
     * <p>⚠️ 动态不存在 / 被设为「仅自己可见」/ 已删 —— 三种情况统一 404，
     * 不区分。区分了等于告诉外面「这个 id 存在但被藏了」。</p>
     */
    @GetMapping("/moments/public/{id}")
    public R publicMoment(@PathVariable long id, HttpServletRequest request) {
        return momentService.findForVisitor(id, viewerId(request))
                .<R>map(R::ok)
                .orElseGet(() -> {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "这条动态不存在或已删除");
                });
    }

    /**
     * 访客给某条动态留言。
     *
     * <p>⚠️ 这个端点保留只为兼容旧前端，真正的实现已统一到
     * {@link CommentController} 的 {@code POST /api/comments/public/moments/{id}}。
     * 两边都走 {@link CommentService#submit}，可见性/屏蔽/昵称覆盖的规则只有一份。</p>
     *
     * @deprecated 用 {@code POST /api/comments/public/moments/{id}}
     */
    @Deprecated
    @PostMapping("/moments/public/{id}/comments")
    public R addComment(@PathVariable long id,
                        @Valid @RequestBody CommentRequests.Submit req,
                        HttpServletRequest request) {
        String ip = ipResolver.resolve(request, visitService.isTrustProxy());
        Long viewerId = viewerId(request);
        CommentService.SubmitResult result = commentService.submit(id, req, viewerId, username(request), ip);
        return R.ok(java.util.Map.of("notice", result.notice()));
    }

    // ==================== 日常动态：站主 ====================

    /** 站主视角的动态流（含隐藏、含评论数、含是否已同步照片墙） */
    @GetMapping("/moments")
    public R adminMoments(@RequestParam(defaultValue = "0") int offset,
                          @RequestParam(defaultValue = "20") int limit,
                          HttpServletRequest request) {
        requireAdmin(request);
        MomentPage page = momentService.listForAdmin(
                limit <= 0 ? DEFAULT_LIMIT : limit, offset);
        return R.ok(page);
    }

    /**
     * 上传动态的图片或视频，返回 URL。
     *
     * <p>⚠️ 用 {@code @RequestPart("file")} 而不是 {@code @RequestParam}：
     * multipart 里参数名的解析方式不同，前者对 {@code <input type=file>}
     * 的常见形态更稳。</p>
     */
    @PostMapping("/moments/upload")
    public R upload(@RequestPart("file") MultipartFile file, HttpServletRequest request) {
        requireAdmin(request);
        MomentService.UploadedMedia media = momentService.uploadMedia(file);
        return R.ok(media);
    }

    /** 发布动态（{@code syncToPhotos} 在 body 里） */
    @PostMapping("/moments")
    public R createMoment(@Valid @RequestBody SiteContentRequests.MomentSave req,
                          HttpServletRequest request) {
        requireAdmin(request);
        boolean sync = Boolean.TRUE.equals(req.getSyncToPhotos());
        MomentDto created = momentService.create(req, sync);
        return R.ok(created);
    }

    /** 改文字和可见性（不支持改媒体） */
    @PutMapping("/moments/{id}")
    public R updateMoment(@PathVariable long id,
                          @Valid @RequestBody SiteContentRequests.MomentSave req,
                          HttpServletRequest request) {
        requireAdmin(request);
        momentService.update(id, req.getContent(), req.getVisible());
        return R.ok();
    }

    /** 把已有的一条动态补发到照片墙 */
    @PostMapping("/moments/{id}/sync-to-photos")
    public R syncToPhotos(@PathVariable long id,
                          @RequestBody(required = false) SiteContentRequests.MomentSave req,
                          HttpServletRequest request) {
        requireAdmin(request);
        String caption = req == null ? null : req.getPhotoCaption();
        long photoId = momentService.syncToPhotos(id, caption);
        return R.ok(java.util.Map.of("photoId", photoId));
    }

    @DeleteMapping("/moments/{id}")
    public R deleteMoment(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        momentService.delete(id);
        return R.ok();
    }

    // ==================== 访客留言：站主看 ====================
    //
    // ⚠️ 这一组三个端点是早期实现留下的，功能已经被 CommentController 覆盖
    //   （GET /api/comments、GET /api/comments/unread、POST /api/comments/read）。
    //   之所以不直接删：前端还有两处在调，删掉要么同步改前端要么留个 404 坑。
    //   处理方式是「保留路径 + 全部转调 CommentService」——
    //   两条路径共用一份实现，就不存在「改了一处忘另一处」的漂移。

    /**
     * 某条动态下的留言（站主看，含悄悄话与已屏蔽）。
     *
     * @deprecated 用 {@code GET /api/comments?...} 带 scope 参数
     */
    @Deprecated
    @GetMapping("/moments/{id}/comments")
    public R commentsOf(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        List<CommentDto> items = commentService.momentComments(id, null, true);
        return R.ok(items);
    }

    /**
     * 全站留言流（默认只看未读）。
     *
     * @deprecated 用 {@code GET /api/comments}
     */
    @Deprecated
    @GetMapping("/moments/comments")
    public R allComments(@RequestParam(defaultValue = "true") boolean unreadOnly,
                         @RequestParam(defaultValue = "0") int offset,
                         @RequestParam(defaultValue = "20") int limit,
                         HttpServletRequest request) {
        requireAdmin(request);
        CommentPage page = commentService.adminList(
                limit <= 0 ? DEFAULT_LIMIT : limit, offset, unreadOnly,
                KbCommentRepository.SCOPE_MOMENT);
        return R.ok(page);
    }

    /**
     * 标记留言已读。
     *
     * <p>⚠️ 用 POST 不是 PUT：这个动作有副作用且不幂等语义
     * （重复标记结果一样，但它是「执行一次操作」不是「把资源改成某状态」），
     * 团队习惯不同，这里选 POST 只是为了让前端少写一个方法名。</p>
     *
     * @deprecated 用 {@code POST /api/comments/read}
     */
    @Deprecated
    @PostMapping("/moments/comments/read")
    public R markRead(@RequestParam(required = false) Long id, HttpServletRequest request) {
        requireAdmin(request);
        int n = commentService.markRead(id);
        return R.ok(java.util.Map.of("updated", n));
    }

    /**
     * 未读数，后台导航小红点用。
     *
     * @deprecated 用 {@code GET /api/comments/unread}
     */
    @Deprecated
    @GetMapping("/moments/comments/unread")
    public R unread(HttpServletRequest request) {
        requireAdmin(request);
        return R.ok(java.util.Map.of("unread", commentService.unreadCount()));
    }

    // ==================== 内部 ====================

    /**
     * 站主断言。
     *
     * <p>⚠️ 抛 {@code IllegalArgumentException} 而不是自己写 401 响应，
     * 是因为全局异常处理器把它映射成 400 —— 语义上不太对。
     * 这里改为抛 {@code ResponseStatusException(401)}，让 Spring 直接给
     * 正确的状态码，前端拦截器也按 401 处理登出。</p>
     */
    private static void requireAdmin(HttpServletRequest request) {
        Object authenticated = request.getAttribute("authenticated");
        if (!Boolean.TRUE.equals(authenticated)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "需要先登录");
        }
    }

    /** 当前登录用户的 id，游客返回 null（留言板据此区分「自己的留言」） */
    private static Long viewerId(HttpServletRequest request) {
        Object id = request.getAttribute("userId");
        return id instanceof Long v ? v : null;
    }

    /** 当前登录用户名，游客返回 null（服务端据此覆盖昵称，防止顶别人名字发言） */
    private static String username(HttpServletRequest request) {
        Object name = request.getAttribute("username");
        return name instanceof String v ? v : null;
    }
}
