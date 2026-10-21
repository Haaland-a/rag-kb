package com.example.kb.controller;

import com.example.kb.common.R;
import com.example.kb.dto.visit.VisitPage;
import com.example.kb.dto.visit.VisitStats;
import com.example.kb.dto.visit.VisitorSummary;
import com.example.kb.service.VisitService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 访客日志查询接口。<b>只有站主能访问</b>（AuthInterceptor 拦 /api/visit/**）。
 *
 * <p>三个端点：</p>
 * <ul>
 *   <li>{@code GET /api/visit} —— 倒序分页的原始访问流水</li>
 *   <li>{@code GET /api/visit/summary} —— 按 IP 聚合，看谁来得最勤</li>
 *   <li>{@code GET /api/visit/stats} —— 总量统计，给仪表盘用</li>
 * </ul>
 *
 * <p>⚠️ 这一层<b>不注入 Repository</b>：分页上限、聚合口径、容错策略都在
 * {@link VisitService}。重构前这里是 Controller 直连 {@code VisitRepository}
 * 并现场 {@code Map.of(...)}，属于越层 + 弱类型，现已改正。</p>
 */
@RestController
@RequestMapping("/api/visit")
public class VisitController {

    private final VisitService visitService;

    public VisitController(VisitService visitService) {
        this.visitService = visitService;
    }

    /** 倒序分页的访问流水 */
    @GetMapping
    public R list(@RequestParam(defaultValue = "50") int limit,
                  @RequestParam(defaultValue = "0") int offset) {
        VisitPage page = visitService.recent(limit, offset);
        return R.ok(page);
    }

    /** 按 IP 聚合：谁来过、来了几次、最近一次什么时候、从哪来 */
    @GetMapping("/summary")
    public R summary(@RequestParam(defaultValue = "50") int limit) {
        List<VisitorSummary> rows = visitService.summary(limit);
        return R.ok(rows);
    }

    /** 仪表盘统计 */
    @GetMapping("/stats")
    public R stats() {
        VisitStats stats = visitService.stats();
        return R.ok(stats);
    }
}
