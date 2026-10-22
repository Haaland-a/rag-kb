package com.example.kb.service;

import com.example.kb.config.ChatPoolProperties;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 聊天模型池：多模型轮转 + 额度耗尽自动切换。
 *
 * <h3>为什么需要这个</h3>
 * <p>阿里云百炼的免费额度是<b>按模型分别发放</b>的 —— 每个模型各 100 万 tokens。
 * 用光一个模型的额度，它就返回 403 {@code AllocationQuota.FreeTierOnly}，但别的
 * 模型还有额度。单个 {@code model:} 配置只能吃一份，用完网站就哑了。</p>
 *
 * <h3>调度策略：轮转（round-robin）+ 跳过冷却中的</h3>
 * <p>不用「永远打第一个直到它挂」的理由：第一个模型的额度会被迅速打光，然后
 * 才切第二个，等于把「每天挂一次」变成「每小时挂一次」。轮转让 N 个模型的额度
 * 同步消耗，整体可用时长直接乘 N。</p>
 *
 * <p>也不用「按剩余额度加权」：百炼没有查剩余额度的公开 API（只有控制台能看到），
 * 硬要查就得爬 HTML，脆弱且要额外鉴权。轮转 + 失败冷却已经够用。</p>
 *
 * <h3>失败分类（决定冷却多久）</h3>
 * <table border="1">
 *   <tr><th>类型</th><th>判定</th><th>冷却</th></tr>
 *   <tr><td>额度耗尽</td><td>403 + 报文含 AllocationQuota / FreeTierOnly / quota</td>
 *       <td>6 小时（等它自然恢复）</td></tr>
 *   <tr><td>限流</td><td>429</td><td>60 秒</td></tr>
 *   <tr><td>其它错误</td><td>5xx / 超时 / 连接失败 / 解析失败</td><td>5 分钟</td></tr>
 * </table>
 *
 * <h3>后台探测</h3>
 * <p>冷却到期的模型不是直接放回池子（可能它还是满的，放回去等于每 6 小时让
 * 用户踩一次 403）。{@link #probeExpired()} 用一个 {@code max_tokens=1} 的
 * 非流式请求先试 —— 通了才回池。1 个 token 的成本，可以忽略。</p>
 *
 * <h3>2C2G 约束</h3>
 * <p>所有模型<b>共用同一个 {@link OpenAiApi}</b>（共享底层 HTTP 客户端与连接池），
 * 每个模型只多一个 {@code OpenAiChatOptions} + 一个 {@code ChatClient} 对象，
 * 几百字节而已。不存在「一个模型一套连接池」的问题。</p>
 */
@Service
public class ChatModelPool {

    private static final Logger log = LoggerFactory.getLogger(ChatModelPool.class);

    private final ChatPoolProperties properties;
    private final OpenAiApi openAiApi;
    private final ToolCallingManager toolCallingManager;
    private final String fallbackModel;
    private final double temperature;

    /**
     * 全局开关：是否给模型传 {@code enable_thinking:false}。
     *
     * <p>单个模型还能用 {@code supports-disable-thinking: false} 单独豁免 ——
     * 百炼实测有模型只接受 true，传 false 直接 400。</p>
     */
    private final boolean disableThinking;

    /** 模型名 → 运行时状态。只在池子里的模型会出现 */
    private final Map<String, Slot> slots = new LinkedHashMap<>();

    /** 轮转游标，每次取模型时 +1 */
    private final AtomicInteger cursor = new AtomicInteger();

    public ChatModelPool(ChatPoolProperties properties,
                         OpenAiApi openAiApi,
                         ToolCallingManager toolCallingManager,
                         @Value("${spring.ai.openai.chat.options.model:qwen3.7-flash}") String fallbackModel,
                         @Value("${spring.ai.openai.chat.options.temperature:0.2}") double temperature,
                         @Value("${rag.chat.disable-thinking:true}") boolean disableThinking) {
        this.properties = properties;
        this.openAiApi = openAiApi;
        this.toolCallingManager = toolCallingManager;
        this.fallbackModel = fallbackModel;
        this.temperature = temperature;
        this.disableThinking = disableThinking;

        List<ChatPoolProperties.Model> configured = properties.getModels();
        if (configured.isEmpty()) {
            // 池子没配 → 退回单模型。造一个等价的槽位，让上层代码只有一条路径
            ChatPoolProperties.Model m = new ChatPoolProperties.Model();
            m.setName(fallbackModel);
            slots.put(fallbackModel, new Slot(m, true));
            log.info("模型池未配置，退回单模型：{}", fallbackModel);
        } else {
            for (ChatPoolProperties.Model m : configured) {
                if (m.getName() == null || m.getName().isBlank()) {
                    continue;
                }
                slots.put(m.getName(), new Slot(m, m.isEnabled()));
            }
            log.info("模型池已加载 {} 个模型：{}", slots.size(), slots.keySet());
        }
    }

    /**
     * 给出这次请求的模型尝试顺序。
     *
     * <p>规则：可用的按轮转起点排前面，冷却中的排最后（万一全挂了，宁可碰一次
     * 冷却中的也别直接给用户 503）。</p>
     */
    public List<String> order() {
        long now = System.currentTimeMillis();
        List<String> healthy = new ArrayList<>();
        List<String> cooling = new ArrayList<>();

        List<Slot> all = new ArrayList<>(slots.values());
        for (Slot s : all) {
            if (!s.enabled) {
                continue;
            }
            if (s.availableAt <= now) {
                healthy.add(s.model.getName());
            } else {
                cooling.add(s.model.getName());
            }
        }

        if (healthy.isEmpty()) {
            // 全在冷却里：按冷却到期时间从早到晚排，先试最快恢复的
            cooling.sort((a, b) -> Long.compare(slots.get(a).availableAt, slots.get(b).availableAt));
            return cooling;
        }

        // 轮转：把 healthy 按游标旋转一个偏移，让负载均摊
        int offset = Math.floorMod(cursor.getAndIncrement(), healthy.size());
        List<String> ordered = new ArrayList<>(healthy.size() + cooling.size());
        for (int i = 0; i < healthy.size(); i++) {
            ordered.add(healthy.get((offset + i) % healthy.size()));
        }
        ordered.addAll(cooling);
        return ordered;
    }

    /** 取某个模型的 ChatClient（首次调用时构造并缓存） */
    public ChatClient clientFor(String modelName) {
        Slot s = slots.get(modelName);
        if (s == null) {
            throw new IllegalStateException("模型不在池中：" + modelName);
        }
        ChatClient c = s.client;
        if (c != null) {
            return c;
        }
        synchronized (s) {
            if (s.client == null) {
                s.client = buildClient(s);
            }
            return s.client;
        }
    }

    private ChatClient buildClient(Slot s) {
        OpenAiChatOptions.Builder ob = OpenAiChatOptions.builder()
                .model(s.model.getName());
        if (s.model.isSupportsTemperature()) {
            ob.temperature(temperature);
        }
        // 不支持 temperature 的模型（实测 kimi-k3）连这个字段都不能出现在请求体里，
        // 否则 400 InvalidParameter。这里靠「不调 .temperature()」让 options 里是 null，
        // Jackson 序列化时字段直接不写。
        if (s.model.isSupportsDisableThinking() && disableThinking) {
            // 百炼/硅基流动的推理模型默认开思考，先吐几十段 reasoning_content 再吐正文。
            // extraBody 上有 @JsonAnyGetter，会被扁平化写进请求体顶层
            ob.extraBody(Map.of("enable_thinking", false));
        }
        // ⚠️ RetryTemplate 用 maxAttempts=1：切换模型的决策在 ChatService 里做，
        // 这里再重试 3 次只会把「一个坏模型」放大成「三倍等待时间」——
        // 用户看到的是转圈更久，而不是换个模型答出来。
        RetryTemplate noRetry = RetryTemplate.builder().maxAttempts(1).build();
        ChatModel chatModel = new OpenAiChatModel(
                openAiApi,
                ob.build(),
                toolCallingManager,
                noRetry,
                ObservationRegistry.NOOP);
        return ChatClient.builder(chatModel).build();
    }

    /**
     * 上报一次失败，进入冷却。
     *
     * @param modelName 出问题的模型
     * @param error     原始异常（用来分类）
     */
    public void reportFailure(String modelName, Throwable error) {
        Slot s = slots.get(modelName);
        if (s == null) {
            return;
        }
        long now = System.currentTimeMillis();
        FailureKind kind = classify(error);
        long cooldownMs = switch (kind) {
            case QUOTA -> properties.getQuotaCooldownSeconds() * 1000L;
            case RATE_LIMIT -> properties.getRateLimitCooldownSeconds() * 1000L;
            case OTHER -> properties.getErrorCooldownSeconds() * 1000L;
        };
        s.availableAt = now + cooldownMs;
        s.lastError = error == null ? null : rootMessage(error);
        s.lastErrorKind = kind;
        s.failCount++;

        log.warn("模型 {} 失败（{}），冷却 {} 秒：{}",
                modelName, kind, cooldownMs / 1000, s.lastError);
    }

    /** 上报一次成功，清掉冷却与错误信息 */
    public void reportSuccess(String modelName) {
        Slot s = slots.get(modelName);
        if (s == null) {
            return;
        }
        if (s.failCount > 0) {
            log.info("模型 {} 恢复可用", modelName);
        }
        s.availableAt = 0;
        s.failCount = 0;
        s.lastError = null;
        s.lastErrorKind = null;
    }

    /** 池子当前状态，给站主后台看 */
    public List<ModelStatus> status() {
        long now = System.currentTimeMillis();
        List<ModelStatus> out = new ArrayList<>(slots.size());
        for (Slot s : slots.values()) {
            long remain = Math.max(0, s.availableAt - now);
            out.add(new ModelStatus(
                    s.model.getName(),
                    s.enabled,
                    s.model.isSupportsDisableThinking(),
                    s.model.isSupportsTemperature(),
                    remain == 0,
                    remain / 1000,
                    s.failCount,
                    s.lastErrorKind == null ? null : s.lastErrorKind.name(),
                    s.lastError));
        }
        return out;
    }

    /**
     * 后台探测：把冷却已到期但还没回池的模型试活。
     *
     * <p>为什么需要：光靠「冷却到期就放回池子」是不够的 —— 额度耗尽后，
     * 6 小时后它多半还是空的，放回去等于让某个用户白等一次 403。
     * 这里用 1 个 token 先试，通了才回池。</p>
     *
     * <p>⚠️ 探测本身也可能失败（模型彻底下线、账号被限制），失败就地续冷却，
     * 不会把池子搞乱。</p>
     */
    @Scheduled(fixedDelayString = "${rag.chat.pool.probe-delay-seconds:300}000",
            initialDelayString = "${rag.chat.pool.probe-initial-delay-seconds:120}000")
    public void probeExpired() {
        if (!properties.isProbeEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Slot s : slots.values()) {
            if (!s.enabled || s.availableAt == 0 || s.availableAt > now) {
                continue;
            }
            // 到期了 → 试着调一次
            boolean ok = probe(s.model.getName());
            if (ok) {
                log.info("模型 {} 探测成功，已回池", s.model.getName());
                reportSuccess(s.model.getName());
            } else {
                // probe 内部已经 reportFailure 过，这里不用重复
                log.info("模型 {} 探测仍失败，继续冷却", s.model.getName());
            }
        }
    }

    private boolean probe(String modelName) {
        try {
            ChatClient client = clientFor(modelName);
            String reply = client.prompt()
                    .user("hi")
                    .options(OpenAiChatOptions.builder()
                            .model(modelName)
                            .maxTokens(properties.getProbeMaxTokens())
                            .build())
                    .call()
                    .content();
            return reply != null;
        } catch (Exception e) {
            reportFailure(modelName, e);
            return false;
        }
    }

    // ==================== 失败分类 ====================

    private enum FailureKind { QUOTA, RATE_LIMIT, OTHER }

    /**
     * 把异常归类。
     *
     * <p>⚠️ 靠字符串匹配而不是异常类型：Spring AI 把 4xx 统一包成
     * {@code NonTransientAiException}，报文才是唯一能区分「额度没了」
     * 和「模型名写错了」的东西。</p>
     */
    private static FailureKind classify(Throwable error) {
        String msg = rootMessage(error);
        if (msg == null) {
            return FailureKind.OTHER;
        }
        String m = msg.toLowerCase();
        if (m.contains("allocationquota")
                || m.contains("freetieronly")
                || m.contains("free quota exhausted")
                || m.contains("insufficient_quota")
                || m.contains("exceeded your current quota")
                || m.contains("quota")) {
            return FailureKind.QUOTA;
        }
        if (m.contains("429") || m.contains("rate limit") || m.contains("too many requests")) {
            return FailureKind.RATE_LIMIT;
        }
        return FailureKind.OTHER;
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

    // ==================== 内部状态 ====================

    private static final class Slot {
        final ChatPoolProperties.Model model;
        final boolean enabled;

        volatile ChatClient client;
        /** 到这个时间戳之前不要用；0 = 可用 */
        volatile long availableAt;
        volatile int failCount;
        volatile String lastError;
        volatile FailureKind lastErrorKind;

        Slot(ChatPoolProperties.Model model, boolean enabled) {
            this.model = model;
            this.enabled = enabled;
        }
    }

    /** 一个模型的状态快照，给后台展示 */
    public record ModelStatus(String model,
                              boolean enabled,
                              boolean supportsDisableThinking,
                              boolean supportsTemperature,
                              boolean available,
                              long cooldownSeconds,
                              int failCount,
                              String lastErrorKind,
                              String lastError) {
    }
}
