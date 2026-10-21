package com.example.kb.dto.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 日常动态 / 评论 / 关于我 的请求体。
 *
 * <p>和 {@link MediaRequests} 一样集中在一个文件：每个类字段都很少，
 * 拆成多个文件只会让 import 变长。</p>
 */
public final class SiteContentRequests {

    private SiteContentRequests() {
    }

    /**
     * 站主保存「关于我」。
     *
     * <p>⚠️ 全部字段允许为空 —— 站主可能只想清空某一段。
     * 校验只做「别太长」，「别为空」交给前端提示，
     * 因为「关于我」本来就是可以什么都不写的。</p>
     */
    public static class ProfileSave {
        @Size(max = 40, message = "名字最多 40 字")
        private String owner;

        @Size(max = 4, message = "头像文字最多 4 个字符")
        private String avatarText;

        /**
         * 头像图片 URL。
         *
         * <p>⚠️ 只接受 http(s) 或本站 /media/ 开头的地址 —— 前端会把它塞进
         * {@code <img src>}，{@code javascript:} 虽然对 img 不生效，
         * 但 {@code data:text/html} 这类在个别浏览器里仍有风险，
         * 校验放在服务端更稳（{@code ProfileService} 里做）。</p>
         */
        @Size(max = 500, message = "头像地址最多 500 字")
        private String avatarUrl;

        @Size(max = 120, message = "一句话介绍最多 120 字")
        private String tagline;

        @Size(max = 1000, message = "简介最多 1000 字")
        private String intro;

        /** 段落列表，每项一段。「关于我」正文 */
        private List<String> about;

        /** 外链列表 */
        private List<LinkInput> links;

        /** 标签列表 */
        private List<String> tags;

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

        public List<LinkInput> getLinks() {
            return links;
        }

        public void setLinks(List<LinkInput> links) {
            this.links = links;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }
    }

    /** 「关于我」里的一条外链 */
    public static class LinkInput {
        @Size(max = 40, message = "外链名称最多 40 字")
        private String label;

        @Size(max = 500, message = "外链地址最多 500 字")
        private String url;

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

    /**
     * 站主发布 / 编辑一条动态。
     *
     * <p>⚠️ {@code syncToPhotos} 是「顺便发到照片墙」的开关。
     * 只在<b>新建</b>时生效 —— 编辑时再打开不会补发一张照片，
     * 因为「补发」意味着要重新上传文件、重算宽高，
     * 而编辑接口拿到的只有文字。想补发就删了重发。</p>
     */
    public static class MomentSave {
        @Size(max = 2000, message = "文字最多 2000 字")
        private String content;

        /** 新建时为已上传好的 URL；编辑时忽略 */
        private String mediaUrl;

        /** IMAGE / VIDEO。有 mediaUrl 时必须给 */
        private String mediaType;

        private Integer width;
        private Integer height;
        private Long size;

        /** 发布时是否同步到照片墙。仅新建生效 */
        private Boolean syncToPhotos;

        /** 这条动态用的照片墙说明文字，空则用动态文字截断 */
        private String photoCaption;

        private Boolean visible;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getMediaUrl() {
            return mediaUrl;
        }

        public void setMediaUrl(String mediaUrl) {
            this.mediaUrl = mediaUrl;
        }

        public String getMediaType() {
            return mediaType;
        }

        public void setMediaType(String mediaType) {
            this.mediaType = mediaType;
        }

        public Integer getWidth() {
            return width;
        }

        public void setWidth(Integer width) {
            this.width = width;
        }

        public Integer getHeight() {
            return height;
        }

        public void setHeight(Integer height) {
            this.height = height;
        }

        public Long getSize() {
            return size;
        }

        public void setSize(Long size) {
            this.size = size;
        }

        public Boolean getSyncToPhotos() {
            return syncToPhotos;
        }

        public void setSyncToPhotos(Boolean syncToPhotos) {
            this.syncToPhotos = syncToPhotos;
        }

        public String getPhotoCaption() {
            return photoCaption;
        }

        public void setPhotoCaption(String photoCaption) {
            this.photoCaption = photoCaption;
        }

        public Boolean getVisible() {
            return visible;
        }

        public void setVisible(Boolean visible) {
            this.visible = visible;
        }
    }

    // ⚠️ 原来的 CommentSubmit 已删除：留言请求体现在只有一份
    //   com.example.kb.dto.comment.CommentRequests.Submit（多了 visibility /
    //   deviceId / parentId 三个字段）。两套请求体并存时，用旧那套的端点
    //   会静默忽略 visibility，游客发"悄悄话"就变成了公开留言。
}
