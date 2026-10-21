package com.example.kb.dto.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 媒体相关的请求体。
 *
 * <p>全部集中在一个文件：每个只有一个到三个字段，分三个文件只会让
 * import 列表变长、找起来更费劲。</p>
 */
public final class MediaRequests {

    private MediaRequests() {
    }

    /** 修改照片的说明 / 排序 / 可见性（只送要改的字段） */
    public static class PhotoUpdate {
        @Size(max = 200, message = "说明最多 200 字")
        private String caption;
        private Integer sortOrder;
        private Boolean visible;

        public String getCaption() {
            return caption;
        }

        public void setCaption(String caption) {
            this.caption = caption;
        }

        public Integer getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }

        public Boolean getVisible() {
            return visible;
        }

        public void setVisible(Boolean visible) {
            this.visible = visible;
        }
    }

    /** 新建 / 修改项目卡片 */
    public static class ProjectSave {
        @NotBlank(message = "项目名不能为空")
        @Size(max = 80, message = "项目名最多 80 字")
        private String title;

        @Size(max = 300, message = "说明最多 300 字")
        private String description;

        private String coverUrl;
        private String tech;
        private String repoUrl;
        private String demoUrl;
        private Integer sortOrder;
        private Boolean visible;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getCoverUrl() {
            return coverUrl;
        }

        public void setCoverUrl(String coverUrl) {
            this.coverUrl = coverUrl;
        }

        public String getTech() {
            return tech;
        }

        public void setTech(String tech) {
            this.tech = tech;
        }

        public String getRepoUrl() {
            return repoUrl;
        }

        public void setRepoUrl(String repoUrl) {
            this.repoUrl = repoUrl;
        }

        public String getDemoUrl() {
            return demoUrl;
        }

        public void setDemoUrl(String demoUrl) {
            this.demoUrl = demoUrl;
        }

        public Integer getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }

        public Boolean getVisible() {
            return visible;
        }

        public void setVisible(Boolean visible) {
            this.visible = visible;
        }
    }

    /** 修改下载项 */
    public static class DownloadUpdate {
        @Size(max = 80, message = "名称最多 80 字")
        private String title;
        private Integer sortOrder;
        private Boolean visible;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public Integer getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }

        public Boolean getVisible() {
            return visible;
        }

        public void setVisible(Boolean visible) {
            this.visible = visible;
        }
    }
}
