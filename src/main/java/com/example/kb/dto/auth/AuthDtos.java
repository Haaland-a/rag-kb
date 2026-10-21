package com.example.kb.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 认证相关 DTO。合并在一个文件里：字段少、只被 AuthController 用，
 * 拆成四个文件纯属噪音。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** POST /api/auth/login 请求体 */
    public static class LoginRequest {

        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名最长 64 字符")
        private String username;

        @NotBlank(message = "密码不能为空")
        @Size(max = 128, message = "密码最长 128 字符")
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * 登录 / 注册成功响应。
     *
     * <p>⚠️ 直接把完整身份一起返回，省掉前端登录后再拉一次
     * {@code /api/auth/me}：少一次往返，也避免「有 token 但还没拿到用户信息」
     * 的中间态（前端得为它写一遍兜底）。</p>
     */
    public static class LoginResponse {

        private String token;
        private Long userId;
        private String username;
        private String displayName;
        /**
         * 是不是站主。
         *
         * <p>⚠️ 前端只用来决定「登录后跳后台还是回主页」，
         * <b>不是安全边界</b> —— 真正的拦截在服务端路径白名单 + requireAdmin。</p>
         */
        private boolean admin;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public boolean isAdmin() {
            return admin;
        }

        public void setAdmin(boolean admin) {
            this.admin = admin;
        }
    }

    /** GET /api/auth/me 响应 */
    public static class UserInfo {

        private Long id;
        private String username;
        private String displayName;
        /**
         * 是不是站主。
         *
         * <p>⚠️ 前端靠这个决定「是否显示后台入口」，但<b>绝不能</b>靠它做安全判断 ——
         * 真正的拦截在服务端的 {@code AuthInterceptor} + {@code requireAdmin()}。
         * 这里只是避免普通用户看到一堆点了会 401 的入口。</p>
         */
        private boolean admin;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public boolean isAdmin() {
            return admin;
        }

        public void setAdmin(boolean admin) {
            this.admin = admin;
        }
    }

    /** POST /api/auth/register 请求体 */
    public static class RegisterRequest {

        @NotBlank(message = "用户名不能为空")
        @Size(max = 20, message = "用户名最长 20 字符")
        private String username;

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 128, message = "密码长度 6-128 字符")
        private String password;

        @Size(max = 30, message = "昵称最长 30 字符")
        private String displayName;

        /**
         * 浏览器设备指纹。
         *
         * <p>前端用 canvas / 字体列表 / 时区等算一个稳定字符串。为空时
         * 后端只走 IP 限制 —— 不强制要求，因为算指纹的脚本可能被拦。</p>
         */
        @Size(max = 128, message = "设备指纹过长")
        private String deviceId;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(String deviceId) {
            this.deviceId = deviceId;
        }
    }

    /** POST /api/auth/password 请求体 */
    public static class ChangePasswordRequest {

        @NotBlank(message = "原密码不能为空")
        private String oldPassword;

        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 128, message = "新密码长度 6-128 字符")
        private String newPassword;

        public String getOldPassword() {
            return oldPassword;
        }

        public void setOldPassword(String oldPassword) {
            this.oldPassword = oldPassword;
        }

        public String getNewPassword() {
            return newPassword;
        }

        public void setNewPassword(String newPassword) {
            this.newPassword = newPassword;
        }
    }
}
