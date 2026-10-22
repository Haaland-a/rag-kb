package com.example.kb.controller;

import com.example.kb.common.R;
import com.example.kb.domain.vo.ChatDoneEvent;
import com.example.kb.domain.vo.ChatQuota;
import com.example.kb.domain.dto.ChatRequest;
import com.example.kb.domain.vo.SourceRef;
import com.example.kb.infrastructure.geo.ClientIpResolver;
import com.example.kb.service.IChatLogService;
import com.example.kb.service.IChatService;
import com.example.kb.service.IRetrievalService;
import com.example.kb.service.IVisitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
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
 *   <li>按 IP 限流（规则在 {@link IChatService#consumeQuota}），默认每小时 20 次</li>
 *   <li>模型侧免费额度（阿里云百炼）是最终兜底</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final IChatService chatService;
    private final IChatLogService chatLogService;
    private final IVisitService visitService;
    private final ClientIpResolver ipResolver;

    public ChatController(IChatService chatService,
                          IChatLogService chatLogService,
                          IVisitService visitService,
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
        IRetrievalService.Retrieved retrieved = chatService.retrieve(
                request.getQuestion(), request.getDocumentIds(), request.getTopK());
        List<SourceRef> sources = retrieved.sources();
        List<Document> context = retrieved.context();

        log.info("问答检索命中 {} 条，ip={}，docIds={}", sources.size(), ip, request.getDocumentIds());

        // 先落一行问答记录，拿到 id。这样访客中途关页面时，
        // 「他问了什么」这条信息也留得下来（见 IChatLogService 类注释）
        Long logId = chatLogService.begin(ip, request.getQuestion(), sources, isAdmin);

        ChatDoneEvent done = new ChatDoneEvent();
        done.setSourceCount(sources.size());
        // 剩余配额给前端展示，让访客心里有数，而不是突然被拒
        done.setRemainingQuota(chatService.remainingQuota(ip, isAdmin));

        // 先发 sources 帧，再拼 token 流，最后 done
        Flux<ServerSentEvent<Object>> head = Flux.just(ServerSentEvent.builder()
                .event("sources")
                .data(sources)
                .build());

        IChatService.AnswerStream answer = chatService.streamAnswer(request.getQuestion(), context);
        String[] answerModel = new String[1];
        Flux<ServerSentEvent<Object>> tokens = answer.tokens()
                // 边发边累计，不发完再拼 —— 累计不碰数据库，成本只有一次 append
                .doOnNext(token -> chatLogService.append(logId, token))
                .doOnSubscribe(s -> answerModel[0] = answer.model())
                .map(token -> ServerSentEvent.builder()
                        .event("token")
                        .data(token)
                        .build())
                // token 可能是 null（某些模型在结束帧发空），过滤掉避免前端多渲染空串
                .filter(Objects::nonNull);

        // ⚠️ 用 defer：done 帧必须在流跑完之后才构造，
        // 否则 elapsedMs 永远是建连耗时、model 永远是 null
        Flux<ServerSentEvent<Object>> tail = Flux.defer(() -> {
            done.setElapsedMs(System.currentTimeMillis() - start);
            done.setModel(answerModel[0]);
            return Flux.just(ServerSentEvent.builder()
                    .event("done")
                    .data(done)
                    .build());
        });

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

    /**
     * 模型池状态（站主）。
     *
     * <p>为什么要暴露这个：额度是按模型分别给的，站主需要知道「现在还有几个
     * 模型能用」「哪个进了冷却」。否则访客说「网站答不出来」时，站主只能翻日志。</p>
     */
    @GetMapping("/models")
    public R models(HttpServletRequest httpRequest) {
        if (!Boolean.TRUE.equals(httpRequest.getAttribute("authenticated"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要先登录");
        }
        return R.ok(chatService.modelPoolStatus());
    }
}
