package com.example.kb.service;

import com.example.kb.dto.chat.ChatQuota;
import com.example.kb.dto.chat.SourceRef;
import com.example.kb.infrastructure.ratelimit.IpRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 问答编排：检索 → 组装 prompt → 流式生成。
 *
 * <p>prompt 策略（md 第 6.5 节）：system 里放「只根据资料回答 + 引用标注」的约束，
 * user 里放编号后的上下文 + 问题。上下文用 <b>vector_store 命中的 content</b>
 * （见 RetrievalService 注释）。</p>
 *
 * <p>⚠️ ChatClient 不自己建 bean（自动配置已装好），这里注入 {@link ChatClient.Builder}
 * 再 build —— 这样不会和自动配置抢 bean。</p>
 *
 * <h3>为什么需要 extraBody 关掉「思考模式」</h3>
 * <p>阿里云百炼的 qwen3.x / deepseek-v4.1 默认是<b>推理模型</b>：先吐几十段
 * {@code reasoning_content}，再吐 {@code content}。实测（服务器上 curl）：</p>
 * <pre>
 * qwen3.8-flash       思考开 → 1860ms，first_content=""（空）
 * qwen3.8-flash       思考关 → 1176ms，first_content 有字
 * deepseek-v4.1-flash 思考开 → 2408ms，全文 25 段 reasoning + 1 段 content
 * </pre>
 * <p>对「别人打开小窗口问一句」这个场景，思考模式意味着十几秒白屏然后一次性蹦出来，
 * 体验极差。所以统一用 {@code enable_thinking: false} 关掉。这个参数不是 OpenAI
 * 标准字段，Spring AI 没有对应属性，但 {@code OpenAiChatOptions#getExtraBody()}
 * 上有 {@code @JsonAnyGetter}，会被扁平化写进请求体顶层 —— 这是官方留的透传通道。</p>
 *
 * <p>⚠️ 换成不认 {@code enable_thinking} 的服务商（比如纯 OpenAI）时，
 * 把 {@code rag.chat.disable-thinking} 设成 false 即可，否则可能报 400 参数错误。</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final RetrievalService retrievalService;
    private final ChatClient.Builder chatClientBuilder;
    private final IpRateLimiter rateLimiter;

    /** 是否给模型传 enable_thinking:false。百炼/硅基流动的推理模型都需要 */
    private final boolean disableThinking;

    public ChatService(RetrievalService retrievalService,
                       ChatClient.Builder chatClientBuilder,
                       IpRateLimiter rateLimiter,
                       @Value("${rag.chat.disable-thinking:true}") boolean disableThinking) {
        this.retrievalService = retrievalService;
        this.chatClientBuilder = chatClientBuilder;
        this.rateLimiter = rateLimiter;
        this.disableThinking = disableThinking;
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
    public RetrievalService.Retrieved retrieve(String question, List<Long> documentIds, Integer topK) {
        return retrievalService.retrieve(question, documentIds, topK);
    }

    /**
     * 流式生成答案。
     *
     * @param context Controller 传进来的那批命中文档（与 sources 同一次检索），
     *                直接用于组 prompt，不再检索
     */
    public Flux<String> streamAnswer(String question, List<Document> context) {
        String prompt = buildPrompt(question, context);

        log.debug("prompt 长度 {} 字", prompt.length());
        return chatClientBuilder.build()
                .prompt()
                .options(buildOptions())
                .system(SYSTEM_PROMPT)
                .user(prompt)
                .stream()
                .content();
    }

    /**
     * 每次请求带一份 options。
     *
     * <p>⚠️ 不做成 bean 单例：ChatOptions 会被 Spring AI 内部 copy 后塞进请求，
     * 单例共享一个可变对象在并发下没有必要冒这个险。</p>
     */
    private ChatOptions buildOptions() {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        if (disableThinking) {
            // @JsonAnyGetter 会把这个 map 的键值平铺到请求体顶层
            builder.extraBody(Map.of("enable_thinking", false));
        }
        return builder.build();
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

    private static final String SYSTEM_PROMPT = """
            你是这个知识库的问答助手。严格遵守：
            1. 只能依据下方「资料」内容回答，不得使用你自己的知识补充。
            2. 资料中没有的信息，明确回答「资料中未提及」，不要猜测。
            3. 回答引用资料时，在对应句子末尾用 [编号] 标注，编号与资料片段序号一致。
            4. 回答简洁、直接，不要复述问题，不要输出资料原文。
            5. 如果多个资料片段结论冲突，指出冲突并分别说明来源。
            """;
}
