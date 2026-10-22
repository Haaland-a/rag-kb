package com.example.kb.service.impl;

import com.example.kb.domain.vo.ChatQuota;
import com.example.kb.infrastructure.ratelimit.IpRateLimiter;
import com.example.kb.service.IChatModelPool;
import com.example.kb.service.IChatService;
import com.example.kb.service.IRetrievalService;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

@Service
public class ChatServiceImpl implements IChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final IRetrievalService retrievalService;
    private final IChatModelPool modelPool;
    private final IpRateLimiter rateLimiter;

    public ChatServiceImpl(IRetrievalService retrievalService,
                       IChatModelPool modelPool,
                       IpRateLimiter rateLimiter) {
        this.retrievalService = retrievalService;
        this.modelPool = modelPool;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 检查并消耗一次问答额度。
     *
     * <p>⚠️ 这个方法原来写在 {@code ChatController} 里，属于业务规则外泄：
     * Controller 不该知道「站主不限流」「每小时 20 次」这些策略。
     * 搬到 Service 后，改配额策略不用碰 Controller。</p>
     *
     * <p>站主（带有效 token）不消耗额度 —— 自己试自己的站不该被自己挡住。</p>
     *
     * @throws ResponseStatusException 429 表示超额
     */
    public void consumeQuota(String ip, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        String rejection = rateLimiter.tryConsumeChat(ip);
        if (rejection != null) {
            log.info("IP {} 触发问答限流", ip);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, rejection);
        }
    }

    /**
     * 当前 IP 的剩余配额。站主返回 -1（前端约定 -1 = 不限）。
     *
     * <p>只读，不消耗额度。</p>
     */
    public ChatQuota quotaOf(String ip, boolean isAdmin) {
        ChatQuota quota = new ChatQuota();
        quota.setLimit(rateLimiter.getChatPerHour());
        quota.setRemaining(isAdmin ? -1 : rateLimiter.remainingChat(ip));
        quota.setAdmin(isAdmin);
        return quota;
    }

    /**
     * 只取剩余次数，不构造整个 {@link ChatQuota}。
     *
     * <p>SSE 的 done 帧只需要这一个数字，单独给个方法，省一次对象创建
     * （Controller 里原来写 {@code quotaOf(ip, false).getRemaining()} 也能跑，
     * 但语义上是在为一个整数造一整个对象）。</p>
     */
    public int remainingQuota(String ip, boolean isAdmin) {
        return isAdmin ? -1 : rateLimiter.remainingChat(ip);
    }

    /**
     * 检索一次，同时产出引用列表和 prompt 原文。
     *
     * <p>分两次检索（一次给前端、一次给 LLM）看起来更解耦，但两次之间文档
     * 可能被删除，前端渲染的 sources 数组和 LLM 看到的上下文就会对不上
     * （引用编号指向空内容）。一次检索是唯一正确做法。</p>
     */
    public IRetrievalService.Retrieved retrieve(String question, List<Long> documentIds, Integer topK) {
        return retrievalService.retrieve(question, documentIds, topK);
    }

    /** 模型池状态，给站主后台看 */
    public List<IChatModelPool.ModelStatus> modelPoolStatus() {
        return modelPool.status();
    }

    /** 一次流式回答：token 流 + 最终用到的模型名 */

    /**
     * 流式生成答案，失败自动换模型。
     *
     * @param context Controller 传进来的那批命中文档（与 sources 同一次检索），
     *                直接用于组 prompt，不再检索
     */
    public AnswerStream streamAnswer(String question, List<Document> context) {
        String prompt = buildPrompt(question, context);
        AtomicReference<String> usedModel = new AtomicReference<>();

        List<String> order = modelPool.order();
        if (order.isEmpty()) {
            log.error("模型池为空且没有兜底模型");
            return new AnswerStream(
                    Flux.error(new IllegalStateException("服务器暂时没有可用的模型，请稍后再试")),
                    usedModel);
        }

        log.debug("本次模型尝试顺序：{}", order);
        return new AnswerStream(attempt(order, 0, prompt, usedModel), usedModel);
    }

    /**
     * 尝试第 {@code idx} 个模型；还没吐字就失败则递归试下一个。
     *
     * <p>⚠️ 用递归而不是 for 循环：Flux 是惰性的，真正可能失败的时机在
     * <b>订阅之后</b>，用 for 循环没法在那个时候跳回循环体。</p>
     */
    private Flux<String> attempt(List<String> order,
                                 int idx,
                                 String prompt,
                                 AtomicReference<String> usedModel) {
        if (idx >= order.size()) {
            return Flux.error(new IllegalStateException("所有模型暂时都不可用，请稍后再试"));
        }
        String model = order.get(idx);
        AtomicInteger emitted = new AtomicInteger(0);

        Flux<String> one;
        try {
            // 不传 .options()：模型名、temperature、enable_thinking 都烤在
            // IChatModelPool 里每个 client 的默认 options 上了
            one = modelPool.clientFor(model)
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .stream()
                    .content();
        } catch (Exception e) {
            // 同步就炸了（模型名写错、client 构造失败），直接下一个
            modelPool.reportFailure(model, e);
            return attempt(order, idx + 1, prompt, usedModel);
        }

        return one
                .doOnSubscribe(s -> usedModel.set(model))
                .doOnNext(t -> emitted.incrementAndGet())
                .doOnComplete(() -> modelPool.reportSuccess(model))
                .onErrorResume(e -> {
                    modelPool.reportFailure(model, e);
                    int sent = emitted.get();
                    if (sent > 0) {
                        // 已经吐字了，换模型会拼出两段风格不同的回答 —— 宁可报错
                        log.warn("模型 {} 在输出中途失败（已发出 {} 个 token），不切换：{}",
                                model, sent, rootMessage(e));
                        return Flux.error(e);
                    }
                    log.info("模型 {} 建连阶段失败，静默切换到下一个：{}", model, rootMessage(e));
                    return attempt(order, idx + 1, prompt, usedModel);
                });
    }

    /**
     * 组装 prompt。
     *
     * <p>⚠️ 引用标注用 [1][2] 而不是文件名：LLM 复述文件名容易改字，
     * 编号是稳定锚点，前端把编号映射到 sources 数组下标。</p>
     */
    private String buildPrompt(String question, List<Document> context) {
        if (context.isEmpty()) {
            return question;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是检索到的资料片段：\n\n");
        for (int i = 0; i < context.size(); i++) {
            sb.append("[")
                    .append(i + 1)
                    .append("] ")
                    .append(context.get(i).getText())
                    .append("\n\n");
        }
        sb.append("请根据以上资料回答问题：").append(question);
        sb.append("\n\n回答中引用资料时用 [编号] 标注来源，例如「支持该功能 [1][3]」。");
        return sb.toString();
    }

    private static String rootMessage(Throwable t) {
        if (t == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 8) {
            String m = cur.getMessage();
            if (m != null && !m.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(cur.getClass().getSimpleName()).append(": ").append(m);
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return sb.length() == 0 ? t.toString() : sb.toString();
    }

    private static final String SYSTEM_PROMPT = """
            你是「Haalan 的知识库」问答助手。严格遵守：
            1. 只能依据下方「资料」内容回答，不得使用你自己的知识补充。
            2. 资料中没有的信息，明确回答「资料中未提及」，不要猜测。
            3. 回答引用资料时，在对应句子末尾用 [编号] 标注，编号与资料片段序号一致。
            4. 回答简洁、直接，不要复述问题，不要输出资料原文。
            5. 如果多个资料片段结论冲突，指出冲突并分别说明来源。
            """;
}
