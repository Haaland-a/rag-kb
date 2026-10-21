package com.example.kb.domain;

import java.time.OffsetDateTime;

/**
 * kb_profile 单行表：站主资料（「关于我」等）。
 *
 * <p>⚠️ 这张表存在的唯一理由是<b>让站主能在后台改关于我</b>。
 * 之前这些内容全在 {@code application.yml} 的 {@code rag.site.*} 里，
 * 改一句话要 SSH 上去改配置文件再重启 —— 对一个「个人网站」来说太别扭了。</p>
 *
 * <p>⚠️ <b>表里没数据时回落到配置文件</b>（见 {@code ProfileService.resolve()}）：
 * 这样第一次部署不用先手工塞一行默认值，配置文件里的 value 依然可作兜底。</p>
 *
 * <p>⚠️ about / links / tags 在数据库里是 JSONB，在这个类里用
 * <b>已经解析好的 Java 对象</b>承载。JSON 的编解码放在 Repository 层，
 * 业务代码不该到处见到 {@code JsonNode}。</p>
 */
public class KbProfile {

    /** 恒为 1（表上有 CHECK 约束） */
    private int id = 1;

    /** 站主名字 */
    private String owner = "";

    /** 头像文字（没有头像图片时用首字母） */
    private String avatarText = "";

    /** 头像图片 URL。非空时优先显示图片，为空退回 {@link #avatarText} */
    private String avatarUrl = "";

    /** 名字下面一句话 */
    private String tagline = "";

    /** 「关于我」第一段 */
    private String intro = "";

    /** 「关于我」更多段落。JSONB 数组，元素是字符串 */
    private java.util.List<String> about = new java.util.ArrayList<>();

    /** 外链卡片。JSONB 数组，元素是 {label, url} */
    private java.util.List<Link> links = new java.util.ArrayList<>();

    /** 技能/兴趣标签。JSONB 数组，元素是字符串 */
    private java.util.List<String> tags = new java.util.ArrayList<>();

    private OffsetDateTime updatedAt;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
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

    public java.util.List<String> getAbout() {
        return about;
    }

    public void setAbout(java.util.List<String> about) {
        this.about = about == null ? new java.util.ArrayList<>() : about;
    }

    public java.util.List<Link> getLinks() {
        return links;
    }

    public void setLinks(java.util.List<Link> links) {
        this.links = links == null ? new java.util.ArrayList<>() : links;
    }

    public java.util.List<String> getTags() {
        return tags;
    }

    public void setTags(java.util.List<String> tags) {
        this.tags = tags == null ? new java.util.ArrayList<>() : tags;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * 一个外链条目。
     *
     * <p>⚠️ 刻意<b>不</b>复用 {@code SiteProperties.Link}：
     * 那个类是配置绑定的 DTO，将来配置项改名会波及数据库读出来的对象。
     * 两个方向的数据各自独立演进，属性名一样是巧合不是契约。</p>
     */
    public static class Link {

        private String label = "";
        private String url = "";

        public Link() {
        }

        public Link(String label, String url) {
            this.label = label;
            this.url = url;
        }

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
