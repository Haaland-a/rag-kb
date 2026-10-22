package com.example.kb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天模型池配置。
 *
 * <h3>为什么需要池子</h3>
 * <p>阿里云百炼的免费额度是<b>按模型分别给的</b>：每个模型各 100 万 tokens，
 * 用完了这个模型就返回 403 {@code AllocationQuota.FreeTierOnly}，但别的模型
 * 还有额度。单个 {@code rag.chat.model} 配置只能吃到一份额度，用完网站就直接
 * 挂掉。池子把「换模型」这件事从「改配置重启」变成「运行时自动」。</p>
 *
 * <h3>为什么还有 thinking 开关</h3>
 * <p>百炼大部分 qwen3.x / deepseek 模型接受 {@code enable_thinking:false}，
 * 但实测有两个模型<b>只接受 true</b>，传 false 直接 400
 * {@code InternalError.Algo.InvalidParameter}：</p>
 * <pre>
 * qwen3.8-2.4t-a95b   → "The value of the enable_thinking parameter is restricted to True"
 * glm-5.3             → 同上
 * </pre>
 * <p>这类模型要么不进池子，要么标 {@code thinking: required} 让调用方别传这个参数
 * —— 传了必炸。代价是它们每次回答前会先吐一段推理，速度慢十几秒，
 * 所以默认排在队尾，只在前面全挂时兜底。</p>
 */
@Component
@ConfigurationProperties(prefix = "rag.chat.pool")
public class ChatPoolProperties {

    /**
     * 模型清单，<b>顺序即优先级</b>（但在轮转模式下顺序只决定第一轮的起点）。
     *
     * <p>空列表 = 退回单模型模式，用 {@code spring.ai.openai.chat.options.model}。</p>
     */
    private List<Model> models = new ArrayList<>();

    /**
     * 额度耗尽（403 AllocationQuota）后的冷却秒数，默认 6 小时。
     *
     * <p>为什么不是永久禁用：百炼的免费额度每月刷新，而且用户可能充值。
     * 6 小时探一次足够了，探活了自动回池。</p>
     */
    private int quotaCooldownSeconds = 21600;

    /** 一般错误（5xx、超时、响应解析失败）后的冷却秒数，默认 5 分钟 */
    private int errorCooldownSeconds = 300;

    /** 被限流（429）后的冷却秒数，默认 60 秒 —— 这种通常很快就会恢复 */
    private int rateLimitCooldownSeconds = 60;

    /** 是否启用后台探测（把冷却中的模型试活） */
    private boolean probeEnabled = true;

    /** 探测间隔秒数 */
    private int probeDelaySeconds = 300;

    /** 首次探测延迟秒数（避开启动时的资源紧张期） */
    private int probeInitialDelaySeconds = 120;

    /**
     * 单次探测的 max_tokens。
     *
     * <p>1 就够 —— 只想知道「这个模型还能不能调通」，不关心它答什么。
     * 每个模型每次探测花 1 个 token，一个月 6 小时探一次也就 120 次。</p>
     */
    private int probeMaxTokens = 1;

    public List<Model> getModels() {
        return models;
    }

    public void setModels(List<Model> models) {
        this.models = models == null ? new ArrayList<>() : models;
    }

    public int getQuotaCooldownSeconds() {
        return quotaCooldownSeconds;
    }

    public void setQuotaCooldownSeconds(int quotaCooldownSeconds) {
        this.quotaCooldownSeconds = quotaCooldownSeconds;
    }

    public int getErrorCooldownSeconds() {
        return errorCooldownSeconds;
    }

    public void setErrorCooldownSeconds(int errorCooldownSeconds) {
        this.errorCooldownSeconds = errorCooldownSeconds;
    }

    public int getRateLimitCooldownSeconds() {
        return rateLimitCooldownSeconds;
    }

    public void setRateLimitCooldownSeconds(int rateLimitCooldownSeconds) {
        this.rateLimitCooldownSeconds = rateLimitCooldownSeconds;
    }

    public boolean isProbeEnabled() {
        return probeEnabled;
    }

    public void setProbeEnabled(boolean probeEnabled) {
        this.probeEnabled = probeEnabled;
    }

    public int getProbeDelaySeconds() {
        return probeDelaySeconds;
    }

    public void setProbeDelaySeconds(int probeDelaySeconds) {
        this.probeDelaySeconds = probeDelaySeconds;
    }

    public int getProbeInitialDelaySeconds() {
        return probeInitialDelaySeconds;
    }

    public void setProbeInitialDelaySeconds(int probeInitialDelaySeconds) {
        this.probeInitialDelaySeconds = probeInitialDelaySeconds;
    }

    public int getProbeMaxTokens() {
        return probeMaxTokens;
    }

    public void setProbeMaxTokens(int probeMaxTokens) {
        this.probeMaxTokens = probeMaxTokens;
    }

    /** 池子里的一个模型 */
    public static class Model {

        /** 模型 id，必须和百炼 {@code /v1/models} 里的一致 */
        private String name;

        /**
         * 这个模型接不接受 {@code enable_thinking:false}。
         *
         * <p>{@code true}（默认）= 正常，会传这个参数关掉思考模式。
         * {@code false} = 它只接受 true，别传，传了 400。</p>
         */
        private boolean supportsDisableThinking = true;

        /**
         * 这个模型接不接受 {@code temperature} 参数。
         *
         * <p>⚠️ 实测 {@code kimi-k3} 不支持：传 {@code temperature:0.2} 直接
         * {@code <400> InternalError.Algo.InvalidParameter: Parameter 'temperature'=0.2
         * is not supported for kimi-k3 model.}。这种模型要把本字段设 false，
         * 我们就不发 temperature，让它用自己默认值。</p>
         *
         * <p>注意这和「不传等于用默认」不是一回事：Spring AI 的
         * {@code OpenAiChatOptions} 里 temperature 为 null 时不写进请求体，
         * 所以设 false 只需要不调 {@code .temperature(...)}。</p>
         */
        private boolean supportsTemperature = true;

        /** 权重，预留给「好模型多分几次」用；当前轮转实现按顺序逐个走，未使用 */
        private int weight = 1;

        /** 是否启用；设 false 可以临时把某个模型摘出去而不删配置 */
        private boolean enabled = true;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isSupportsDisableThinking() {
            return supportsDisableThinking;
        }

        public void setSupportsDisableThinking(boolean supportsDisableThinking) {
            this.supportsDisableThinking = supportsDisableThinking;
        }

        public boolean isSupportsTemperature() {
            return supportsTemperature;
        }

        public void setSupportsTemperature(boolean supportsTemperature) {
            this.supportsTemperature = supportsTemperature;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public String toString() {
            return name + (supportsDisableThinking ? "" : "(thinking-required)")
                    + (supportsTemperature ? "" : "(no-temperature)")
                    + (enabled ? "" : "(disabled)");
        }
    }
}
