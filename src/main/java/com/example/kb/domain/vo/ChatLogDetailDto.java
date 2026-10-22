package com.example.kb.domain.vo;

import com.example.kb.common.TimeFormat;
import com.example.kb.domain.po.KbChat;

import java.util.List;

/**
 * 问答记录<b>详情</b>（点开一条才拉）。
 *
 * <p>和 {@link ChatLogDto} 的区别：这里带回答全文和引用来源明细。
 * 列表页不带这些是为了省流量，详情页一次只拉一条，代价可接受。</p>
 *
 * @param id          记录 id
 * @param ip          访客 IP
 * @param location    归属地
 * @param question    问题原文
 * @param answer      回答全文（中途断掉的只有已生成部分）
 * @param sources     引用来源明细
 * @param sourceCount 引用片段数
 * @param elapsedMs   耗时毫秒
 * @param complete    是否完整生成
 * @param admin       是否站主提问
 * @param createdAt   提问时间
 */
public record ChatLogDetailDto(long id,
                               String ip,
                               String location,
                               String question,
                               String answer,
                               List<SourceRef> sources,
                               int sourceCount,
                               Integer elapsedMs,
                               boolean complete,
                               boolean admin,
                               String createdAt) {

    public static ChatLogDetailDto of(KbChat c, String location, List<SourceRef> sources) {
        return new ChatLogDetailDto(
                c.getId() == null ? 0L : c.getId(),
                c.getIp(),
                location,
                c.getQuestion(),
                c.getAnswer(),
                sources == null ? List.of() : sources,
                c.getSourceCount(),
                c.getElapsedMs(),
                c.isComplete(),
                c.isAdmin(),
                c.getCreatedAt() == null ? null : TimeFormat.format(c.getCreatedAt()));
    }
}
