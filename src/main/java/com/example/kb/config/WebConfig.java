package com.example.kb.config;

import com.example.kb.config.AuthInterceptor;
import com.example.kb.infrastructure.storage.LocalStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * Web 配置：CORS + 认证拦截器 + 前端静态托管 + 本地媒体目录。
 *
 * <p>前端 Vite 工程在 rag-kb-ui/，dev 时跑 5173，生产 pnpm build 后把 dist/ 拷到
 * src/main/resources/static/ 由 Spring Boot 托管（不引 nginx，2C2G 省 30MB）。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Vite dev server 默认端口，联调时前端直连后端 8080 需要放行 */
    private static final String DEV_ORIGIN = "http://localhost:5173";

    private final AuthInterceptor authInterceptor;

    /** 本地存储目录，和 StorageConfig 用的是同一个配置键 */
    private final String localMediaDir;

    public WebConfig(AuthInterceptor authInterceptor,
                     @Value("${rag.storage.local-dir:${java.io.tmpdir}/rag-kb-media}") String localMediaDir) {
        this.authInterceptor = authInterceptor;
        this.localMediaDir = localMediaDir;
    }

    /**
     * 注册认证拦截器。
     *
     * <p>⚠️ 只拦 {@code /api/**}，不拦静态资源和页面路由。
     * 如果拦 {@code /**}，主页和 index.html 也会被拦，游客就进不来了 ——
     * 这站的语义是「主页公开，管理功能需登录」。</p>
     *
     * <p>⚠️ {@link AuthInterceptor} 内部做的是「软认证 + 按路径判权」，
     * 不是「没 token 一律 401」。这里不重复配置白名单，避免两处规则打架。</p>
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 不做 excludePathPatterns：白名单逻辑集中在 AuthInterceptor 里，
                // 否则「哪条路径要登录」这件事散在两个文件里，改一处漏一处
                .order(0);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(DEV_ORIGIN, "http://127.0.0.1:5173")
                .allowedMethods("GET", "POST", "DELETE", "PUT", "OPTIONS")
                .allowedHeaders("*")
                // 带 Authorization 头跨域时需要，否则浏览器不暴露响应给 JS
                .exposedHeaders("Content-Type")
                .maxAge(3600);
    }

    /**
     * 静态资源：Spring Boot 默认已托管 classpath:/static/，
     * 这里补两件事：① 本地存储的媒体目录映射 ② history 模式路由回退由 view controller 负责。
     *
     * <p>⚠️ {@code /media/**} 映射到本地磁盘目录，只在 {@code rag.storage.type=local} 时有用。
     * 切 OSS 后图片走 OSS 的 https URL，这个映射没人访问（留着也不碍事，
     * 免得为了「切存储要改 WebConfig」而重新打包）。</p>
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 本地存储的文件（照片 / 项目截图 / 简历）
        // ⚠️ 必须是绝对路径形式的 file: URL，否则 Spring 会当成 classpath 相对路径
        String mediaDir = Path.of(localMediaDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler(LocalStorage.URL_PREFIX + "**")
                .addResourceLocations(mediaDir)
                .setCachePeriod(86400);

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true);
    }

    /**
     * history 模式路由回退。
     *
     * <p>⚠️ 不能用 addResourceHandlers 的 fallback（它只处理静态资源 404，
     * 不处理「路径存在但无对应文件」）。必须用 forward controller：
     * 把非 /api 的 GET 请求转发到 /index.html，让 Vue Router 接管。</p>
     *
     * <p>⚠️ 转发前要排除 /api 和 /actuator，否则会把 API 请求也吞进 index.html。</p>
     *
     * <p>路由清单要与 {@code rag-kb-ui/src/router/index.js} 保持一致：
     * 新增页面记得两边都加，否则刷新就 404。</p>
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 游客可见：个人主页（问答是页面上的浮窗，不是独立路由）
        registry.addViewController("/").setViewName("forward:/index.html");
        // 游客可见：日常动态全页（主页只展示最近几条，看全部要点进来）
        registry.addViewController("/moments").setViewName("forward:/index.html");
        // 站主可见：登录页 + 后台概览 + 文档管理 + 访客日志 + 个人内容 + 关于我 + 动态管理 + 留言
        registry.addViewController("/login").setViewName("forward:/index.html");
        registry.addViewController("/admin").setViewName("forward:/index.html");
        registry.addViewController("/admin/dashboard").setViewName("forward:/index.html");
        registry.addViewController("/admin/documents").setViewName("forward:/index.html");
        registry.addViewController("/admin/documents/{id:[0-9]+}").setViewName("forward:/index.html");
        registry.addViewController("/admin/media").setViewName("forward:/index.html");
        registry.addViewController("/admin/visits").setViewName("forward:/index.html");
        registry.addViewController("/admin/profile").setViewName("forward:/index.html");
        registry.addViewController("/admin/moments").setViewName("forward:/index.html");
        registry.addViewController("/admin/comments").setViewName("forward:/index.html");
        registry.addViewController("/admin/chats").setViewName("forward:/index.html");
        // 留言板（独立页面，游客也能看和发）
        registry.addViewController("/board").setViewName("forward:/index.html");
        // 注册
        registry.addViewController("/register").setViewName("forward:/index.html");
    }
}
