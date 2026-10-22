package com.example.kb.service.impl;

import com.example.kb.common.PiiMasker;
import com.example.kb.common.PiiProperties;
import com.example.kb.config.SiteProperties;
import com.example.kb.domain.po.KbDocument;
import com.example.kb.domain.vo.ChatQuota;
import com.example.kb.domain.vo.DownloadDto;
import com.example.kb.domain.vo.MomentPreview;
import com.example.kb.domain.vo.PhotoDto;
import com.example.kb.domain.vo.ProjectDto;
import com.example.kb.domain.vo.RecentDocument;
import com.example.kb.domain.vo.SiteInfo;
import com.example.kb.domain.vo.SiteOwner;
import com.example.kb.infrastructure.ratelimit.IpRateLimiter;
import com.example.kb.mapper.KbDocumentMapper;
import com.example.kb.mapper.KbVisitMapper;
import com.example.kb.service.ISiteService;
import com.example.kb.service.IMediaService;
import com.example.kb.service.IMomentService;
import com.example.kb.service.IProfileService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SiteServiceImpl implements ISiteService {

    private static final Logger log = LoggerFactory.getLogger(SiteServiceImpl.class);

    /** 主页「最近收录」最多展示几篇 */
    private static final int RECENT_LIMIT = 5;

    private final SiteProperties site;
    private final KbDocumentMapper documentMapper;
    private final KbVisitMapper visitMapper;
    private final IpRateLimiter rateLimiter;
    private final IMediaService mediaService;
    private final IProfileService profileService;
    private final IMomentService momentService;
    private final PiiProperties piiProperties;

    public SiteServiceImpl(SiteProperties site,
                       KbDocumentMapper documentMapper,
                       KbVisitMapper visitMapper,
                       IpRateLimiter rateLimiter,
                       IMediaService mediaService,
                       IProfileService profileService,
                       IMomentService momentService,
                       PiiProperties piiProperties) {
        this.site = site;
        this.documentMapper = documentMapper;
        this.visitMapper = visitMapper;
        this.rateLimiter = rateLimiter;
        this.mediaService = mediaService;
        this.profileService = profileService;
        this.momentService = momentService;
        this.piiProperties = piiProperties;
    }

    /**
     * 主页所需的全部公开数据，一次组装完。
     *
     * @param ip       当前访客 IP（算剩余额度用）
     * @param viewerId 当前看的人（null = 游客）；只用来决定「自己发的被屏蔽
     *                 留言是否可见」，不区分站主 —— 主页对谁都一样
     * @param isAdmin  是否站主（站主额度不限，显示 -1）
     */
    public SiteInfo build(String ip, boolean isAdmin, Long viewerId) {
        // ⚠️ 站主资料现在优先读数据库（后台可在线编辑），数据库没行才回落 yml。
        // 回落逻辑封装在 IProfileService 里，这里不关心来源
        SiteOwner owner = safeOwner();

        long docCount = 0;
        long chunkCount = 0;
        List<RecentDocument> recent = new ArrayList<>();
        try {
            List<KbDocument> docs = documentMapper.findAll();
            docCount = docs.size();
            // ⚠️ KbDocument.chunkCount 是 int 不是 Integer（表上 NOT NULL DEFAULT 0），
            // 不能写 == null 判空，编译期就过不去
            chunkCount = docs.stream().mapToLong(KbDocument::getChunkCount).sum();

            // 主页只展示最近入库的几篇，且只给文件名和块数，不暴露全文
            recent = docs.stream()
                    .filter(d -> "READY".equals(d.getStatus()))
                    .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                    .limit(RECENT_LIMIT)
                    .map(d -> new RecentDocument(
                            PiiMasker.mask(d.getFilename(), piiProperties.getExtraTerms()),
                            d.getChunkCount(),
                            d.getFileType()))
                    .toList();
        }
        catch (Exception e) {
            // 统计失败不能让主页 500 —— 访客还能看到自我介绍和问答入口
            log.warn("主页文档统计失败，返回 0：{}", e.getMessage());
        }

        long visitCount = 0;
        try {
            visitCount = visitMapper.count();
        }
        catch (Exception e) {
            log.warn("主页访客数统计失败，返回 0：{}", e.getMessage());
        }

        ChatQuota quota = new ChatQuota();
        quota.setLimit(rateLimiter.getChatPerHour());
        quota.setRemaining(isAdmin ? -1 : rateLimiter.remainingChat(ip));
        quota.setAdmin(isAdmin);

        // 个人内容三块：照片墙 / 项目 / 简历下载。各自 try/catch，
        // 一块查挂了不影响其他块和主页本身
        List<PhotoDto> photos = safeList("照片墙", mediaService::listVisiblePhotos);
        List<ProjectDto> projects = safeList("项目展示", mediaService::listVisibleProjects);
        // ⚠️ 下载项的标题也要脱敏。站主上传简历时标题往往就是文件名，
        // 「张三-Java后端开发-简历-v4」这种会把真名摆在主页最显眼的位置。
        // 这是实测里漏掉的一处：正文、预览、文档文件名都堵住了，唯独下载标题没堵。
        List<DownloadDto> downloads = safeList("下载列表", mediaService::listVisibleDownloads)
                .stream()
                .map(this::maskDownloadTitle)
                .toList();
        // ⚠️ 这里传的是「看的人」，不是「是不是站主」：
        // 主页永远是公开视角，站主自己打开主页看到的也该是公开视图。
        // 悄悄话不进主页，只在后台留言管理里出现
        List<MomentPreview> moments = safeList("日常动态",
                () -> momentService.listForVisitorWithPreviews(viewerId));

        return new SiteInfo(
                site.getTitle(), owner,
                docCount, chunkCount, recent, visitCount, quota,
                photos, projects, downloads, moments);
    }

    /**
     * 站主资料，读挂了也要给出可渲染的东西。
     *
     * <p>⚠️ 这里不能简单地 {@code safeList} 返回空，因为 owner 是主页首屏
     * 的核心内容 —— 空对象会让「你好，我是 」后面什么都没有。
     * 所以注册失败时退回到配置里的值。</p>
     */
    private SiteOwner safeOwner() {
        try {
            return profileService.owner();
        }
        catch (Exception e) {
            log.warn("站主资料读取失败，回落配置：{}", e.getMessage());
            return new SiteOwner(site.getOwner(), site.getAvatarText(),
                    site.getAvatarUrl(), site.getTagline(),
                    site.getIntro(), site.getAbout(), site.getTags(), List.of());
        }
    }

    /** 一块内容查失败就当空的，只记日志 —— 主页不该因为某块数据挂了就白屏 */
    private static <T> List<T> safeList(String what, java.util.function.Supplier<List<T>> supplier) {
        try {
            return supplier.get();
        }
        catch (Exception e) {
            log.warn("主页{}加载失败，按空处理：{}", what, e.getMessage());
            return List.of();
        }
    }

    /**
     * 下载项标题脱敏。只换标题，其他字段原样。
     *
     * <p>返回新的 {@link DownloadDto} 而不是原地改 —— record 不可变，
     * 而且原地改会污染 MyBatis 二级缓存里那个对象。</p>
     */
    private DownloadDto maskDownloadTitle(DownloadDto d) {
        if (d == null || d.title() == null || d.title().isEmpty()) {
            return d;
        }
        String safe = PiiMasker.mask(d.title(), piiProperties.getExtraTerms());
        if (safe.equals(d.title())) {
            return d;
        }
        return new DownloadDto(d.id(), safe, d.url(), d.fileType(), d.fileSize(),
                d.downloadCount(), d.sortOrder(), d.visible(), d.createdAt());
    }
}
