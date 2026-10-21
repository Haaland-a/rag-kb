package com.example.kb.dto.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 留言相关的请求体。
 *
 * <p>⚠️ 三个字段值得单独说明：</p>
 * <ul>
 *   <li>{@code visibility} —— 只有注册用户能选 PRIVATE（悄悄话）。
 *       游客传什么都会被强制改成 PUBLIC，理由写在 CommentService 里</li>
 *   <li>{@code nickname} —— 只有游客生效。注册用户忽略它，用账号昵称，
 *       否则任何人都能顶着别人的名字发言</li>
 *   <li>{@code deviceId} —— 留言也记一份设备指纹，让站主能看出
 *       「同一设备换昵称刷留言」。不传不影响留言本身</li>
 * </ul>
 */
public final class CommentRequests {

    private CommentRequests() {
    }

    /** POST /api/comments/public（留言板）与 POST /api/comments/public/moments/{id} */
    public static class Submit {

        @NotBlank(message = "留言内容不能为空")
        @Size(max = 1000, message = "留言最长 1000 字")
        private String content;

        @Size(max = 30, message = "昵称最长 30 字")
        private String nickname;

        /** PUBLIC（默认）/ PRIVATE（悄悄话，仅站主可见） */
        @Size(max = 16)
        private String visibility;

        @Size(max = 128, message = "设备指纹过长")
        private String deviceId;

        /** 回复某条留言时填它；顶层留言留空 */
        private Long parentId;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }

        public String getVisibility() {
            return visibility;
        }

        public void setVisibility(String visibility) {
            this.visibility = visibility;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(String deviceId) {
            this.deviceId = deviceId;
        }

        public Long getParentId() {
            return parentId;
        }

        public void setParentId(Long parentId) {
            this.parentId = parentId;
        }
    }

    /** PUT /api/comments/{id} —— 作者改自己的留言（游客不可改） */
    public static class Edit {

        @NotBlank(message = "留言内容不能为空")
        @Size(max = 1000, message = "留言最长 1000 字")
        private String content;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    /** PUT /api/comments/{id}/status —— 站主屏蔽/恢复 */
    public static class StatusChange {

        /** VISIBLE / HIDDEN */
        @NotBlank(message = "状态不能为空")
        @Size(max = 16)
        private String status;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
