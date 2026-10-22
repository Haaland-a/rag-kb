package com.example.kb.service.impl;

import com.example.kb.common.TimeFormat;
import com.example.kb.domain.dto.CommentRequests;
import com.example.kb.domain.po.KbComment;
import com.example.kb.domain.po.KbMoment;
import com.example.kb.domain.vo.CommentDto;
import com.example.kb.domain.vo.CommentPage;
import com.example.kb.mapper.KbCommentMapper;
import com.example.kb.mapper.KbMomentMapper;
import com.example.kb.mapper.KbUserMapper;
import com.example.kb.service.IAuthService;
import com.example.kb.service.ICommentService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CommentServiceImpl implements ICommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentServiceImpl.class);

    private static final int MAX_PAGE_LIMIT = 50;
    private static final int DEFAULT_PAGE_LIMIT = 20;
    /** 一条留言下最多展示多少回复。个人站的留言板不需要无限翻 */
    private static final int MAX_REPLIES = 30;

    /** 游客提交时的提示语。前后端共用一句话，避免两处措辞不一致 */
    public static final String GUEST_NOTICE =
            "留言已收到。这是匿名留言，发布后不能修改、不能删除，你也不会再找回它 —— "
                    + "如果希望之后还能编辑，请先注册。";
    /** 注册用户发公开留言 */
    public static final String USER_PUBLIC_NOTICE = "留言已发布，其他人可以看到。";
    /** 注册用户发悄悄话 */
    public static final String USER_PRIVATE_NOTICE =
            "悄悄话已发出，只有站主能看到，其他人（包括留言列表里）都看不到。";

    private final KbCommentMapper commentMapper;
    private final KbMomentMapper momentMapper;
    private final KbUserMapper userMapper;
    private final IAuthService authService;

    public CommentServiceImpl(KbCommentMapper commentMapper,
                          KbMomentMapper momentMapper,
                          KbUserMapper userMapper,
                          IAuthService authService) {
        this.commentMapper = commentMapper;
        this.momentMapper = momentMapper;
        this.userMapper = userMapper;
        this.authService = authService;
    }

    // ==================== 浏览 ====================

    /**
     * 留言板列表（{@code momentId == null} 的那些）。
     *
     * @param viewerId 看的人。null = 游客。**这个参数是可见性规则的一部分，
     *                 不是可选优化** —— 少了它，「被屏蔽的留言作者仍可见」就失效
     */
    public CommentPage board(Long viewerId, int limit, int offset, boolean adminView) {
        int safeLimit = clampLimit(limit);
        int safeOffset = Math.max(0, offset);

        if (adminView) {
            List<KbComment> rows = commentMapper.findAllForAdmin(
                    safeLimit, safeOffset, null, KbCommentMapper.SCOPE_BOARD);
            long total = commentMapper.countForAdmin(null, KbCommentMapper.SCOPE_BOARD);
            return CommentPage.of(toDtos(rows, null, true), total, safeLimit, safeOffset);
        }
        List<KbComment> rows = commentMapper.findBoardForVisitor(viewerId, safeLimit, safeOffset);
        long total = commentMapper.countBoardForVisitor(vid(viewerId));
        return CommentPage.of(toDtos(rows, viewerId, false), total, safeLimit, safeOffset);
    }

    /** 某条动态下的评论 */
    public List<CommentDto> momentComments(long momentId, Long viewerId, boolean adminView) {
        List<KbComment> rows;
        if (adminView) {
            rows = commentMapper.findMomentCommentsForAdmin(momentId, MAX_REPLIES);
        }
        else {
            rows = commentMapper.findMomentCommentsForVisitor(momentId, viewerId, MAX_REPLIES);
        }
        return toDtos(rows, viewerId, adminView);
    }

    /**
     * 一批动态各自的留言预览（主页用）。
     *
     * <p>⚠️ 和 {@link #momentComments} 的区别不只是「批量」：这里
     * <b>只走访客视角</b>（没有 adminView 分支）。主页是公开页面，
     * 站主自己打开主页看到的也该是公开视图 —— 悄悄话不该在主页上冒出来，
     * 那是后台留言管理的事。</p>
     *
     * @param momentIds 要预览的动态 id
     * @param viewerId  看的人（null = 游客），决定「自己发的被屏蔽留言」是否可见
     * @param perMoment 每条动态最多预览几条
     * @return momentId → 留言 DTO 列表（没有留言的动态不在 map 里）
     */
    public Map<Long, List<CommentDto>> previewsForMoments(List<Long> momentIds, Long viewerId,
                                                          int perMoment) {
        List<KbComment> rows = commentMapper.findVisiblePreviews(momentIds, viewerId, perMoment);
        // 按 momentId 分组。用 LinkedHashMap 保持 SQL 里的顺序（老留言在前）
        Map<Long, List<KbComment>> grouped = new LinkedHashMap<>();
        for (KbComment c : rows) {
            if (c.getMomentId() == null) {
                continue;
            }
            grouped.computeIfAbsent(c.getMomentId(), k -> new ArrayList<>()).add(c);
        }

        Map<Long, List<CommentDto>> out = new LinkedHashMap<>();
        for (Map.Entry<Long, List<KbComment>> e : grouped.entrySet()) {
            // adminView 恒 false：主页永远是访客视角
            out.put(e.getKey(), toDtos(e.getValue(), viewerId, false));
        }
        return out;
    }

    /**
     * 一批动态各自的访客可见留言总数（角标用）。
     *
     * <p>⚠️ 必须和 {@link #previewsForMoments} 用同一套可见性条件，
     * 否则会出现「角标写 5 条，展开只看到 2 条」。两边共用
     * {@code KbCommentMapper} 里同一段 SQL 片段就是这个原因。</p>
     */
    public Map<Long, Long> visibleCountsForMoments(List<Long> momentIds, Long viewerId) {
        return commentMapper.countVisibleByMoments(momentIds, viewerId);
    }

    /** 单条动态下访客可见的留言总数（详情页用，和列表同条件） */
    public long visibleCountForMoment(long momentId, Long viewerId) {
        return commentMapper.countVisibleForMoment(momentId, vid(viewerId));
    }

    // ==================== 提交 ====================

    /**
     * 提交结果。
     *
     * <p>⚠️ 必须把 {@code id} 一起返回：注册用户发完留言后要能立刻编辑/删除
     * 自己那条，没有 id 就只能整页刷新再从头找（游客不需要 id，但也无副作用）。</p>
     *
     * <p>游客的 {@code id} 也返回，但前端不会用它 —— 匿名留言改不了删不了，
     * 这是设计而不是遗漏。</p>
     *
     * @param id     新留言 id
     * @param notice 提示语（告诉留言的人「这条谁会看到」「能不能改」）
     * @param mine   是不是自己名下可编辑的留言（游客恒 false）
     */

    /**
     * 提交一条留言/评论。
     *
     * @param momentId 挂在哪条动态下；null = 留言板
     * @param viewerId 已登录用户的 id；null = 游客
     * @param username 已登录用户名；null = 游客
     * @return 新留言 id + 提示语
     */
    public SubmitResult submit(Long momentId, CommentRequests.Submit req,
                               Long viewerId, String username, String ip) {
        String content = req.getContent() == null ? "" : req.getContent().trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("留言内容不能为空");
        }
        if (content.length() > 1000) {
            throw new IllegalArgumentException("留言最长 1000 字");
        }

        // 挂动态的留言：动态必须存在且公开，否则等于往一个看不见的地方留言
        if (momentId != null) {
            Optional<KbMoment> moment = momentMapper.findById(momentId);
            if (moment.isEmpty() || !moment.get().isVisible()) {
                throw new IllegalArgumentException("这条动态不存在或已删除");
            }
        }

        // 回复关系校验：父留言必须存在，且和自己在同一个上下文（同一动态或同在留言板）
        if (req.getParentId() != null) {
            Optional<KbComment> parent = commentMapper.findById(req.getParentId());
            if (parent.isEmpty()) {
                throw new IllegalArgumentException("要回复的那条留言不存在");
            }
            Long parentMoment = parent.get().getMomentId();
            boolean sameContext = momentId == null
                    ? parentMoment == null
                    : momentId.equals(parentMoment);
            if (!sameContext) {
                throw new IllegalArgumentException("只能回复同一区域下的留言");
            }
        }

        KbComment c = new KbComment();
        c.setMomentId(momentId);
        c.setContent(content);
        c.setIp(ip);
        c.setDeviceId(blankToNull(req.getDeviceId()));
        c.setParentId(req.getParentId());

        String notice;
        if (viewerId == null) {
            // ---- 游客 ----
            c.setUserId(null);
            c.setNickname(nicknameOrDefault(req.getNickname()));
            // ⚠️ 游客不能发悄悄话：悄悄话的语义是「注册用户对站主说」，而游客
            // 本就无法被识别，说了也收不到回复。这个字段在这里被强制改回 PUBLIC，
            // 不信任前端传什么
            c.setVisibility(KbComment.VISIBILITY_PUBLIC);
            notice = GUEST_NOTICE;
        }
        else {
            // ---- 注册用户 ----
            c.setUserId(viewerId);
            // ⚠️ 忽略前端传的 nickname：否则任何人都能顶着别人的名字发言
            String display = userMapper.findById(viewerId)
                    .map(u -> u.getDisplayName() == null || u.getDisplayName().isBlank()
                            ? u.getUsername()
                            : u.getDisplayName())
                    .orElse("用户" + viewerId);
            c.setNickname(display);
            boolean wantPrivate = KbComment.VISIBILITY_PRIVATE
                    .equalsIgnoreCase(nullToEmpty(req.getVisibility()).trim());
            c.setVisibility(wantPrivate
                    ? KbComment.VISIBILITY_PRIVATE : KbComment.VISIBILITY_PUBLIC);
            notice = wantPrivate ? USER_PRIVATE_NOTICE : USER_PUBLIC_NOTICE;
        }

        // ⚠️ insert() 返回的是受影响行数（int），不是自增 id；id 由 MP 回填到实体上
        commentMapper.insert(c);
        long id = c.getId();
        log.info("新留言 id={} momentId={} userId={} visibility={} ip={}",
                id, momentId, viewerId, c.getVisibility(), ip);
        // mine 只在「有账号」时为 true：游客自己改不了，前端据此决定显示不显示编辑按钮
        return new SubmitResult(id, notice, viewerId != null);
    }

    // ==================== 编辑 / 删除 ====================

    /**
     * 作者改自己的留言。
     *
     * <p>⚠️ 游客的留言（user_id 为 null）永远改不了：没有稳定身份，
     * 无法证明「这条是你写的」。这正是游客提示里强调「发出去就不能改」的原因。</p>
     */
    public void edit(long commentId, long viewerId, boolean admin, String content) {
        KbComment c = commentMapper.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("留言不存在"));
        if (!admin && !isAuthor(c, viewerId)) {
            throw new IllegalStateException("只能改自己写的留言");
        }
        if (c.getUserId() == null) {
            throw new IllegalStateException("匿名留言不能修改");
        }
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("留言内容不能为空");
        }
        commentMapper.updateContent(commentId, text);
    }

    /**
     * 删除一条留言。
     *
     * <p>作者能删自己的，站主能删任何一条。游客的留言只能由站主删。</p>
     */
    public void delete(long commentId, long viewerId, boolean admin) {
        KbComment c = commentMapper.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("留言不存在"));
        if (!admin && !isAuthor(c, viewerId)) {
            throw new IllegalStateException("只能删自己写的留言");
        }
        commentMapper.deleteById(commentId);
    }

    /** 站主屏蔽 / 恢复 */
    public void setStatus(long commentId, String status) {
        String s = nullToEmpty(status).trim().toUpperCase();
        if (!KbComment.STATUS_VISIBLE.equals(s) && !KbComment.STATUS_HIDDEN.equals(s)) {
            throw new IllegalArgumentException("状态只能是 VISIBLE 或 HIDDEN");
        }
        if (commentMapper.updateStatus(commentId, s) == 0) {
            throw new IllegalArgumentException("留言不存在");
        }
        log.info("留言 {} 状态改为 {}", commentId, s);
    }

    // ==================== 后台 ====================

    /**
     * 站主后台的留言列表（全部可见性、全部状态）。
     *
     * @param scope all / board / moment，见 {@link KbCommentMapper#SCOPE_ALL}。
     *              传 null 或未知值等价于 all
     */
    public CommentPage adminList(int limit, int offset, Boolean unreadOnly, String scope) {
        int safeLimit = clampLimit(limit);
        int safeOffset = Math.max(0, offset);
        List<KbComment> rows = commentMapper.findAllForAdmin(
                safeLimit, safeOffset, unreadOnly, scope);
        long total = commentMapper.countForAdmin(unreadOnly, scope);
        return CommentPage.of(toDtos(rows, null, true), total, safeLimit, safeOffset);
    }

    public long unreadCount() {
        return commentMapper.countUnread();
    }

    public int markRead(Long id) {
        return commentMapper.markRead(id);
    }

    // ==================== 内部 ====================

    private static boolean isAuthor(KbComment c, long viewerId) {
        return c.getUserId() != null && c.getUserId() == viewerId;
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_PAGE_LIMIT;
        }
        return Math.min(limit, MAX_PAGE_LIMIT);
    }

    private static String nicknameOrDefault(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return KbComment.DEFAULT_NICKNAME;
        }
        return s.length() <= 30 ? s : s.substring(0, 30);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 实体 → DTO，顺带把顶层留言和它的一层回复组装成树。
     *
     * <p>⚠️ 组装只做一层：{@code parentId != null} 的进 replies，
     * 其余当顶层。不做递归 —— 个人站的留言板不需要楼中楼，
     * 递归还要防环（恶意构造 parent_id 互相指）。</p>
     */
    /**
     * {@code Long} 版 viewerId 转 {@code long}。
     *
     * <p>⚠️ 不是多此一举：mapper 里带 {@code VISIBLE_CLAUSE} 的方法形参是
     * 原始类型 {@code long}，直接传 {@code Long null} 会触发自动拆箱 NPE。
     * 线上真实故障：{@code GET /api/comments/public} 在游客（未登录）访问时
     * 报 {@code NullPointerException: Cannot invoke "java.lang.Long.longValue()
     * because "viewerId" is null"}。</p>
     *
     * <p>为什么把 null 变成 0 而不是让 SQL 收 null：SQL 里 {@code user_id = NULL}
     * 的结果是 NULL 不是 false，整个 {@code OR} 会退化成 null，连
     * 「PUBLIC + VISIBLE」那半边也匹配不上，留言板会一片空白。0 是个不可能
     * 存在的用户 id，等价于「没有登录的人」。</p>
     */
    private static long vid(Long viewerId) {
        return viewerId == null ? 0L : viewerId;
    }

    private List<CommentDto> toDtos(List<KbComment> rows, Long viewerId, boolean adminView) {
        Map<Long, List<KbComment>> repliesByParent = new LinkedHashMap<>();
        List<KbComment> topLevel = new ArrayList<>();
        for (KbComment c : rows) {
            if (c.getParentId() == null) {
                topLevel.add(c);
            }
            else {
                repliesByParent.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
            }
        }

        List<CommentDto> out = new ArrayList<>(topLevel.size());
        for (KbComment c : topLevel) {
            out.add(toDto(c, viewerId, adminView,
                    repliesByParent.getOrDefault(c.getId(), List.of())));
        }
        return out;
    }

    private CommentDto toDto(KbComment c, Long viewerId, boolean adminView,
                             List<KbComment> replies) {
        boolean mine = viewerId != null && isAuthor(c, viewerId);
        boolean hidden = KbComment.STATUS_HIDDEN.equals(c.getStatusOrDefault());
        // ⚠️ 悄悄话的 visibility 只对站主和作者本人暴露。
        // 对其他人来说这行压根查不出来（SQL 里就排除了），所以走到这里
        // 的 PRIVATE 只有站主视角一种情况 —— 直接给真实值即可。
        String visibility = c.getVisibilityOrDefault();

        List<CommentDto> replyDtos = new ArrayList<>(replies.size());
        for (KbComment r : replies) {
            replyDtos.add(toDto(r, viewerId, adminView, List.of()));
        }

        return new CommentDto(
                c.getId() == null ? 0 : c.getId(),
                c.getMomentId(),
                c.getNickname(),
                c.getContent(),
                new CommentDto.Author(c.getUserId(), null, c.getUserId() != null),
                mine,
                hidden,
                // 站主视角才给真实已读状态；访客视角恒 true（这字段对他没意义，
                // 给 false 会让前端误以为是自己的未读）
                adminView ? c.isRead() : true,
                visibility,
                TimeFormat.format(c.getCreatedAt()),
                replyDtos);
    }
}
