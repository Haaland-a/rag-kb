package com.example.kb.domain;

import java.time.OffsetDateTime;

/**
 * 一条问答记录。
 *
 * <p>⚠️ 和 {@link KbVisit} 的区别：kb_visit 记「有人访问了某个路径」，
 * 这个记「有人问了一个问题、得到了什么回答、引用了哪些资料」。
 * 一次问答会同时产生两行（kb_visit 一行 + kb_chat 一行）——
 * 看起来冗余，但两者用途不同：kb_visit 用来数访问量和独立 IP，
 * kb_chat 用来看问答质量。合成一张表的话，主页访问也要占 answer 字段。</p>
 *
 * <p>⚠️ 没有归属地字段：同一 IP 的归属地 kb_visit 已经在补了，
 * 后台列表用 SQL 关联取，不在这里重复存一份（否则要维护两处补全逻辑）。</p>
 */
public class KbChat {

    private Long id;

    /** 访客 IP */
    private String ip;

    /** 问题原文 */
    private String question;

    /** 回答全文。中途断掉的记录这里是已生成的部分 */
    private String answer;

    /** 引用来源的 JSON 原文（和 kb_chat.sources 列一一对应） */
    private String sourcesJson;

    /** 引用了几个资料片段 */
    private int sourceCount;

    /** 从收到请求到生成结束的毫秒数 */
    private Integer elapsedMs;

    /** 回答字数。单独存一列是为了后台列表不用把 answer 全文拉出来算长度 */
    private Integer answerChars;

    /** false = 访客中途关掉页面，回答没生成完 */
    private boolean complete = true;

    /** 是不是站主自己问的（站主提问不计入访客统计） */
    private boolean admin;

    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getSourcesJson() {
        return sourcesJson;
    }

    public void setSourcesJson(String sourcesJson) {
        this.sourcesJson = sourcesJson;
    }

    public int getSourceCount() {
        return sourceCount;
    }

    public void setSourceCount(int sourceCount) {
        this.sourceCount = sourceCount;
    }

    public Integer getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(Integer elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public Integer getAnswerChars() {
        return answerChars;
    }

    public void setAnswerChars(Integer answerChars) {
        this.answerChars = answerChars;
    }

    public boolean isComplete() {
        return complete;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
