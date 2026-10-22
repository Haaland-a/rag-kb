package com.example.kb.service;

import com.example.kb.domain.po.KbChat;
import com.example.kb.domain.vo.ChatLogDetailDto;
import com.example.kb.domain.vo.ChatLogDto;
import com.example.kb.domain.vo.ChatPage;
import com.example.kb.domain.vo.SourceRef;
import com.example.kb.mapper.KbChatMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;

public interface IChatLogService {

    /**
     * 请求进来时先落一行，返回记录 id 给后续收尾用。
     *
     * <p>⚠️ 任何异常都吞掉返回 null：问答记录是观测手段，
     * 它写失败绝不能让访客问不出问题（和 IVisitService 同一条原则）。</p>
     *
     * @param ip         访客 IP
     * @param question   问题原文
     * @param sources    检索到的引用
     * @param isAdmin    是否站主
     * @return 记录 id；写库失败返回 null（调用方拿到 null 就跳过收尾）
     */
    public Long begin(String ip, String question, List<SourceRef> sources, boolean isAdmin);

    /**
     * 累计一段回答。
     *
     * <p>⚠️ 这个方法在流的每个 token 上被调用，必须足够便宜：
     * 一次 Map 查找 + 一次 StringBuilder.append，不碰数据库。</p>
     */
    public void append(Long logId, String token);

    /**
     * 收尾：写回全文。
     *
     * <p>⚠️ 必须在 {@code doFinally} 里调用而不是 {@code doOnComplete}：
     * 访客关页面时 Flux 被取消，只有 doFinally 保证跑得到。</p>
     *
     * @param complete true=回答生成完了；false=中途断开，answer 是已生成的部分
     */
    public void finish(Long logId, boolean complete);

    /**
     * 兜底清理：超过 10 分钟还没收尾的记录。
     *
     * <p>正常流程里每条流都会走到 complete/cancel，这条任务理论上不该
     * 清出东西。它存在只是为了「万一」，比如某个 Flux 异常被吞了。
     * 不清的话这些 StringBuilder 会一直挂着直到重启。</p>
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void cleanupStale();

    public ChatPage recent(int limit, int offset, boolean onlyVisitor);

    public Optional<ChatLogDetailDto> detail(long id);

    public int delete(long id);

    public int deleteAll();

}
