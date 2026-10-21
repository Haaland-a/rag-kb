package com.example.kb.dto.chat;

/**
 * SSE 结束帧（done 事件）。
 *
 * <p>⚠️ token usage：百炼的 OpenAI 兼容端点<b>在 stream 模式下不返回 usage</b>
 * （每帧 {@code usage: null}），所以 chatTokens 是 0 而不是真实值。
 * 要真实用量得按模型 tokenizer 单独统计，或用 {@code stream_options.include_usage}，
 * 但不同服务商支持度不一。第 1 周不做 —— 本地估算只用于展示，不做计费。</p>
 */
public class ChatDoneEvent {

    /** 本次检索命中数（已按阈值过滤后） */
    private int sourceCount;
    /** 0 = 端点不返回 usage，非 0 时才是真实值 */
    private long chatTokens;
    /** 整个请求耗时（毫秒） */
    private long elapsedMs;
    /** 该 IP 本小时剩余问答次数；-1 = 不限（站主） */
    private int remainingQuota;

    public int getSourceCount() {
        return sourceCount;
    }

    public void setSourceCount(int sourceCount) {
        this.sourceCount = sourceCount;
    }

    public long getChatTokens() {
        return chatTokens;
    }

    public void setChatTokens(long chatTokens) {
        this.chatTokens = chatTokens;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public int getRemainingQuota() {
        return remainingQuota;
    }

    public void setRemainingQuota(int remainingQuota) {
        this.remainingQuota = remainingQuota;
    }
}
