package com.example.kb.service;

import com.example.kb.domain.KbDownload;
import com.example.kb.domain.KbPhoto;
import com.example.kb.domain.KbProject;
import com.example.kb.dto.media.DownloadDto;
import com.example.kb.dto.media.PhotoDto;
import com.example.kb.dto.media.ProjectDto;
import com.example.kb.infrastructure.storage.FileStorage;
import com.example.kb.repository.KbDownloadRepository;
import com.example.kb.repository.KbPhotoRepository;
import com.example.kb.repository.KbProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 个人内容（照片墙 / 项目 / 简历下载）的业务层。
 *
 * <p>三件事：</p>
 * <ol>
 *   <li><b>校验</b>：文件类型、大小、扩展名。这些规则集中在这里，Controller 不重复写</li>
 *   <li><b>存文件</b>：交给 {@link FileStorage}，本地还是 OSS 由配置决定</li>
 *   <li><b>写元信息</b>：图片宽高、文件大小、URL 落库</li>
 * </ol>
 *
 * <p>⚠️ 删除时<b>先删数据库再删文件</b>：如果反过来（先删文件，写库失败），
 * 数据库里会留下指向空文件的记录，页面上全是裂图，而且没法自己恢复。
 * 反过来最坏是 OSS 里多几个孤儿文件，不影响页面。</p>
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    /** 图片允许的类型 → 存储时用的扩展名 */
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");

    /** 图片大小上限 8MB。手机直出照片通常 2-5MB，8MB 够用又不至于拖垮 2C2G */
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    /** 文档（简历）上限 20MB */
    private static final long MAX_DOC_BYTES = 20L * 1024 * 1024;

    /** 简历允许的扩展名 */
    private static final Set<String> DOC_EXTS = Set.of("pdf", "docx", "doc", "zip");

    /** 主页照片上限。再多就说明该分页了，但个人站不需要 */
    private static final int HOME_PHOTO_LIMIT = 60;

    private final KbPhotoRepository photoRepository;
    private final KbProjectRepository projectRepository;
    private final KbDownloadRepository downloadRepository;
    private final FileStorage storage;

    public MediaService(KbPhotoRepository photoRepository,
                        KbProjectRepository projectRepository,
                        KbDownloadRepository downloadRepository,
                        FileStorage storage) {
        this.photoRepository = photoRepository;
        this.projectRepository = projectRepository;
        this.downloadRepository = downloadRepository;
        this.storage = storage;
    }

    // ==================== 照片墙 ====================

    /** 站主视角：全部照片（含隐藏） */
    public List<PhotoDto> listPhotosForAdmin() {
        return photoRepository.findAll(0).stream().map(PhotoDto::of).toList();
    }

    /** 访客视角：只返回可见的，主页用 */
    public List<PhotoDto> listVisiblePhotos() {
        return photoRepository.findVisible(HOME_PHOTO_LIMIT).stream().map(PhotoDto::of).toList();
    }

    /**
     * 上传一张照片。
     *
     * @param caption 一句话说明，可为空
     */
    public PhotoDto uploadPhoto(MultipartFile file, String caption) throws IOException {
        String contentType = file.getContentType();
        if (contentType == null || !IMAGE_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("只支持 jpg / png / webp / gif，收到: " + contentType);
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                    "图片不能超过 8MB，当前 " + (file.getSize() / 1024 / 1024) + "MB");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件是空的");
        }

        byte[] bytes = file.getBytes();
        // 读宽高：失败不影响上传（有些图 ImageIO 读不了，比如带特殊色彩配置的）
        Integer width = null;
        Integer height = null;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            BufferedImage img = ImageIO.read(in);
            if (img != null) {
                width = img.getWidth();
                height = img.getHeight();
            }
        }
        catch (IOException e) {
            log.warn("读取图片尺寸失败（不影响上传）：{}", e.getMessage());
        }

        String key = buildKey("photos", extensionFor(contentType));
        String url;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            url = storage.put(key, in, bytes.length, contentType);
        }

        KbPhoto p = new KbPhoto();
        p.setUrl(url);
        p.setCaption(trimToNull(caption));
        p.setSortOrder(0);
        p.setVisible(true);
        p.setWidth(width);
        p.setHeight(height);
        p.setFileSize((long) bytes.length);
        long id = photoRepository.insert(p);
        log.info("已上传照片 id={} url={} 尺寸={}x{}", id, url, width, height);

        return photoRepository.findById(id).map(PhotoDto::of).orElseThrow(
                () -> new IllegalStateException("照片插入后读不回来，id=" + id));
    }

    /**
     * 上传站主头像，返回可直接写进 {@code kb_profile.avatar_url} 的 URL。
     *
     * <p>⚠️ 单独开一个 {@code avatars/} 目录，不和照片墙混在一起：
     * 照片墙是「可批量管理的相册」，头像是「一个固定的资源」。
     * 混在一起以后想「清空照片墙」会把头像一起删掉。</p>
     *
     * <p>⚠️ 尺寸不裁：前端用 {@code object-fit: cover} 圆形裁切，
     * 服务端不引图像处理（2C2G 上为一头像开 BufferedImage 变换不划算，
     * 而且 ImageIO 处理 PNG 透明通道容易出意外）。代价是原图多大就存多大，
     * 所以这里把上限压到 8MB 并提示站主。</p>
     */
    public String uploadAvatar(MultipartFile file) throws IOException {
        String contentType = file.getContentType();
        if (contentType == null || !IMAGE_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("头像只支持 jpg / png / webp / gif，收到: " + contentType);
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                    "头像不能超过 8MB，当前 " + (file.getSize() / 1024 / 1024) + "MB");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件是空的");
        }
        byte[] bytes = file.getBytes();
        String key = buildKey("avatars", extensionFor(contentType));
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            String url = storage.put(key, in, bytes.length, contentType);
            log.info("已上传头像 url={}（{} bytes）", url, bytes.length);
            return url;
        }
    }

    // ==================== 照片墙（续） ====================

    /** 改说明 / 排序 / 可见性 */
    public void updatePhoto(long id, String caption, Integer sortOrder, Boolean visible) {
        if (photoRepository.findById(id).isEmpty()) {
            throw new IllegalArgumentException("照片不存在");
        }
        photoRepository.updateMeta(id, caption, sortOrder, visible);
    }

    /**
     * 删除照片。
     *
     * <p>顺序：先删数据库行，再删存储里的文件。理由见类注释。</p>
     */
    public void deletePhoto(long id) {
        KbPhoto p = photoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("照片不存在"));
        photoRepository.deleteById(id);
        deleteFileQuietly(p.getUrl());
    }

    // ==================== 项目展示 ====================

    public List<ProjectDto> listProjectsForAdmin() {
        return projectRepository.findAll().stream().map(ProjectDto::of).toList();
    }

    public List<ProjectDto> listVisibleProjects() {
        return projectRepository.findVisible().stream().map(ProjectDto::of).toList();
    }

    /**
     * 上传项目截图，返回可放进 coverUrl 的地址。
     *
     * <p>⚠️ 单独一个方法而不是复用 {@link #uploadPhoto}：截图存到
     * {@code projects/} 目录下，和照片墙分开。混在一起的话，
     * 以后想「清空照片墙」会连带删掉项目截图。</p>
     */
    public String uploadProjectCover(MultipartFile file) throws IOException {
        String contentType = file.getContentType();
        if (contentType == null || !IMAGE_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("只支持 jpg / png / webp / gif，收到: " + contentType);
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                    "图片不能超过 8MB，当前 " + (file.getSize() / 1024 / 1024) + "MB");
        }
        byte[] bytes = file.getBytes();
        String key = buildKey("projects", extensionFor(contentType));
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return storage.put(key, in, bytes.length, contentType);
        }
    }

    /** 新建项目卡片 */
    public long createProject(String title, String description, String coverUrl,
                              String tech, String repoUrl, String demoUrl,
                              Integer sortOrder, Boolean visible) {
        KbProject p = new KbProject();
        p.setTitle(title);
        p.setDescription(trimToNull(description));
        p.setCoverUrl(trimToNull(coverUrl));
        p.setTech(normalizeTech(tech));
        p.setRepoUrl(trimToNull(repoUrl));
        p.setDemoUrl(trimToNull(demoUrl));
        p.setSortOrder(sortOrder == null ? 0 : sortOrder);
        p.setVisible(visible == null || visible);
        return projectRepository.insert(p);
    }

    /**
     * 修改项目卡片。
     *
     * <p>⚠️ 整体覆盖：传 null 就是清空那个字段。这是有意为之 ——
     * 项目卡片要能「删掉截图」，局部更新做不到这一点。</p>
     */
    public void updateProject(long id, String title, String description, String coverUrl,
                              String tech, String repoUrl, String demoUrl,
                              Integer sortOrder, Boolean visible) {
        KbProject existing = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        existing.setTitle(title);
        existing.setDescription(trimToNull(description));
        existing.setCoverUrl(trimToNull(coverUrl));
        existing.setTech(normalizeTech(tech));
        existing.setRepoUrl(trimToNull(repoUrl));
        existing.setDemoUrl(trimToNull(demoUrl));
        existing.setSortOrder(sortOrder == null ? existing.getSortOrder() : sortOrder);
        existing.setVisible(visible == null || visible);
        projectRepository.update(existing);
    }

    public void deleteProject(long id) {
        KbProject p = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        projectRepository.deleteById(id);
        // 截图单独删：它是这个项目私有的，照片墙的图不受影响
        deleteFileQuietly(p.getCoverUrl());
    }

    // ==================== 简历 / 作品集下载 ====================

    public List<DownloadDto> listDownloadsForAdmin() {
        return downloadRepository.findAll().stream().map(DownloadDto::of).toList();
    }

    public List<DownloadDto> listVisibleDownloads() {
        return downloadRepository.findVisible().stream().map(DownloadDto::of).toList();
    }

    /** 上传简历 PDF，落库并返回完整条目 */
    public DownloadDto uploadDownload(MultipartFile file, String title) throws IOException {
        String original = file.getOriginalFilename();
        String ext = extensionOf(original);
        if (!DOC_EXTS.contains(ext)) {
            throw new IllegalArgumentException("只支持 pdf / docx / doc / zip，收到: " + ext);
        }
        if (file.getSize() > MAX_DOC_BYTES) {
            throw new IllegalArgumentException(
                    "文件不能超过 20MB，当前 " + (file.getSize() / 1024 / 1024) + "MB");
        }
        byte[] bytes = file.getBytes();
        // ⚠️ 用原扩展名而不是从 MIME 推：pdf/docx 的 MIME 在浏览器端
        // 有时是 application/octet-stream，推出来会变成 .bin
        String key = buildKey("files", ext);
        String url;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            url = storage.put(key, in, bytes.length, contentTypeOf(ext));
        }

        KbDownload d = new KbDownload();
        d.setTitle(trimToNull(title) == null ? original : title);
        d.setUrl(url);
        d.setFileType(ext);
        d.setFileSize((long) bytes.length);
        d.setSortOrder(0);
        d.setVisible(true);
        long id = downloadRepository.insert(d);
        log.info("已上传下载项 id={} title={} url={}", id, d.getTitle(), url);

        return downloadRepository.findById(id).map(DownloadDto::of).orElseThrow(
                () -> new IllegalStateException("下载项插入后读不回来，id=" + id));
    }

    /** 改标题 / 排序 / 可见性 */
    public void updateDownload(long id, String title, Integer sortOrder, Boolean visible) {
        if (downloadRepository.findById(id).isEmpty()) {
            throw new IllegalArgumentException("下载项不存在");
        }
        downloadRepository.updateMeta(id, title, sortOrder, visible);
    }

    public void deleteDownload(long id) {
        KbDownload d = downloadRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("下载项不存在"));
        downloadRepository.deleteById(id);
        deleteFileQuietly(d.getUrl());
    }

    /**
     * 删存储里的文件，失败只记日志不抛。
     *
     * <p>调用时数据库行<b>已经删掉了</b>，如果这里再抛异常，
     * 前端会收到 500 以为没删成功，刷新却发现记录没了 —— 状态和提示打架。</p>
     *
     * <p>真实踩到的场景：RAM 子账号只授了 PutObject / GetObject，没有 DeleteObject，
     * OSS 返回 {@code AccessDenied}。这时页面该删的还是要删掉，
     * 剩下的孤儿文件由站主自己去 OSS 控制台清，或者补上权限后重新删一次。</p>
     */
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
        } catch (Exception e) {
            log.warn("删除存储文件失败（数据库记录已删，可能留下孤儿文件）：key={}，原因：{}",
                    key, e.getMessage());
        }
    }

    /**
     * 记一次下载。
     *
     * <p>⚠️ 只在站主自己也不下载时才记？不 —— 访客点下载就走这里，
     * 站主在后台点「预览」不经过这个接口。计数偏一点无所谓，
     * 站主只是想大概知道有没有人下。</p>
     */
    public void recordDownload(long id) {
        downloadRepository.incrementDownload(id);
    }

    /** 公开可下载项的地址（校验可见性后再返回，防止直接猜 id 下隐藏文件） */
    public String visibleDownloadUrl(long id) {
        return downloadRepository.findById(id)
                .filter(KbDownload::isVisible)
                .map(KbDownload::getUrl)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在"));
    }

    // ==================== 内部工具 ====================

    /**
     * 生成存储 key：{@code 目录/年-月/{uuid}.{ext}}。
     *
     * <p>⚠️ 用 UUID 而不是用户原始文件名：</p>
     * <ol>
     *   <li>同名文件会互相覆盖</li>
     *   <li>中文文件名在 URL 里要编码，OSS 和浏览器对编码的处理边界不一致</li>
     *   <li>用户文件名可能含 {@code ../}，虽然 LocalStorage 有防护，
     *       但从源头就不让脏数据进入更干净</li>
     * </ol>
     *
     * @param dir 顶层目录（photos / covers / files）
     * @param ext 扩展名，<b>不带点</b>。图片可以走 {@link #extensionFor} 从 MIME 推，
     *            简历必须从原文件名的扩展名来 —— pdf/docx 的 MIME 在浏览器端
     *            有时是 application/octet-stream，推出来会变成 .bin
     */
    private static String buildKey(String dir, String ext) {
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return dir + "/" + month + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
    }

    /** MIME → 扩展名。只覆盖我们允许的类型，其余给 bin */
    private static String extensionFor(String contentType) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "application/pdf" -> "pdf";
            case "application/msword" -> "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/zip" -> "zip";
            default -> "bin";
        };
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String contentTypeOf(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc" -> "application/msword";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }

    /** 技术栈统一成「逗号分隔、去首尾空格、去空项」，避免「, ,a,,b 」这种脏数据 */
    private static String normalizeTech(String tech) {
        if (tech == null || tech.isBlank()) {
            return null;
        }
        return java.util.Arrays.stream(tech.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
