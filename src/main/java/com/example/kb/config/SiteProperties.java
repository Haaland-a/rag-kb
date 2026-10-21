package com.example.kb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 个人站资料配置。
 *
 * <p>全部从 {@code application-prod.yml} 的 {@code rag.site.*} 读取，
 * 改内容不用重新 build 前端。</p>
 *
 * <p>⚠️ 用 {@code @ConfigurationProperties} 而不是一堆 {@code @Value}：
 * about 是段落列表、links 是 {label,url} 的对象列表，{@code @Value} 表达不了
 * 这种结构（只能拼逗号字符串再自己解析，很容易踩转义坑）。</p>
 */
@Component
@ConfigurationProperties(prefix = "rag.site")
public class SiteProperties {

    /** 站名，显示在浏览器标题和页脚 */
    private String title = "Demo";

    /** 站主名字，主页大标题 */
    private String owner = "Demo";

    /** 头像文字（没有头像图片时用首字母，比默认灰头好看） */
    private String avatarText = "H";

    /**
     * 头像图片 URL。
     *
     * <p>⚠️ 只在「数据库里还没有资料行」时生效。一旦在后台保存过，
     * 头像就读 {@code kb_profile.avatar_url} 了。想用配置里的这张图，
     * 要么后台把 URL 填进输入框，要么点「恢复默认」。</p>
     */
    private String avatarUrl = "";

    /** 一句话介绍，主页副标题 */
    private String tagline = "记录、思考与分享";

    /** 简介段落的开头一句 */
    private String intro = "";

    /** 「关于我」的段落列表 */
    private List<String> about = new ArrayList<>();

    /** 外部链接（GitHub / 邮箱 / 博客等） */
    private List<Link> links = new ArrayList<>();

    /** 主页「技能/兴趣」标签 */
    private List<String> tags = new ArrayList<>();

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getAvatarText() {
        return avatarText;
    }

    public void setAvatarText(String avatarText) {
        this.avatarText = avatarText;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getTagline() {
        return tagline;
    }

    public void setTagline(String tagline) {
        this.tagline = tagline;
    }

    public String getIntro() {
        return intro;
    }

    public void setIntro(String intro) {
        this.intro = intro;
    }

    public List<String> getAbout() {
        return about;
    }

    public void setAbout(List<String> about) {
        this.about = about;
    }

    public List<Link> getLinks() {
        return links;
    }

    public void setLinks(List<Link> links) {
        this.links = links;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    /** 一个外链条目 */
    public static class Link {
        private String label = "";
        private String url = "";

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}
