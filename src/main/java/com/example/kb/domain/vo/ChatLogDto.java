package com.example.kb.domain.vo;

import com.example.kb.common.TimeFormat;
import com.example.kb.domain.po.KbChat;

/**
 * 后台「问答记录」列表里的一行。
 *
 * <p>⚠️ 列表页<b>不返回 answer 全文</b>，只给 {@code answerChars}
 * 和一小段 {@code answerPreview}。理由：一次问答的回答常常几千字，
 * 一页 20 条就是几十 KB JSON，而站主扫列表时根本不会逐条读完 ——
 * 点进详情再看全文。这和「文档列表不返回 contentHash」是同一类考虑。</p>
 *
 * <p>⚠️ {@code location} 是 SQL 关联 kb_visit 取到的归属地拼串，
 * 不在这里重复存：同一个 IP 的归属地由 GeoIpService 统一补全，
 * 维护一处就够了。</p>
 *
 * @param id            记录 id
 * @param ip            访客 IP
 * @param location      归属地（如「中国 山西 晋中」），查不到为 null
 * @param question      问题原文
 * @param answerPreview 回答前 120 字，列表页预览用
 * @param answerChars   回答字数
 * @param sourceCount   引用资料片段数
 * @param elapsedMs     耗时毫秒
 * @param complete      是否完整生成
 * @param admin         是否站主提问
 * @param createdAt     提问时间
 */
public record ChatLogDto(long id,
                         String ip,
                         String location,
                         String question,
                         String answerPreview,
                         Integer answerChars,
                         int sourceCount,
                         Integer elapsedMs,
                         boolean complete,
                         boolean admin,
                         String createdAt) {

    /** 列表预览长度 */
    private static final int PREVIEW_LEN = 120;

    /**
     * 从实体转列表 DTO。
     *
     * <p>⚠️ 多带一个 {@code location} 参数：位置是 SQL 关联出来的，
     * 实体上没有这个字段（见 {@link KbChat} 类注释）。</p>
     */
    public static ChatLogDto of(KbChat c, String location) {
        return new ChatLogDto(
                c.getId() == null ? 0L : c.getId(),
                c.getIp(),
                location,
                c.getQuestion(),
                preview(c.getAnswer()),
                c.getAnswerChars(),
                c.getSourceCount(),
                c.getElapsedMs(),
                c.isComplete(),
                c.isAdmin(),
                c.getCreatedAt() == null ? null : TimeFormat.format(c.getCreatedAt()));
    }

    /**
     * 回答前 120 字。
     *
     * <p>换行和连续空白压成单空格 —— 列表里一行显示不下换行，
     * 留着会让表格行高忽高忽低。</p>
     */
    private static String preview(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        String flat = answer.replaceAll("\\s+", " ").strip();
        return flat.length() <= PREVIEW_LEN ? flat : flat.substring(0, PREVIEW_LEN) + "…";
    }
}
