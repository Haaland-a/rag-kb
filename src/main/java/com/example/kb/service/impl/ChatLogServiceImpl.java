package com.example.kb.service.impl;

import com.example.kb.domain.po.KbChat;
import com.example.kb.domain.vo.ChatLogDetailDto;
import com.example.kb.domain.vo.ChatLogDto;
import com.example.kb.domain.vo.ChatPage;
import com.example.kb.domain.vo.SourceRef;
import com.example.kb.mapper.KbChatMapper;
import com.example.kb.service.IChatLogService;
import com.example.kb.service.IVisitService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ChatLogServiceImpl implements IChatLogService {

    private static final Logger log = LoggerFactory.getLogger(ChatLogServiceImpl.class);

    private static final int MAX_QUESTION_LEN = 2000;
    private static final int MAX_ANSWER_LEN = 20000;
    private static final int MAX_PAGE_LIMIT = 100;

    private final KbChatMapper chatMapper;
    private final ObjectMapper objectMapper;

    /**
     * 正在进行中的问答：id → 已累计的回答。
     *
     * <p>⚠️ 用 {@code ConcurrentHashMap} + {@code StringBuilder} 而不是普通 Map：
     * 累计发生在 Reactor 的 onNext（可能是不同线程），收尾在 onComplete/onCancel。
     * 实际测试里 SSE 是顺序的，但依赖「碰巧单线程」是危险的。</p>
     *
     * <p>⚠️ 必须有清理：如果某次流既没 complete 也没 cancel（比如异常吞掉了），
     * 这个 entry 会永远留在内存里。{@link #cleanupStale()} 兜底。</p>
     */
    private final ConcurrentHashMap<Long, PendingAnswer> pending = new ConcurrentHashMap<>();

    public ChatLogServiceImpl(KbChatMapper chatMapper, ObjectMapper objectMapper) {
        this.chatMapper = chatMapper;
        this.objectMapper = objectMapper;
    }

    /** 一次进行中的问答 */
    private static final class PendingAnswer {
        final StringBuilder text = new StringBuilder();
        final long startedAt = System.currentTimeMillis();

        /** 开始时命中的片段数。收尾时要用它写回，不能在 finish 里重新查 */
        final int sourceCount;

        volatile boolean finished;

        PendingAnswer(int sourceCount) {
            this.sourceCount = sourceCount;
        }
    }

    // ==================== 写入 ====================

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
    public Long begin(String ip, String question, List<SourceRef> sources, boolean isAdmin) {
        try {
            KbChat c = new KbChat();
            c.setIp(ip);
            c.setQuestion(truncate(question, MAX_QUESTION_LEN));
            c.setAnswer("");
            c.setSourcesJson(writeJson(sources));
            c.setSourceCount(sources == null ? 0 : sources.size());
            c.setAdmin(isAdmin);
            c.setComplete(false);
            // ⚠️ insert() 返回的是受影响行数（int），不是自增 id；id 由 MP 回填到实体上
        chatMapper.insert(c);
        long id = c.getId();
            pending.put(id, new PendingAnswer(c.getSourceCount()));
            return id;
        } catch (Exception e) {
            log.warn("创建问答记录失败（不影响问答）：{}", e.toString());
            return null;
        }
    }

    /**
     * 累计一段回答。
     *
     * <p>⚠️ 这个方法在流的每个 token 上被调用，必须足够便宜：
     * 一次 Map 查找 + 一次 StringBuilder.append，不碰数据库。</p>
     */
    public void append(Long logId, String token) {
        if (logId == null || token == null || token.isEmpty()) {
            return;
        }
        PendingAnswer p = pending.get(logId);
        if (p == null || p.finished) {
            return;
        }
        // 防止模型发疯输出百万字把内存吃光
        if (p.text.length() >= MAX_ANSWER_LEN) {
            return;
        }
        p.text.append(token);
    }

    /**
     * 收尾：写回全文。
     *
     * <p>⚠️ 必须在 {@code doFinally} 里调用而不是 {@code doOnComplete}：
     * 访客关页面时 Flux 被取消，只有 doFinally 保证跑得到。</p>
     *
     * @param complete true=回答生成完了；false=中途断开，answer 是已生成的部分
     */
    public void finish(Long logId, boolean complete) {
        if (logId == null) {
            return;
        }
        PendingAnswer p = pending.remove(logId);
        if (p == null || p.finished) {
            return;
        }
        p.finished = true;
        try {
            String answer = p.text.length() == 0 ? "" : p.text.toString();
            int chars = answer.length();
            int elapsed = (int) (System.currentTimeMillis() - p.startedAt);
            chatMapper.finish(logId, truncate(answer, MAX_ANSWER_LEN), chars,
                    p.sourceCount, elapsed, complete);
        } catch (Exception e) {
            log.warn("写回收尾问答记录失败（不影响问答）：{}", e.toString());
        }
    }

    /**
     * 兜底清理：超过 10 分钟还没收尾的记录。
     *
     * <p>正常流程里每条流都会走到 complete/cancel，这条任务理论上不该
     * 清出东西。它存在只是为了「万一」，比如某个 Flux 异常被吞了。
     * 不清的话这些 StringBuilder 会一直挂着直到重启。</p>
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void cleanupStale() {
        long now = System.currentTimeMillis();
        Set<Long> stale = new java.util.HashSet<>();
        pending.forEach((id, p) -> {
            if (now - p.startedAt > 600_000L) {
                stale.add(id);
            }
        });
        for (Long id : stale) {
            log.warn("问答记录 {} 超过 10 分钟未收尾，按中断处理", id);
            finish(id, false);
        }
    }

    // ==================== 查询（站主后台） ====================

    public ChatPage recent(int limit, int offset, boolean onlyVisitor) {
        int safeLimit = Math.min(Math.max(1, limit), MAX_PAGE_LIMIT);
        int safeOffset = Math.max(0, offset);
        List<ChatLogDto> items = chatMapper.findRecent(safeLimit, safeOffset, onlyVisitor)
                .stream()
                .map(row -> ChatLogDto.of(row.chat(), row.locationText()))
                .toList();
        long total = chatMapper.count(onlyVisitor);
        return new ChatPage(items, total, safeLimit, safeOffset);
    }

    public Optional<ChatLogDetailDto> detail(long id) {
        return chatMapper.findById(id).map(row ->
                ChatLogDetailDto.of(row.chat(), row.locationText(), readSources(row.chat().getSourcesJson())));
    }

    public int delete(long id) {
        return chatMapper.deleteById(id);
    }

    public int deleteAll() {
        return chatMapper.deleteAll();
    }

    // ==================== 内部 ====================

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o == null ? List.of() : o);
        } catch (Exception e) {
            log.warn("序列化引用来源失败，存空数组：{}", e.toString());
            return "[]";
        }
    }

    /**
     * 反序列化 sources。
     *
     * <p>⚠️ 解析失败返回空列表而不是抛：历史上可能存过损坏的 JSON，
     * 一条坏数据不该让整个详情页 500。</p>
     */
    private List<SourceRef> readSources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<SourceRef>>() {
            });
        } catch (Exception e) {
            log.warn("解析引用来源失败，返回空列表：{}", e.toString());
            return List.of();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
