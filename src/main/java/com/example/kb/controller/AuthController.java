package com.example.kb.controller;

import com.example.kb.common.R;
import com.example.kb.config.AuthInterceptor;
import com.example.kb.domain.dto.AuthDtos.ChangePasswordRequest;
import com.example.kb.domain.dto.AuthDtos.LoginRequest;
import com.example.kb.domain.dto.AuthDtos.LoginResponse;
import com.example.kb.domain.dto.AuthDtos.RegisterRequest;
import com.example.kb.domain.dto.AuthDtos.UserInfo;
import com.example.kb.infrastructure.geo.ClientIpResolver;
import com.example.kb.service.IAuthService;
import com.example.kb.service.IVisitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
/**
 * 认证接口。
 *
 * <p>四个端点：</p>
 * <ul>
 *   <li>{@code POST /api/auth/login}  —— 登录换 token</li>
 *   <li>{@code POST /api/auth/logout} —— 登出（token 无效也返回成功）</li>
 *   <li>{@code GET  /api/auth/me}     —— 当前登录状态（<b>匿名可调</b>，未登录返回 null）</li>
 *   <li>{@code POST /api/auth/password} —— 改密（需登录）</li>
 * </ul>
 *
 * <p>token 走 {@code Authorization: Bearer <token>} 头，不放 cookie：
 * 前端 SSE 用 fetch 不用 axios，cookie 那套 SameSite/CSRF 配置反而更麻烦。</p>
 *
 * <p>⚠️ {@code /api/auth/me} 是<b>匿名可访问</b>的，故意不返回 401：
 * 前端进主页时要先问「我登录了吗」，如果未登录就 401，浏览器控制台会红一片，
 * 而且 axios 拦截器会把它当成登录失效触发跳转。返回 200 + null 干净得多。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final IAuthService authService;
    private final ClientIpResolver ipResolver;
    private final IVisitService visitService;

    public AuthController(IAuthService authService,
                          ClientIpResolver ipResolver,
                          IVisitService visitService) {
        this.authService = authService;
        this.ipResolver = ipResolver;
        this.visitService = visitService;
    }

    /**
     * 登录。
     *
     * <p>失败统一返回「用户名或密码错误」，不区分用户不存在和密码错 ——
     * 防止用户枚举。</p>
     *
     * <p>⚠️ 返回值必须包 {@code R}，与全站其他接口保持一致。前端统一按
     * {@code res.data.data} 取值，某个端点裸返回会导致取到 undefined。</p>
     *
     * <p>⚠️ 响应里带上 {@code admin} 和 {@code displayName}：前端靠 admin
     * 决定「登录后跳后台还是回主页」。不给就会导致普通用户登录后被弹进
     * 后台再被踢出来的难看流程。</p>
     */
    @PostMapping("/login")
    public R login(@Valid @RequestBody LoginRequest request) {
        IAuthService.LoginSuccess success = authService
                .authenticate(request.getUsername(), request.getPassword())
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        return R.ok(toLoginResponse(success));
    }

    /** 登出。幂等：token 已失效也返回成功 */
    @PostMapping("/logout")
    public R logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(AuthInterceptor.extractToken(authorization));
        return R.ok(null);
    }

    /**
     * 注册一个普通用户。
     *
     * <p>⚠️ <b>注册用户不是管理员</b>。管理权限靠路径白名单 +
     * {@code requireAdmin()} 拦，和这个端点无关。</p>
     *
     * <p>设备限制：{@code deviceId} 由前端算好传来（canvas 指纹之类的稳定串）。
     * 数据库有部分唯一索引兜底，Service 里再查一次是为了给出人话提示。
     * 不传也能注册 —— 那就只剩同 IP 24 小时限 2 个这道软限制。</p>
     */
    @PostMapping("/register")
    public R register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        String ip = ipResolver.resolve(request, visitService.isTrustProxy());
        long id = authService.register(body.getUsername(), body.getPassword(),
                body.getDisplayName(), body.getDeviceId(), ip);
        // 注册完直接登录，省用户再输一遍
        IAuthService.LoginSuccess success = authService
                .authenticate(body.getUsername(), body.getPassword())
                .orElseThrow(() -> new IllegalStateException("注册成功但自动登录失败，请手动登录"));
        return R.ok(toLoginResponse(success, id));
    }

    /**
     * Service 的成功对象 → 接口响应。
     *
     * <p>⚠️ 必须在这里转一次而不是直接返回 Service 的 record：
     * Service 的 record 是内部类型，直接返回等于把内部结构当接口契约，
     * 将来加字段会连带改前端。</p>
     */
    private static LoginResponse toLoginResponse(IAuthService.LoginSuccess s) {
        return toLoginResponse(s, s.userId());
    }

    private static LoginResponse toLoginResponse(IAuthService.LoginSuccess s, long userId) {
        LoginResponse resp = new LoginResponse();
        resp.setToken(s.token());
        resp.setUserId(userId);
        resp.setUsername(s.username());
        resp.setDisplayName(s.displayName());
        resp.setAdmin(s.admin());
        return resp;
    }

    /**
     * 当前登录状态。<b>匿名可访问</b>。
     *
     * <p>⚠️ 返回 {@code R} 而不是裸 {@code UserInfo}：未登录时裸返回 null，
     * Spring 会给出一个<b>空 body</b>（不是 JSON {@code null}），
     * 前端 {@code res.data.data} 取到 undefined，看起来像网络出错。
     * 包一层 R 后永远是合法 JSON：{@code {"code":0,"message":"ok","data":null}}。</p>
     *
     * @return 已登录时 data 是用户信息；未登录时 data 为 null
     */
    @GetMapping("/me")
    public R me(HttpServletRequest request) {
        Object userIdAttr = request.getAttribute("userId");
        if (!(userIdAttr instanceof Long userId)) {
            // 未登录不是错误，前端靠这个 null 判断要不要显示「登录」按钮
            return R.ok(null);
        }
        // 用户被删了但 token 还在内存里 —— 按未登录处理
        return R.ok(authService.currentUserInfo(userId).orElse(null));
    }

    /**
     * 改密。成功后所有已登录会话失效（包括当前这个），需重新登录。
     */
    @PostMapping("/password")
    public R changePassword(HttpServletRequest request,
                            @Valid @RequestBody ChangePasswordRequest body) {
        Object userIdAttr = request.getAttribute("userId");
        if (!(userIdAttr instanceof Long userId)) {
            // 正常情况走不到这里（拦截器已经拦了），防御性写法
            throw new IllegalStateException("未登录");
        }
        boolean ok = authService.changePassword(
                userId, body.getOldPassword(), body.getNewPassword());
        if (!ok) {
            throw new IllegalArgumentException("原密码错误");
        }
        return R.ok(null);
    }

    /**
     * 用户列表（站主专用）。
     *
     * <p>放在 {@code /api/auth} 下而不是新开一个 {@code /api/users}：这个接口读的是
     * 认证体系里的账号表，和 login/register/me/password 是同一组东西，
     * 拆到另一个前缀只会让「账号相关的东西在哪」多一个记忆点。</p>
     *
     * <p>⚠️ 必须显式判 {@code admin} attribute，不能只靠 AuthInterceptor：
     * {@code /api/auth/**} 里登录/注册/me 都是公开的，所以这个前缀不在
     * {@code ADMIN_PATHS} 里，拦截器只保证「认证过」不保证「是站主」。
     * 少了这一判就是<b>任何注册用户都能看到全部账号</b>。</p>
     *
     * <p>⚠️ 返回体是 {@link com.example.kb.domain.vo.UserPage}，里面的每一项是
     * {@code UserRowVO} —— 没有 passwordHash、没有 deviceId。</p>
     */
    @GetMapping("/users")
    public R users(HttpServletRequest request,
                   @RequestParam(value = "page", defaultValue = "1") int page,
                   @RequestParam(value = "size", defaultValue = "20") int size) {
        if (!Boolean.TRUE.equals(request.getAttribute("admin"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有站主能看用户列表");
        }
        return R.ok(authService.listUsers(page, size));
    }
}
