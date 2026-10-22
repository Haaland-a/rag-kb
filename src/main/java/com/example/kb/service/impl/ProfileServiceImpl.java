package com.example.kb.service.impl;

import com.example.kb.config.SiteProperties;
import com.example.kb.domain.po.KbProfile;
import com.example.kb.domain.vo.LinkDto;
import com.example.kb.domain.vo.ProfileDto;
import com.example.kb.domain.vo.SiteOwner;
import com.example.kb.mapper.KbProfileMapper;
import com.example.kb.service.IProfileService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProfileServiceImpl implements IProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileServiceImpl.class);

    private final KbProfileMapper profileMapper;
    private final SiteProperties siteProperties;

    public ProfileServiceImpl(KbProfileMapper profileMapper, SiteProperties siteProperties) {
        this.profileMapper = profileMapper;
        this.siteProperties = siteProperties;
    }

    /**
     * 解析当前生效的资料（数据库优先，配置兜底）。
     *
     * <p>主页和后台编辑页都调这个 —— 保证两边看到的是同一份数据，
     * 不会出现「后台显示 A、主页显示 B」。</p>
     */
    public ProfileDto resolve() {
        KbProfile p = loadOrNull();
        if (p != null) {
            return new ProfileDto(
                    nullToEmpty(p.getOwner()),
                    nullToEmpty(p.getAvatarText()),
                    nullToEmpty(p.getAvatarUrl()),
                    nullToEmpty(p.getTagline()),
                    nullToEmpty(p.getIntro()),
                    safeList(p.getAbout()),
                    toLinkDtos(p.getLinks()),
                    safeList(p.getTags()),
                    p.getUpdatedAt() == null ? null : com.example.kb.common.TimeFormat.format(p.getUpdatedAt()),
                    false);
        }

        // 回落配置文件
        return new ProfileDto(
                nullToEmpty(siteProperties.getOwner()),
                nullToEmpty(siteProperties.getAvatarText()),
                nullToEmpty(siteProperties.getAvatarUrl()),
                nullToEmpty(siteProperties.getTagline()),
                nullToEmpty(siteProperties.getIntro()),
                safeList(siteProperties.getAbout()),
                fromConfigLinks(),
                safeList(siteProperties.getTags()),
                null,
                true);
    }

    /** 主页首屏用的精简版（少一个 fromConfig / updatedAt，不需要给访客看） */
    public SiteOwner owner() {
        ProfileDto p = resolve();
        return new SiteOwner(p.owner(), p.avatarText(), p.avatarUrl(), p.tagline(),
                p.intro(), p.about(), p.tags(), p.links());
    }

    /** 站主保存「关于我」。整行覆盖，见类注释里说的理由 */
    public ProfileDto save(String owner, String avatarText, String avatarUrl, String tagline,
                           String intro, List<String> about, List<LinkInputLike> links,
                           List<String> tags) {
        KbProfile p = new KbProfile();
        p.setOwner(nullToEmpty(owner).trim());
        p.setAvatarText(nullToEmpty(avatarText).trim());
        p.setAvatarUrl(safeAvatarUrl(avatarUrl));
        p.setTagline(nullToEmpty(tagline).trim());
        p.setIntro(nullToEmpty(intro).trim());
        p.setAbout(cleanStrings(about));
        p.setLinks(toDomainLinks(links));
        p.setTags(cleanStrings(tags));
        profileMapper.save(p);
        log.info("关于我已保存：owner={}，头像={}，段落 {} 个，外链 {} 个，标签 {} 个",
                p.getOwner(),
                p.getAvatarUrl().isEmpty() ? "文字「" + p.getAvatarText() + "」" : p.getAvatarUrl(),
                p.getAbout().size(), p.getLinks().size(), p.getTags().size());
        return resolve();
    }

    /**
     * 删掉数据库里的资料行，回到「听配置文件」的状态。
     *
     * <p>用途：站主把 yml 改好之后，发现数据库的旧值盖着不生效，
     * 需要一个「恢复成配置」的出口。不加这个功能的话，
     * 站主只能自己 psql 去 DELETE，对一个「个人网站」来说太硬核了。</p>
     */
    public ProfileDto resetToConfig() {
        profileMapper.delete();
        log.info("已清除数据库里的站主资料，回落到配置文件");
        return resolve();
    }

    private KbProfile loadOrNull() {
        try {
            return profileMapper.find().orElse(null);
        }
        catch (Exception e) {
            // 表还没建（老库没跑迁移脚本）时不能让整站挂掉，回落配置即可
            log.warn("读取 kb_profile 失败，回落到配置文件：{}", e.getMessage());
            return null;
        }
    }

    private List<LinkDto> fromConfigLinks() {
        List<SiteProperties.Link> raw = siteProperties.getLinks();
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(l -> l != null && notBlank(l.getUrl()))
                .map(l -> new LinkDto(nullToEmpty(l.getLabel()), l.getUrl().trim()))
                .toList();
    }

    private static List<LinkDto> toLinkDtos(List<KbProfile.Link> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(l -> l != null && notBlank(l.getUrl()))
                .map(l -> new LinkDto(nullToEmpty(l.getLabel()), l.getUrl().trim()))
                .toList();
    }

    private static List<KbProfile.Link> toDomainLinks(List<LinkInputLike> raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        List<KbProfile.Link> out = new ArrayList<>();
        for (LinkInputLike l : raw) {
            if (l == null || !notBlank(l.url())) {
                continue;
            }
            // ⚠️ 只接受 http(s) 和 mailto：站主自己填的也校验，
            // 因为 javascript: 开头的链接是一个真实的 XSS 向量
            String url = l.url().trim();
            if (!isSafeUrl(url)) {
                log.warn("忽略不安全的外链地址：{}", url);
                continue;
            }
            out.add(new KbProfile.Link(nullToEmpty(l.label()).trim(), url));
        }
        return out;
    }

    private static boolean isSafeUrl(String url) {
        String lower = url.toLowerCase();
        return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:");
    }

    /**
     * 头像地址的白名单校验。
     *
     * <p>比外链更严一点：<b>不接受 mailto</b>（头像不可能是邮箱），
     * 另外放行本站相对路径 {@code /media/...} —— 用本地存储时头像就是这个形态，
     * 如果只认 http(s)，站主切到本地存储后头像会全部被拒。</p>
     *
     * <p>⚠️ 为什么必须在服务端挡：前端把它塞进 {@code <img src>}，
     * 虽然 {@code javascript:} 对 img 不执行，但 {@code data:text/html}
     * 之类在部分浏览器/嵌入场景仍有风险。这条校验的成本几乎为零，
     * 不做就是留个口子。</p>
     */
    private static String safeAvatarUrl(String raw) {
        String url = nullToEmpty(raw).trim();
        if (url.isEmpty()) {
            return "";
        }
        String lower = url.toLowerCase();
        boolean ok = lower.startsWith("https://")
                || lower.startsWith("http://")
                || url.startsWith("/media/");
        if (!ok) {
            log.warn("忽略不安全的头像地址：{}", url);
            return "";
        }
        return url;
    }

    /** 一个临时的入参形态，避免 Service 依赖 DTO 包的内部类 */

    private static List<String> cleanStrings(List<String> raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (s == null) {
                continue;
            }
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static <T> List<T> safeList(List<T> raw) {
        return raw == null ? List.of() : raw;
    }
}
