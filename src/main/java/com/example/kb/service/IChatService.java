package com.example.kb.service;

import com.example.kb.domain.vo.ChatQuota;
import com.example.kb.infrastructure.ratelimit.IpRateLimiter;
import com.example.kb.service.IChatModelPool;
import com.example.kb.service.IRetrievalService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

public interface IChatService {

    /** 一次流式回答：token 流 + 最终用到的模型名 */
    public static final class AnswerStream {
        private final Flux<String> tokens;
        private final AtomicReference<String> model;

        // ⚠️ 必须显式 public：嵌在接口里的类，构造器默认是包级私有（不是 public），
        //    而实现类在 service.impl 包，跨包 new 会报「不是公共的」。
        public AnswerStream(Flux<String> tokens, AtomicReference<String> model) {
            this.tokens = tokens;
            this.model = model;
        }

        public Flux<String> tokens() {
            return tokens;
        }

        /**
         * 实际答出这句话的模型名。
         *
         * <p>⚠️ 只有在流<b>订阅之后</b>才有值。Controller 里要放在
         * {@code Flux.defer(...)} 里读，不能在建 tail 时就取值。</p>
         */
        public String model() {
            return model.get();
        }
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
    public void consumeQuota(String ip, boolean isAdmin);

    /**
     * 当前 IP 的剩余配额。站主返回 -1（前端约定 -1 = 不限）。
     *
     * <p>只读，不消耗额度。</p>
     */
    public ChatQuota quotaOf(String ip, boolean isAdmin);

    /**
     * 只取剩余次数，不构造整个 {@link ChatQuota}。
     *
     * <p>SSE 的 done 帧只需要这一个数字，单独给个方法，省一次对象创建
     * （Controller 里原来写 {@code quotaOf(ip, false).getRemaining()} 也能跑，
     * 但语义上是在为一个整数造一整个对象）。</p>
     */
    public int remainingQuota(String ip, boolean isAdmin);

    /**
     * 检索一次，同时产出引用列表和 prompt 原文。
     *
     * <p>分两次检索（一次给前端、一次给 LLM）看起来更解耦，但两次之间文档
     * 可能被删除，前端渲染的 sources 数组和 LLM 看到的上下文就会对不上
     * （引用编号指向空内容）。一次检索是唯一正确做法。</p>
     */
    public IRetrievalService.Retrieved retrieve(String question, List<Long> documentIds, Integer topK);

    /** 模型池状态，给站主后台看 */
    public List<IChatModelPool.ModelStatus> modelPoolStatus();

    /**
     * 流式生成答案，失败自动换模型。
     *
     * @param context Controller 传进来的那批命中文档（与 sources 同一次检索），
     *                直接用于组 prompt，不再检索
     */
    public AnswerStream streamAnswer(String question, List<Document> context);

}
