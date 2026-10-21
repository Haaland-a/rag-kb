package com.example.kb.service;

import com.example.kb.config.SiteProperties;
import com.example.kb.domain.KbDocument;
import com.example.kb.dto.chat.ChatQuota;
import com.example.kb.dto.media.DownloadDto;
import com.example.kb.dto.media.MomentPreview;
import com.example.kb.dto.media.PhotoDto;
import com.example.kb.dto.media.ProjectDto;
import com.example.kb.dto.site.RecentDocument;
import com.example.kb.dto.site.SiteInfo;
import com.example.kb.dto.site.SiteOwner;
import com.example.kb.infrastructure.ratelimit.IpRateLimiter;
import com.example.kb.repository.KbDocumentRepository;
import com.example.kb.repository.VisitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 主页公开信息的业务编排。
 *
 * <p>⚠️ 这个类是从 {@code SiteController} 抽出来的。原来 Controller 里直接
 * 注入 {@link KbDocumentRepository} / {@link VisitRepository} 并现场拼统计，
 * 是典型的<b>越层</b>：Controller 应该只做「收参数 → 调 Service → 包 R」，
 * 不该知道数据怎么查、怎么聚合。</p>
 *
 * <p>另一个动机是<b>返回强类型</b>：原来返回 {@code Map<String,Object>}，
 * 前端要的 key 拼错（比如 {@code recentDocument} 少了 s）编译期发现不了，
 * 只能等浏览器里 undefined。现在换成 {@link SiteInfo} 记录类，
 * 字段改名编译器立刻报错。</p>
 */
@Service
public class SiteService {

    private static final Logger log = LoggerFactory.getLogger(SiteService.class);

    /** 主页「最近收录」最多展示几篇 */
    private static final int RECENT_LIMIT = 5;

    private final SiteProperties site;
    private final KbDocumentRepository documentRepository;
    private final VisitRepository visitRepository;
    private final IpRateLimiter rateLimiter;
    private final MediaService mediaService;
    private final ProfileService profileService;
    private final MomentService momentService;

    public SiteService(SiteProperties site,
                       KbDocumentRepository documentRepository,
                       VisitRepository visitRepository,
                       IpRateLimiter rateLimiter,
                       MediaService mediaService,
                       ProfileService profileService,
                       MomentService momentService) {
        this.site = site;
        this.documentRepository = documentRepository;
        this.visitRepository = visitRepository;
        this.rateLimiter = rateLimiter;
        this.mediaService = mediaService;
        this.profileService = profileService;
        this.momentService = momentService;
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
        // 回落逻辑封装在 ProfileService 里，这里不关心来源
        SiteOwner owner = safeOwner();

        long docCount = 0;
        long chunkCount = 0;
        List<RecentDocument> recent = new ArrayList<>();
        try {
            List<KbDocument> docs = documentRepository.findAll();
            docCount = docs.size();
            // ⚠️ KbDocument.chunkCount 是 int 不是 Integer（表上 NOT NULL DEFAULT 0），
            // 不能写 == null 判空，编译期就过不去
            chunkCount = docs.stream().mapToLong(KbDocument::getChunkCount).sum();

            // 主页只展示最近入库的几篇，且只给文件名和块数，不暴露全文
            recent = docs.stream()
                    .filter(d -> "READY".equals(d.getStatus()))
                    .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                    .limit(RECENT_LIMIT)
                    .map(d -> new RecentDocument(d.getFilename(), d.getChunkCount(), d.getFileType()))
                    .toList();
        }
        catch (Exception e) {
            // 统计失败不能让主页 500 —— 访客还能看到自我介绍和问答入口
            log.warn("主页文档统计失败，返回 0：{}", e.getMessage());
        }

        long visitCount = 0;
        try {
            visitCount = visitRepository.count();
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
        List<DownloadDto> downloads = safeList("下载列表", mediaService::listVisibleDownloads);
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
}
