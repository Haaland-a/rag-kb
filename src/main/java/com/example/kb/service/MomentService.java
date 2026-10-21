package com.example.kb.service;

import com.example.kb.domain.KbMoment;
import com.example.kb.domain.KbPhoto;
import com.example.kb.dto.media.MomentDto;
import com.example.kb.dto.media.MomentPage;
import com.example.kb.dto.media.SiteContentRequests;
import com.example.kb.infrastructure.storage.FileStorage;
import com.example.kb.repository.KbCommentRepository;
import com.example.kb.repository.KbMomentRepository;
import com.example.kb.repository.KbPhotoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 日常动态 + 访客评论的业务层。
 *
 * <h2>三条设计原则</h2>
 *
 * <p><b>1. 评论是私有的。</b>它本质上是「留给站主的悄悄话」，不是公开评论区。
 * 所以：访客接口只能提交不能读；站主接口才能列出评论；
 * 主页返回的 {@link MomentDto#comments()} 恒为 0（访客连「有几条评论」
 * 都不该知道，否则能靠数量变化探测别人有没有留言）。</p>
 *
 * <p><b>2. 同步到照片墙是「投影」，动态才是主体。</b>
 * 发布时勾了同步，就在 kb_photo 里插一条并把 id 记在 {@code synced_photo_id}。
 * 删动态时连带删照片；但站主单独在照片墙删那张图时，只把
 * {@code synced_photo_id} 置空，动态本身不动。</p>
 *
 * <p><b>3. 媒体文件走 {@link FileStorage}。</b>图片和视频分开校验
 * （视频上限大得多），但存储路径都用 {@code moments/} 前缀，
 * 和照片墙的 {@code photos/} 分开 —— 删照片墙不会波及动态。</p>
 */
@Service
public class MomentService {

    private static final Logger log = LoggerFactory.getLogger(MomentService.class);

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

    /** 单条动态下的评论最多回多少条（后台看详情用） */

    private final KbMomentRepository momentRepository;
    private final KbCommentRepository commentRepository;
    private final KbPhotoRepository photoRepository;
    private final FileStorage storage;

    public MomentService(KbMomentRepository momentRepository,
                         KbCommentRepository commentRepository,
                         KbPhotoRepository photoRepository,
                         FileStorage storage) {
        this.momentRepository = momentRepository;
        this.commentRepository = commentRepository;
        this.photoRepository = photoRepository;
        this.storage = storage;
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
        long id = momentRepository.insert(m);

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
                long photoId = photoRepository.insert(photo);
                momentRepository.updateSyncedPhoto(id, photoId);
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

        return momentRepository.findById(id).map(this::toAdminDto).orElseThrow(
                () -> new IllegalStateException("动态插入后读不回来，id=" + id));
    }

    /**
     * 编辑动态的文字和可见性。
     *
     * <p>⚠️ 不支持改媒体：见 {@link KbMomentRepository#updateContent} 的注释。
     * 也不支持在这里打开「同步到照片墙」—— 补发照片需要重新上传文件。</p>
     */
    public void update(long id, String content, Boolean visible) {
        KbMoment existing = momentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("动态不存在"));
        String trimmed = trimToNull(content);
        // 改成空文字时，必须确认它还有媒体，否则会变成一条什么都没有的空动态
        if (trimmed == null && existing.getMediaUrl() == null) {
            throw new IllegalArgumentException("文字清空后这条动态就什么都没有了，可以直接删掉它");
        }
        momentRepository.updateContent(id, trimmed, visible);
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
        KbMoment m = momentRepository.findById(id)
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
        long photoId = photoRepository.insert(photo);
        momentRepository.updateSyncedPhoto(id, photoId);
        log.info("动态 id={} 补发到照片墙，photoId={}", id, photoId);
        return photoId;
    }

    /**
     * 删动态。
     *
     * <p>⚠️ 顺序：先删数据库行（评论靠外键级联一起走），再删媒体文件，
     * 最后删照片墙那条。理由见 {@code MediaService} 类注释 ——
     * 先删文件的话，写库失败会留下指向空文件的记录，页面全是裂图且无法恢复。</p>
     */
    public void delete(long id) {
        KbMoment m = momentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("动态不存在"));
        Long photoId = m.getSyncedPhotoId();

        momentRepository.deleteById(id);

        // 照片墙那条：先删库行再删文件，和 MediaService 一个套路
        if (photoId != null) {
            try {
                photoRepository.findById(photoId).ifPresent(p -> {
                    photoRepository.deleteById(photoId);
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
            momentRepository.findBySyncedPhotoId(photoId)
                    .forEach(m -> momentRepository.updateSyncedPhoto(m.getId(), null));
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
        List<MomentDto> items = momentRepository.findAll(safeLimit, safeOffset)
                .stream().map(this::toAdminDto).toList();
        return new MomentPage(items, momentRepository.count(false), safeLimit, safeOffset);
    }

    /**
     * 访客视角：只返回可见的，且<b>不带评论数</b>。
     *
     * <p>⚠️ 这就是「评论只有站主能看到」这条需求在数据层的落地：
     * 不是前端隐藏一个数字，而是服务端根本不查、不返回。
     * 抓包也看不到。</p>
     */
    public List<MomentDto> listForVisitor() {
        return momentRepository.findVisible(HOME_LIMIT, 0)
                .stream().map(this::toPublicDto).toList();
    }

    // ==================== 评论（已全部迁移到 CommentService） ====================
    //
    // ⚠️ 这一节原本有 addComment / listComments / commentsOf / markCommentsRead /
    //   unreadCount 五个方法，现在全部移除。原因不是"整理代码"，是它们真出过 bug：
    //   commentsOf 调的是访客版 SQL（带 VISIBLE_CLAUSE），悄悄话和已屏蔽的留言
    //   在站主后台点"看留言"时直接消失；listComments 用的是另一套 DTO
    //   （dto.media.CommentDto，没有 author/hidden/visibility 字段），前端要按
    //   调用的端点不同切两套解析逻辑。
    //
    //   现在所有留言读写只有一处实现：{@link CommentService}。
    //   MomentService 只保留一个 countMomentComments 用于动态列表上的数字角标。

    // ==================== DTO 转换 ====================

    /** 站主视角：带评论数、带 visible、带是否已同步照片墙 */
    private MomentDto toAdminDto(KbMoment m) {
        long comments = 0;
        try {
            comments = commentRepository.countMomentComments(m.getId());
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
    public record UploadedMedia(String url, String mediaType, Integer width, Integer height, Long size) {
    }
}
