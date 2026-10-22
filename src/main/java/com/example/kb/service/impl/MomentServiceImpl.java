package com.example.kb.service.impl;

import com.example.kb.domain.dto.SiteContentRequests;
import com.example.kb.domain.po.KbMoment;
import com.example.kb.domain.po.KbPhoto;
import com.example.kb.domain.vo.CommentDto;
import com.example.kb.domain.vo.MomentDetail;
import com.example.kb.domain.vo.MomentDto;
import com.example.kb.domain.vo.MomentPage;
import com.example.kb.domain.vo.MomentPreview;
import com.example.kb.infrastructure.storage.FileStorage;
import com.example.kb.mapper.KbCommentMapper;
import com.example.kb.mapper.KbMomentMapper;
import com.example.kb.mapper.KbPhotoMapper;
import com.example.kb.service.ICommentService;
import com.example.kb.service.IMomentService;
import com.example.kb.service.IMediaService;
import com.example.kb.service.ISiteService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MomentServiceImpl implements IMomentService {

    private static final Logger log = LoggerFactory.getLogger(MomentServiceImpl.class);

    /** 图片类型白名单 */
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");

    /**
     * 视频类型白名单。
     *
     * <p>⚠️ 只收这三种的原因：mp4/h264 是所有浏览器都能播的底子，
     * webm/vp9 是 Chrome/Firefox 的次选，quicktime(.mov) 只在 Safari 原生支持。
     * iPhone 默认录 .mov —— 直接放上去在安卓 Chrome 里会变成下载链接，
     * 所以站主要么转码，要么接受「只有 Safari 能播」。
     * 这里放行 mov 并在前端提示，比直接拒绝更实用（个人站不是视频平台）。</p>
     */
    private static final Set<String> VIDEO_TYPES = Set.of(
            "video/mp4", "video/webm", "video/quicktime", "video/x-m4v");

    /** 图片上限 8MB，和照片墙一致 */
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    /**
     * 视频上限 50MB。
     *
     * <p>⚠️ 这个数字不是随手定的：spring.servlet.multipart.max-file-size 是 50MB，
     * Tomcat 的 maxSwallowSize 设成了 -1（不限），所以 50MB 是这条链路上
     * 真正能过的大小。再大必须改配置并考虑 2C2G 的内存 —— 50MB 视频
     * 在堆里过一遍 ByteArray 已经是这个内存规格的极限了。</p>
     */
    private static final long MAX_VIDEO_BYTES = 50L * 1024 * 1024;

    /** 主页动态流默认条数 */
    private static final int HOME_LIMIT = 20;

    /** 后台分页上限，防止传个 1000000 把内存打爆 */
    private static final int MAX_PAGE_LIMIT = 100;

    /**
     * 主页每条动态下面摊开几条留言预览。
     *
     * <p>⚠️ 不能给太多：主页是「扫一眼」的地方，一条动态下面挂 30 条留言
     * 会把后面的项目/照片区推到几屏以外。3 条是我试出来的 ——
     * 1 条看不出是讨论，2 条像巧合，3 条才有「有人在这儿聊」的感觉。
     * 想看全部就去详情页。</p>
     */
    private static final int HOME_PREVIEW_LIMIT = 3;

    private final KbMomentMapper momentMapper;
    private final KbCommentMapper commentMapper;
    private final KbPhotoMapper photoMapper;
    private final FileStorage storage;
    private final ICommentService commentService;

    public MomentServiceImpl(KbMomentMapper momentMapper,
                         KbCommentMapper commentMapper,
                         KbPhotoMapper photoMapper,
                         FileStorage storage,
                         ICommentService commentService) {
        this.momentMapper = momentMapper;
        this.commentMapper = commentMapper;
        this.photoMapper = photoMapper;
        this.storage = storage;
        this.commentService = commentService;
    }

    // ==================== 动态：站主写 ====================

    /**
     * 上传动态的媒体文件（图片或视频），返回 URL 和宽高。
     *
     * <p>⚠️ 分成「先传文件拿到 URL，再提交动态」两步，而不是一个 multipart 请求
     * 把文字和文件一起提交：后者在上传 50MB 视频时，用户只要在等的时候
     * 关了页面，整个请求就白费了；两步的话文件已经在存储里，
     * 用户填完文字还能继续提交。代价是会留下没人引用的孤儿文件，
     * 由站主在 OSS 控制台清（个人站的量级不值得加定时清理任务）。</p>
     */
    public UploadedMedia uploadMedia(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null) {
            throw new IllegalArgumentException("无法识别文件类型");
        }
        String mime = contentType.toLowerCase(Locale.ROOT);
        boolean isImage = IMAGE_TYPES.contains(mime);
        boolean isVideo = VIDEO_TYPES.contains(mime);
        if (!isImage && !isVideo) {
            throw new IllegalArgumentException("只支持图片（jpg/png/webp/gif）或视频（mp4/webm/mov），收到: " + contentType);
        }
        long limit = isImage ? MAX_IMAGE_BYTES : MAX_VIDEO_BYTES;
        if (file.getSize() > limit) {
            throw new IllegalArgumentException(
                    (isImage ? "图片" : "视频") + "不能超过 " + (limit / 1024 / 1024)
                            + "MB，当前 " + (file.getSize() / 1024 / 1024) + "MB");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件是空的");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        }
        catch (Exception e) {
            throw new IllegalStateException("读取上传文件失败：" + e.getMessage());
        }

        Integer width = null;
        Integer height = null;
        if (isImage) {
            // 只有图片能读宽高。视频要读就得引 ffmpeg，为了占位图不值得
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                BufferedImage img = ImageIO.read(in);
                if (img != null) {
                    width = img.getWidth();
                    height = img.getHeight();
                }
            }
            catch (Exception e) {
                log.warn("读取动态图片尺寸失败（不影响上传）：{}", e.getMessage());
            }
        }

        String key = buildKey("moments", extOf(mime, isImage));
        String url;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            url = storage.put(key, in, bytes.length, mime);
        }
        catch (Exception e) {
            throw new IllegalStateException("保存媒体文件失败：" + e.getMessage());
        }
        log.info("已上传动态媒体 url={} 类型={} 大小={}KB", url, isImage ? "IMAGE" : "VIDEO", bytes.length / 1024);
        return new UploadedMedia(url, isImage ? KbMoment.MEDIA_IMAGE : KbMoment.MEDIA_VIDEO,
                width, height, (long) bytes.length);
    }

    /**
     * 发布一条动态。
     *
     * @param syncToPhotos 是否顺便发一条到照片墙（仅新建生效）
     * @param photoCaption 照片墙里那条的说明，空则用文字内容截断
     */
    public MomentDto create(SiteContentRequests.MomentSave req, boolean syncToPhotos) {
        String content = trimToNull(req.getContent());
        String mediaUrl = trimToNull(req.getMediaUrl());
        String mediaType = trimToNull(req.getMediaType());

        // ⚠️ 纯文字和「有媒体」两条路必须都通，但不能既没文字又没媒体
        if (content == null && mediaUrl == null) {
            throw new IllegalArgumentException("写点什么，或者传张图/一段视频");
        }
        if (mediaUrl != null && mediaType == null) {
            throw new IllegalArgumentException("上传了媒体但没传 mediaType（IMAGE 还是 VIDEO）");
        }
        if (mediaType != null && !KbMoment.MEDIA_IMAGE.equals(mediaType)
                && !KbMoment.MEDIA_VIDEO.equals(mediaType)) {
            throw new IllegalArgumentException("mediaType 只能是 IMAGE 或 VIDEO，收到: " + mediaType);
        }

        KbMoment m = new KbMoment();
        m.setContent(content);
        m.setMediaUrl(mediaUrl);
        m.setMediaType(mediaType);
        m.setMediaWidth(req.getWidth());
        m.setMediaHeight(req.getHeight());
        m.setMediaSize(req.getSize());
        m.setVisible(req.getVisible() == null || req.getVisible());
        // ⚠️ insert() 返回的是受影响行数（int），不是自增 id；id 由 MP 回填到实体上
        momentMapper.insert(m);
        long id = m.getId();

        // 同步到照片墙：只有图片能同步，视频放到照片墙没有意义
        if (syncToPhotos && mediaUrl != null && KbMoment.MEDIA_IMAGE.equals(mediaType)) {
            try {
                KbPhoto photo = new KbPhoto();
                photo.setUrl(mediaUrl);
                photo.setCaption(captionForPhoto(req.getPhotoCaption(), content));
                photo.setSortOrder(0);
                photo.setVisible(true);
                photo.setWidth(req.getWidth());
                photo.setHeight(req.getHeight());
                photo.setFileSize(req.getSize());
                // ⚠️ insert() 返回的是受影响行数（int），不是自增 id；id 由 MP 回填到实体上
        photoMapper.insert(photo);
        long photoId = photo.getId();
                momentMapper.updateSyncedPhoto(id, photoId);
                log.info("动态 id={} 已同步到照片墙，photoId={}", id, photoId);
            }
            catch (Exception e) {
                // ⚠️ 同步失败不能让发布失败：动态本身已经存好了，
                // 照片墙少一张图是次要问题，报错会让人以为动态没发出去
                log.warn("同步动态到照片墙失败（动态已发布）：id={}，原因：{}", id, e.getMessage());
            }
        }
        else if (syncToPhotos && mediaUrl != null && KbMoment.MEDIA_VIDEO.equals(mediaType)) {
            log.info("动态 id={} 是视频，跳过照片墙同步（照片墙只收图片）", id);
        }

        return momentMapper.findById(id).map(this::toAdminDto).orElseThrow(
                () -> new IllegalStateException("动态插入后读不回来，id=" + id));
    }

    /**
     * 编辑动态的文字和可见性。
     *
     * <p>⚠️ 不支持改媒体：见 {@link KbMomentMapper#updateContent} 的注释。
     * 也不支持在这里打开「同步到照片墙」—— 补发照片需要重新上传文件。</p>
     */
    public void update(long id, String content, Boolean visible) {
        KbMoment existing = momentMapper.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("动态不存在"));
        String trimmed = trimToNull(content);
        // 改成空文字时，必须确认它还有媒体，否则会变成一条什么都没有的空动态
        if (trimmed == null && existing.getMediaUrl() == null) {
            throw new IllegalArgumentException("文字清空后这条动态就什么都没有了，可以直接删掉它");
        }
        momentMapper.updateContent(id, trimmed, visible);
    }

    /**
     * 把一条已有的动态补发到照片墙。
     *
     * <p>⚠️ 这个能力是单独加的，因为 {@link #update} 做不到 ——
     * 站主经常是发完才发现「这张应该也放照片墙」。
     * 补发只对图片动态有效，且不会重复补发（已同步的直接返回原 photoId）。</p>
     *
     * @return 照片墙记录 id
     */
    public long syncToPhotos(long id, String photoCaption) {
        KbMoment m = momentMapper.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("动态不存在"));
        if (m.getSyncedPhotoId() != null) {
            throw new IllegalStateException("这条动态已经在照片墙里了");
        }
        if (m.getMediaUrl() == null) {
            throw new IllegalArgumentException("纯文字动态不能发到照片墙");
        }
        if (!KbMoment.MEDIA_IMAGE.equals(m.getMediaType())) {
            throw new IllegalArgumentException("只有图片能发到照片墙，视频不行");
        }

        KbPhoto photo = new KbPhoto();
        photo.setUrl(m.getMediaUrl());
        photo.setCaption(captionForPhoto(photoCaption, m.getContent()));
        photo.setSortOrder(0);
        photo.setVisible(true);
        photo.setWidth(m.getMediaWidth());
        photo.setHeight(m.getMediaHeight());
        photo.setFileSize(m.getMediaSize());
        // ⚠️ insert() 返回的是受影响行数（int），不是自增 id；id 由 MP 回填到实体上
        photoMapper.insert(photo);
        long photoId = photo.getId();
        momentMapper.updateSyncedPhoto(id, photoId);
        log.info("动态 id={} 补发到照片墙，photoId={}", id, photoId);
        return photoId;
    }

    /**
     * 删动态。
     *
     * <p>⚠️ 顺序：先删数据库行（评论靠外键级联一起走），再删媒体文件，
     * 最后删照片墙那条。理由见 {@code IMediaService} 类注释 ——
     * 先删文件的话，写库失败会留下指向空文件的记录，页面全是裂图且无法恢复。</p>
     */
    public void delete(long id) {
        KbMoment m = momentMapper.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("动态不存在"));
        Long photoId = m.getSyncedPhotoId();

        momentMapper.deleteById(id);

        // 照片墙那条：先删库行再删文件，和 IMediaService 一个套路
        if (photoId != null) {
            try {
                photoMapper.findById(photoId).ifPresent(p -> {
                    photoMapper.deleteById(photoId);
                    deleteFileQuietly(p.getUrl());
                });
            }
            catch (Exception e) {
                log.warn("删除动态时清理照片墙记录失败（动态已删）：photoId={}，原因：{}",
                        photoId, e.getMessage());
            }
        }

        deleteFileQuietly(m.getMediaUrl());
    }

    /** 照片墙里那张图被单独删掉时，把动态上的引用置空 */
    public void onPhotoDeleted(long photoId) {
        try {
            momentMapper.findBySyncedPhotoId(photoId)
                    .forEach(m -> momentMapper.updateSyncedPhoto(m.getId(), null));
        }
        catch (Exception e) {
            log.warn("清理动态的照片墙引用失败：photoId={}，原因：{}", photoId, e.getMessage());
        }
    }

    // ==================== 动态：读 ====================

    /** 站主视角：全部动态（含隐藏）+ 评论数 */
    public MomentPage listForAdmin(int limit, int offset) {
        int safeLimit = Math.min(Math.max(1, limit), MAX_PAGE_LIMIT);
        int safeOffset = Math.max(0, offset);
        List<MomentDto> items = momentMapper.findAll(safeLimit, safeOffset)
                .stream().map(this::toAdminDto).toList();
        return new MomentPage(items, momentMapper.count(false), safeLimit, safeOffset);
    }

    /**
     * 访客视角：只返回可见的，且<b>不带评论数</b>。
     *
     * <p>⚠️ 这就是「评论只有站主能看到」这条需求在数据层的落地：
     * 不是前端隐藏一个数字，而是服务端根本不查、不返回。
     * 抓包也看不到。</p>
     *
     * <p>⚠️ 但 {@code private} 留言的<b>预览</b>是另一回事（见
     * {@link #listForVisitorWithPreviews}）—— 公开留言本来就是给所有人看的，
     * 主页给个预览不违反「悄悄话只有站主能看」，因为 SQL 已经把 PRIVATE
     * 和 HIDDEN 排除干净了。两个方法的区别只在于「要不要顺带查留言」，
     * 不是可见性口径不同。</p>
     */
    public List<MomentDto> listForVisitor() {
        return momentMapper.findVisible(HOME_LIMIT, 0)
                .stream().map(this::toPublicDto).toList();
    }

    /**
     * 访客详情页的数据：一条动态 + 它下面访客可见的全部留言。
     *
     * <p>⚠️ 返回 {@code Optional.empty()} 有三种情况，前端都当 404 处理：
     * id 不存在、动态被站主设成了「仅自己可见」、动态已删。
     * <b>不区分这三种</b> —— 区分了等于告诉外面「这个 id 存在但被藏了」。</p>
     *
     * @param viewerId 看的人（null = 游客），决定「自己发的被屏蔽留言」是否可见
     */
    public Optional<MomentDetail> findForVisitor(long momentId, Long viewerId) {
        Optional<KbMoment> found = momentMapper.findById(momentId);
        if (found.isEmpty() || !found.get().isVisible()) {
            return Optional.empty();
        }
        MomentDto dto = toPublicDto(found.get());
        List<CommentDto> comments = commentService.momentComments(momentId, viewerId, false);
        long total = commentService.visibleCountForMoment(momentId, viewerId);
        return Optional.of(new MomentDetail(dto, comments, total));
    }

    /**
     * 主页数据：动态列表 + 每条动态的留言预览 + 留言总数。
     *
     * <p>为什么单独一个方法而不是让 {@code ISiteService} 自己拼两次调用：
     * 「先查动态、再按这批 id 批量查留言」的顺序和 id 提取是一次事务性的
     * 读逻辑，分散到两个类里将来改一处忘另一处就会错位。</p>
     *
     * <p>⚠️ 预览条数（{@link #HOME_PREVIEW_LIMIT}）和总数是两回事：
     * 主页每条动态下面只摊开 {{@value #HOME_PREVIEW_LIMIT}} 条，
     * 但角标要显示真实总数，点进详情页才看全部。</p>
     *
     * @param viewerId 看的人（null = 游客）
     */
    public List<MomentPreview> listForVisitorWithPreviews(Long viewerId) {
        List<MomentDto> moments = listForVisitor();
        if (moments.isEmpty()) {
            return List.of();
        }
        List<Long> ids = moments.stream().map(MomentDto::id).toList();
        Map<Long, List<CommentDto>> comments = commentService.previewsForMoments(
                ids, viewerId, HOME_PREVIEW_LIMIT);
        Map<Long, Long> totals = commentService.visibleCountsForMoments(ids, viewerId);

        List<MomentPreview> out = new ArrayList<>(moments.size());
        for (MomentDto m : moments) {
            out.add(new MomentPreview(m,
                    comments.getOrDefault(m.id(), List.of()),
                    totals.getOrDefault(m.id(), 0L)));
        }
        return out;
    }

    /**
     * 一条动态的预览：动态本体 + 前几条公开留言 + 留言总数。
     *
     * <p>record 本体在 {@code dto/media/MomentPreview}（因为 {@code SiteInfo}
     * 直接引用它 —— 留在 Service 里会让 DTO 层反过来依赖 Service 层），
     * 方法本体就是上面那个 {@link #listForVisitorWithPreviews}。</p>
     */

    // ==================== 评论（已全部迁移到 ICommentService） ====================
    //
    // ⚠️ 这一节原本有 addComment / listComments / commentsOf / markCommentsRead /
    //   unreadCount 五个方法，现在全部移除。原因不是"整理代码"，是它们真出过 bug：
    //   commentsOf 调的是访客版 SQL（带 VISIBLE_CLAUSE），悄悄话和已屏蔽的留言
    //   在站主后台点"看留言"时直接消失；listComments 用的是另一套 DTO
    //   （dto.media.CommentDto，没有 author/hidden/visibility 字段），前端要按
    //   调用的端点不同切两套解析逻辑。
    //
    //   现在所有留言读写只有一处实现：{@link ICommentService}。
    //   MomentService 只保留一个 countMomentComments 用于动态列表上的数字角标。

    // ==================== DTO 转换 ====================

    /** 站主视角：带评论数、带 visible、带是否已同步照片墙 */
    private MomentDto toAdminDto(KbMoment m) {
        long comments = 0;
        try {
            comments = commentMapper.countMomentComments(m.getId());
        }
        catch (Exception e) {
            log.warn("统计动态评论数失败：momentId={}，原因：{}", m.getId(), e.getMessage());
        }
        return new MomentDto(m.getId(), m.getContent(), m.getMediaUrl(), m.getMediaType(),
                m.getMediaWidth(), m.getMediaHeight(), m.getMediaSize(),
                m.getSyncedPhotoId() != null, m.isVisible(),
                formatTs(m.getCreatedAt()), comments);
    }

    /**
     * 访客视角。
     *
     * <p>⚠️ 三个字段被抹掉：{@code comments} 恒为 0（不查库）、
     * {@code synced} 恒为 false、{@code visible} 恒为 true。
     * 前两个是内部状态，第三个访客看到的本来就只有可见的。</p>
     */
    private MomentDto toPublicDto(KbMoment m) {
        return new MomentDto(m.getId(), m.getContent(), m.getMediaUrl(), m.getMediaType(),
                m.getMediaWidth(), m.getMediaHeight(), m.getMediaSize(),
                false, true, formatTs(m.getCreatedAt()), 0);
    }


    // ==================== 内部工具 ====================

    /** 照片墙里那条的说明：站主填了就用，没填就用动态文字前 60 字，都没有就留空 */
    private static String captionForPhoto(String photoCaption, String momentContent) {
        String explicit = trimToNull(photoCaption);
        if (explicit != null) {
            return explicit.length() <= 200 ? explicit : explicit.substring(0, 200) + "…";
        }
        String content = trimToNull(momentContent);
        if (content == null) {
            return null;
        }
        return content.length() <= 60 ? content : content.substring(0, 60) + "…";
    }



    private static String formatTs(java.time.OffsetDateTime ts) {
        // ⚠️ 必须走 TimeFormat（内部转服务器时区），不能直接 format ——
        // 数据库存 UTC，直接格式化会让 18:54 的事件显示成 10:54
        return com.example.kb.common.TimeFormat.format(ts);
    }


    private static String buildKey(String dir, String ext) {
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return dir + "/" + month + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
    }

    private static String extOf(String mime, boolean isImage) {
        if (isImage) {
            return switch (mime) {
                case "image/jpeg" -> "jpg";
                case "image/png" -> "png";
                case "image/webp" -> "webp";
                case "image/gif" -> "gif";
                default -> "jpg";
            };
        }
        return switch (mime) {
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            case "video/x-m4v" -> "m4v";
            default -> "mp4";
        };
    }

    /** 删存储文件，失败只记日志 —— 数据库行已经删了，再抛异常会让前端以为没删成功 */
    private void deleteFileQuietly(String url) {
        if (url == null) {
            return;
        }
        String key = storage.keyOf(url);
        if (key == null) {
            return;
        }
        try {
            storage.delete(key);
        }
        catch (Exception e) {
            log.warn("删除动态媒体失败（数据库记录已删，可能留下孤儿文件）：key={}，原因：{}",
                    key, e.getMessage());
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 上传媒体的结果。
     *
     * @param url       存储 URL，直接放进 MomentSave.mediaUrl
     * @param mediaType IMAGE / VIDEO
     * @param width     图片宽，视频为 null
     * @param height    图片高，视频为 null
     * @param size      字节数
     */
}
