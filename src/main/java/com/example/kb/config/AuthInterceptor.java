package com.example.kb.config;

import com.example.kb.infrastructure.security.TokenStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
/**
 * 认证拦截器：<b>匿名可访问 + 管理路径需登录</b>。
 *
 * <p>这个站的语义和普通后台不一样：</p>
 * <ul>
 *   <li><b>游客</b>（任何人）能看主页、能问 AI —— 不需要登录</li>
 *   <li><b>站主</b>（只有一个管理员账号，见 {@code rag.admin.username}）登录后才能上传/删除文档、看访客日志</li>
 * </ul>
 *
 * <p>所以策略是「先认人，再按路径判权」，而不是「没 token 一律 401」：</p>
 * <ol>
 *   <li>无论什么路径，先尝试解析 token。解析到就挂 request attribute，
 *       解析不到也不报错 —— 后面按路径决定要不要拦。</li>
 *   <li>命中 {@link #ADMIN_PATHS} 且没认证 → 401</li>
 *   <li>其余路径 → 放行（游客能进）</li>
 * </ol>
 *
 * <p>为什么用 HandlerInterceptor 而不是 Spring Security：需求只有「一个管理员」，
 * 没有角色体系、没有方法级注解。引全套 Security 要多 ~30MB 类加载 + 一堆
 * FilterChain 配置，2C2G 上不划算。</p>
 *
 * <p>⚠️ 为什么登出接口放行：登出要幂等 —— token 已过期时用户点「退出登录」
 * 也应该成功清掉本地 token，不然前端会卡在「退出失败」的怪状态。</p>
 *
 * <p>⚠️ 为什么用 request attribute 传 userId 而不是自定义 header：
 * header 是客户端发的，伪造一个 {@code X-User-Id: 1} 就能冒充站主；
 * attribute 是服务端内部传递，客户端改不了。</p>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenStore tokenStore;

    /** 放行且不需要认证的路径（精确匹配） */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            // 注册：游客当然能注册，否则永远注册不了
            "/api/auth/register",
            "/api/auth/logout",
            // /api/auth/me 特殊：没登录返回 200 + null，不算错误，前端用来判断状态
            "/api/auth/me",
            // ⚠️ 访客动态流本身没有结尾斜杠，前缀匹配 /api/moments/public/ 抓不到它，
            // 必须在这里单独列出（下次加公开端点时先想清楚有没有结尾斜杠）
            "/api/moments/public",
            // 留言板列表与提交：游客不注册也能留言，是需求的明确要求
            "/api/comments/public"
    );

    /**
     * 放行且不需要认证的路径<b>前缀</b>。
     *
     * <p>{@code /api/media/public/} —— 主页的照片墙、项目卡、简历列表，
     * 游客必须能看。这些端点内部只查 {@code visible=true} 的行，
     * 隐藏内容不会从这里漏出去。</p>
     *
     * <p>⚠️ 用前缀而不是逐个列出精确路径：每加一个公开端点就要记得改白名单，
     * 忘一次就是「主页某个区块 401」。而 {@code /public/} 这个分段是约定，
     * 看一眼代码就知道哪些是公开的。</p>
     *
     * <p>{@code /api/moments/public/} —— 日常动态的访客视角：
     * 动态流 {@code GET /api/moments/public} 和留言
     * {@code POST /api/moments/public/{id}/comments}。</p>
     */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/api/media/public/",
            "/api/moments/public/",
            // 留言板提交与「看自己在被屏蔽留言下的回复」都走这个前缀
            "/api/comments/public/"
    );

    /**
     * 只有站主能访问的路径前缀。
     *
     * <p>{@code /api/documents/**} —— 文档增删改查。游客不该看到你有哪些文档。
     * 注意：游客<b>能</b>通过 /api/chat 检索文档内容，但看不到文档列表本身。</p>
     *
     * <p>{@code /api/visit/**} —— 访客日志，只有站主能看。</p>
     *
     * <p>{@code /api/media/**} —— 照片墙、项目、简历的增删改。
     * 公开的那几个端点走 {@link #PUBLIC_PREFIXES}，在这个检查之前就放行了。</p>
     */
    private static final List<String> ADMIN_PATHS = List.of(
            "/api/documents",
            "/api/visit",
            "/api/media",
            // 留言后台（看全部含悄悄话、屏蔽、删除）。公开的前缀在上面已放行
            "/api/comments"
    );

    public AuthInterceptor(TokenStore tokenStore,
                           @Value("${rag.admin.username:demo}") String adminUsername) {
        this.tokenStore = tokenStore;
        this.adminUsername = adminUsername;
    }

    /** 站主用户名。判定「谁是站主」的唯一事实源就在这个字段 */
    private final String adminUsername;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String path = request.getRequestURI();

        // ① 先认人：不管什么路径，有有效 token 就挂上身份
        //    ⚠️ 这一步对游客是「静默失败」，不能报错
        String token = extractToken(request.getHeader("Authorization"));
        if (token != null) {
            Optional<TokenStore.Session> session = tokenStore.find(token);
            if (session.isPresent()) {
                TokenStore.Session s = session.get();
                request.setAttribute("authenticated", Boolean.TRUE);
                request.setAttribute("userId", s.userId());
                request.setAttribute("username", s.username());
                // 站主判定在这里做一次，下游 Controller 只消费 admin 这个 attribute
                request.setAttribute("admin",
                        s.username() != null && s.username().equalsIgnoreCase(adminUsername));
            }
        }

        // ② actuator 放行（部署探活用，且不含敏感信息）
        if (path.startsWith("/actuator/")) {
            return true;
        }

        // ③ 明确公开的路径放行
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }

        // ③b 公开前缀放行（如 /api/media/public/**）
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }

        // ④ 管理路径要求已认证
        if (isAdminPath(path) && request.getAttribute("authenticated") == null) {
            // 401 而不是 403：语义是「我没认出你是谁」，前端据此弹登录框
            writeUnauthorized(response);
            return false;
        }

        return true;
    }

    private static boolean isAdminPath(String path) {
        for (String prefix : ADMIN_PATHS) {
            // 精确匹配 /api/documents，或前缀匹配 /api/documents/...
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private static void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":401,\"message\":\"需要先登录\",\"data\":null}");
    }

    /**
     * 从 {@code Authorization: Bearer xxx} 取 token。
     *
     * <p>⚠️ 不用 {@code substring(7)} 的魔法数字：写成常量，
     * 将来改方案（比如加 Basic）时不会漏改这里。</p>
     *
     * <p>⚠️ 必须是 public：重构前和 AuthController 同在 user 包，包级可见就够；
     * 现在 AuthController 在 controller 包，跨包访问必须是 public。</p>
     */
    private static final String BEARER_PREFIX = "Bearer ";

    public static String extractToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).strip();
        return token.isEmpty() ? null : token;
    }
}
