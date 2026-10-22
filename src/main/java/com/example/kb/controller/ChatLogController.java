package com.example.kb.controller;

import com.example.kb.common.R;
import com.example.kb.domain.vo.ChatLogDetailDto;
import com.example.kb.domain.vo.ChatPage;
import com.example.kb.service.IChatLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台「问答记录」接口（仅站主）。
 *
 * <p>别人在我站上问了什么、我怎么答的 —— 这是需求里「聊天记录需要保存在后台」的落地。</p>
 *
 * <p>⚠️ 这类端点<b>不在</b> {@code AuthInterceptor.ADMIN_PATHS} 里，
 * 因为 {@code /api/chat} 不能整体拦（{@code /api/chat/stream} 和
 * {@code /api/chat/quota} 必须匿名可访问）。所以每个方法都显式调
 * {@link #requireAdmin}，统一入口避免漏检查。</p>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatLogController {

    private static final int DEFAULT_LIMIT = 20;

    private final IChatLogService chatLogService;

    public ChatLogController(IChatLogService chatLogService) {
        this.chatLogService = chatLogService;
    }

    /**
     * 问答记录列表（倒序分页）。
     *
     * @param limit       每页条数，服务端夹到 1..100
     * @param offset      偏移量
     * @param onlyVisitor true（默认）= 只看访客的提问，排除站主自己调试时的记录
     */
    @GetMapping("/logs")
    public R logs(@RequestParam(defaultValue = "0") int offset,
                  @RequestParam(defaultValue = "20") int limit,
                  @RequestParam(defaultValue = "true") boolean onlyVisitor,
                  HttpServletRequest request) {
        requireAdmin(request);
        ChatPage page = chatLogService.recent(
                limit <= 0 ? DEFAULT_LIMIT : limit, offset, onlyVisitor);
        return R.ok(page);
    }

    /** 单条详情：带回答全文和引用来源明细 */
    @GetMapping("/logs/{id}")
    public R detail(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        ChatLogDetailDto dto = chatLogService.detail(id)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在或已删除"));
        return R.ok(dto);
    }

    /** 删一条 */
    @DeleteMapping("/logs/{id}")
    public R delete(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        int n = chatLogService.delete(id);
        return R.ok(java.util.Map.of("deleted", n));
    }

    /**
     * 清空全部问答记录。
     *
     * <p>⚠️ 用 POST 不用 DELETE：DELETE 带不了「确认」语义，
     * 而清空是不可逆的。前端弹二次确认后再调这个。</p>
     */
    @PostMapping("/logs/clear")
    public R clear(HttpServletRequest request) {
        requireAdmin(request);
        int n = chatLogService.deleteAll();
        return R.ok(java.util.Map.of("deleted", n));
    }

    /**
     * 站主断言。
     *
     * <p>抛 {@code ResponseStatusException(401)} 而不是 400：
     * 前端 axios 拦截器按 401 处理登出，状态码必须准。</p>
     */
    private static void requireAdmin(HttpServletRequest request) {
        if (!Boolean.TRUE.equals(request.getAttribute("authenticated"))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "需要先登录");
        }
    }
}
