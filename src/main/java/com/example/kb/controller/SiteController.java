package com.example.kb.controller;

import com.example.kb.common.R;
import com.example.kb.dto.site.SiteInfo;
import com.example.kb.infrastructure.geo.ClientIpResolver;
import com.example.kb.service.SiteService;
import com.example.kb.service.VisitService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点公开信息。<b>匿名可访问</b>。
 *
 * <p>这一层只做三件事：取 IP、判断是不是站主、把结果包成 {@link R}。
 * 统计口径、字段组装全在 {@link SiteService} —— 原来这里直接注入 Repository
 * 现场拼统计，是越层写法，已重构掉。</p>
 */
@RestController
@RequestMapping("/api/site")
public class SiteController {

    private final SiteService siteService;
    private final VisitService visitService;
    private final ClientIpResolver ipResolver;

    public SiteController(SiteService siteService,
                          VisitService visitService,
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
        SiteInfo info = siteService.build(ip, isAdmin);
        return R.ok(info);
    }
}
