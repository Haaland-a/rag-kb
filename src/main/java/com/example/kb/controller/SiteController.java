package com.example.kb.controller;

import com.example.kb.common.R;
import com.example.kb.domain.vo.SiteInfo;
import com.example.kb.infrastructure.geo.ClientIpResolver;
import com.example.kb.service.ISiteService;
import com.example.kb.service.IVisitService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点公开信息。<b>匿名可访问</b>。
 *
 * <p>这一层只做三件事：取 IP、判断是不是站主、把结果包成 {@link R}。
 * 统计口径、字段组装全在 {@link ISiteService} —— 原来这里直接注入 Repository
 * 现场拼统计，是越层写法，已重构掉。</p>
 */
@RestController
@RequestMapping("/api/site")
public class SiteController {

    private final ISiteService siteService;
    private final IVisitService visitService;
    private final ClientIpResolver ipResolver;

    public SiteController(ISiteService siteService,
                          IVisitService visitService,
                          ClientIpResolver ipResolver) {
        this.siteService = siteService;
        this.visitService = visitService;
        this.ipResolver = ipResolver;
    }

    /**
     * 主页所需的全部公开数据，一次拿完。
     *
     * <p>拆成多个接口会让主页发好几个请求，2C2G 上不值得。数据都很小。</p>
     *
     * <p>⚠️ 统一用 {@code R} 包装：全站所有 JSON 接口都是
     * {@code {code, message, data}} 结构，前端才能用一个 axios 拦截器统一拆包。</p>
     */
    @GetMapping
    public R info(HttpServletRequest request) {
        String ip = ipResolver.resolve(request, visitService.isTrustProxy());
        boolean isAdmin = Boolean.TRUE.equals(request.getAttribute("authenticated"));
        SiteInfo info = siteService.build(ip, isAdmin, viewerId(request));
        return R.ok(info);
    }

    /**
     * 当前登录用户 id（游客返回 null）。
     *
     * <p>只有一种用途：让注册用户在自己的浏览器上看到「自己发的被屏蔽留言」。
     * 游客没有稳定身份，所以他的被屏蔽留言自己也看不到 —— 这是需求
     * 「屏蔽后留言者仍可见自己那条」在游客身份下的必然结果。</p>
     */
    private static Long viewerId(HttpServletRequest request) {
        Object v = request.getAttribute("userId");
        return (v instanceof Long l) ? l : null;
    }
}
