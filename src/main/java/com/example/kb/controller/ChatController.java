package com.example.kb.controller;

import com.example.kb.common.R;
import com.example.kb.dto.chat.ChatDoneEvent;
import com.example.kb.dto.chat.ChatQuota;
import com.example.kb.dto.chat.ChatRequest;
import com.example.kb.dto.chat.SourceRef;
import com.example.kb.infrastructure.geo.ClientIpResolver;
import com.example.kb.service.ChatLogService;
import com.example.kb.service.ChatService;
import com.example.kb.service.RetrievalService;
import com.example.kb.service.VisitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * SSE 流式问答接口。
 *
 * <p>协议（与前端 src/api/index.js 的 chatApi.stream 对齐）：</p>
 * <pre>
 * event: sources
 * data: [{chunkId, documentId, filename, pageNumber, headingPath, score, preview}, ...]
 *
 * event: token
 * data: "回答片段"
 *
 * event: done
 * data: {"sourceCount":5,"chatTokens":0,"elapsedMs":1234,"remainingQuota":19}
 * </pre>
 *
 * <p>⚠️ 用 {@code Flux<ServerSentEvent<Object>>} 而不是 {@code Flux<String>}：
 * 后者 SSE 的 data 行必须单行，JSON 里带 \n 会被协议拆坏。ServerSentEvent
 * 会帮我们按帧序列化，且能带 event: 字段。</p>
 *
 * <p>⚠️ 检索在 Controller 里同步做而不是放进 Flux：检索失败要能返回 400/500
 * 而不是一个 error 帧 —— 前端对 HTTP 状态码的处理更直接。</p>
 *
 * <h3>这个接口是<b>匿名可访问</b>的</h3>
 * <p>别人的网站访客不需要登录就能问问题，这是需求本身。代价是被刷的风险，
 * 所以有两道闸：</p>
 * <ol>
 *   <li>按 IP 限流（规则在 {@link ChatService#consumeQuota}），默认每小时 20 次</li>
 *   <li>模型侧免费额度（阿里云百炼）是最终兜底</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final ChatLogService chatLogService;
    private final VisitService visitService;
    private final ClientIpResolver ipResolver;

    public ChatController(ChatService chatService,
                          ChatLogService chatLogService,
                          VisitService visitService,
                          ClientIpResolver ipResolver) {
        this.chatService = chatService;
        this.chatLogService = chatLogService;
        this.visitService = visitService;
        this.ipResolver = ipResolver;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(@Valid @RequestBody ChatRequest request,
                                                HttpServletRequest httpRequest) {
        long start = System.currentTimeMillis();

        String ip = ipResolver.resolve(httpRequest, visitService.isTrustProxy());
        boolean isAdmin = Boolean.TRUE.equals(httpRequest.getAttribute("authenticated"));

        // 限流规则在 Service 里，Controller 只管调用（超额时它抛 429）
        chatService.consumeQuota(ip, isAdmin);

        // 记录这次访问。问题原文也存下来 —— 站主最想知道「别人都在问什么」
        visitService.record(ip,
                httpRequest.getRequestURI(),
                request.getQuestion(),
                httpRequest.getHeader("User-Agent"),
                httpRequest.getHeader("Referer"),
                isAdmin);

        // 一次检索，同时拿到「给前端的引用」和「给 LLM 的原文」
        RetrievalService.Retrieved retrieved = chatService.retrieve(
                request.getQuestion(), request.getDocumentIds(), request.getTopK());
        List<SourceRef> sources = retrieved.sources();
        List<Document> context = retrieved.context();

        log.info("问答检索命中 {} 条，ip={}，docIds={}", sources.size(), ip, request.getDocumentIds());

        // 先落一行问答记录，拿到 id。这样访客中途关页面时，
        // 「他问了什么」这条信息也留得下来（见 ChatLogService 类注释）
        Long logId = chatLogService.begin(ip, request.getQuestion(), sources, isAdmin);

        ChatDoneEvent done = new ChatDoneEvent();
        done.setSourceCount(sources.size());
        done.setElapsedMs(System.currentTimeMillis() - start);
        // 剩余配额给前端展示，让访客心里有数，而不是突然被拒
        done.setRemainingQuota(chatService.remainingQuota(ip, isAdmin));

        // 先发 sources 帧，再拼 token 流，最后 done
        Flux<ServerSentEvent<Object>> head = Flux.just(ServerSentEvent.builder()
                .event("sources")
                .data(sources)
                .build());

        Flux<ServerSentEvent<Object>> tokens = chatService.streamAnswer(request.getQuestion(), context)
                // 边发边累计，不发完再拼 —— 累计不碰数据库，成本只有一次 append
                .doOnNext(token -> chatLogService.append(logId, token))
                .map(token -> ServerSentEvent.builder()
                        .event("token")
                        .data(token)
                        .build())
                // token 可能是 null（某些模型在结束帧发空），过滤掉避免前端多渲染空串
                .filter(Objects::nonNull);

        Flux<ServerSentEvent<Object>> tail = Flux.just(ServerSentEvent.builder()
                .event("done")
                .data(done)
                .build());

        // ⚠️ doFinally 而不是 doOnComplete：访客关掉页面时 Flux 被取消，
        // 只有 doFinally 保证跑得到。complete 标志据此区分「答完了」和「中途走了」
        return head.concatWith(tokens).concatWith(tail)
                .doFinally(signal -> chatLogService.finish(
                        logId, signal == reactor.core.publisher.SignalType.ON_COMPLETE));
    }

    /**
     * 查询当前 IP 的剩余配额。
     *
     * <p>前端进主页时调一次，用于显示「今天还可以问 N 次」。
     * 匿名可访问 —— 它不泄露任何别人的信息。</p>
     */
    @GetMapping("/quota")
    public R quota(HttpServletRequest httpRequest) {
        String ip = ipResolver.resolve(httpRequest, visitService.isTrustProxy());
        boolean isAdmin = Boolean.TRUE.equals(httpRequest.getAttribute("authenticated"));
        ChatQuota quota = chatService.quotaOf(ip, isAdmin);
        return R.ok(quota);
    }
}
