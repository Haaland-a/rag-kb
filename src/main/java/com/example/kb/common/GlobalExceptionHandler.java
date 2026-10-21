package com.example.kb.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理。
 *
 * <p>原则：错误信息可以给用户看（不含堆栈），细节进日志。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<R> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数非法: {}", e.getMessage());
        return ResponseEntity.badRequest().body(R.fail(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<R> handleIllegalState(IllegalStateException e) {
        log.warn("状态非法: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(R.fail(e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R> handleMaxUpload(MaxUploadSizeExceededException e) {
        log.warn("上传文件超限: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(R.fail("文件过大，上限 50MB"));
    }

    /**
     * 限流拒绝（{@code IpRateLimiter} 抛的 429）。
     *
     * <p>⚠️ 必须显式处理：{@code ResponseStatusException} 继承自
     * {@code RuntimeException}，如果不单独拦，会被下面的 {@code Exception}
     * 处理器吞成 500，前端就分不清「被限流了」和「服务器挂了」——
     * 一个该显示「休息一下」的场景变成「系统错误」，体验差一个级别。</p>
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<R> handleResponseStatus(ResponseStatusException e) {
        String reason = e.getReason() == null ? "请求被拒绝" : e.getReason();
        log.info("请求被拒绝（{}）: {}", e.getStatusCode().value(), reason);
        return ResponseEntity.status(e.getStatusCode()).body(R.fail(reason));
    }

    /**
     * 静态资源找不到（{@code /media/xxx.jpg}、{@code /assets/xxx.js} 等）。
     *
     * <p>⚠️ 必须显式处理并返回 <b>404</b>：{@code NoResourceFoundException} 是
     * Spring 6.1 引入的，继承自 {@code ServletException}，如果不拦，
     * 会被 {@link #handleUnexpected} 兜成 500。后果有两个：</p>
     * <ol>
     *   <li>浏览器拿不到 404 就不会去走正常降级（比如 {@code <img>} 的 alt）</li>
     *   <li>日志被「未预期异常」刷屏 —— 访客每看一张已删的图就一条 ERROR</li>
     * </ol>
     *
     * <p>注意返回的是空 body 的 404，不是 R 包装：静态资源请求方是浏览器
     * （img/script 标签），它不认识 JSON。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResource(NoResourceFoundException e) {
        // 只记 debug：图片 404 是常态（缓存了已删除的图），不该占日志
        log.debug("静态资源不存在: {}", e.getResourcePath());
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(R.fail("服务内部错误，请查看日志"));
    }
}
