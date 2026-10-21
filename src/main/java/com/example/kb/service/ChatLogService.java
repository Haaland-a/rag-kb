package com.example.kb.service;

import com.example.kb.domain.KbChat;
import com.example.kb.dto.chat.ChatLogDetailDto;
import com.example.kb.dto.chat.ChatLogDto;
import com.example.kb.dto.chat.ChatPage;
import com.example.kb.dto.chat.SourceRef;
import com.example.kb.repository.KbChatRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 问答记录写入与查询。
 *
 * <h3>为什么不能在流结束后一次性 insert</h3>
 * <p>访客可能在回答生成到一半时关掉页面（或刷新、或网络断了）。
 * SSE 的 Flux 一旦被取消，{@code doOnComplete} 就不会触发 ——
 * 如果只在完成时写库，这类记录一条都不会留下。而「他问了什么」
 * 恰恰是站主最想知道的信息（能看出访客关心什么、在哪儿没得到答案）。</p>
 *
 * <p>所以流程是：</p>
 * <ol>
 *   <li>请求进来时立即 insert 一行（answer 为空）→ 拿到 id</li>
 *   <li>流式过程中在内存里累计回答片段</li>
 *   <li>流正常结束 → update 全文 + complete=true</li>
 *   <li>流被取消（访客关了页面）→ 把已累计的部分写进去 + complete=false</li>
 * </ol>
 *
 * <p>⚠️ 这样做的代价：cancel 分支要靠 Reactor 的 {@code doFinally}，
 * 它不是每次都跑得到（进程被杀就没有）。可接受 —— 丢的只是最后一条。</p>
 *
 * <h3>为什么用内存累计而不是每个 token 都写库</h3>
 * <p>一个回答有几十到几百个 token 片段，每个都 UPDATE 一次意味着几百次
 * 往返数据库。2C2G 上这是纯浪费。内存里 StringBuilder 拼接，
 * 结束时写一次就够了。</p>
 */
@Service
public class ChatLogService {

    private static final Logger log = LoggerFactory.getLogger(ChatLogService.class);

    private static final int MAX_QUESTION_LEN = 2000;
    private static final int MAX_ANSWER_LEN = 20000;
    private static final int MAX_PAGE_LIMIT = 100;

    private final KbChatRepository chatRepository;
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

    public ChatLogService(KbChatRepository chatRepository, ObjectMapper objectMapper) {
        this.chatRepository = chatRepository;
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
     * 它写失败绝不能让访客问不出问题（和 VisitService 同一条原则）。</p>
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
            long id = chatRepository.insert(c);
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
            chatRepository.finish(logId, truncate(answer, MAX_ANSWER_LEN), chars,
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
        List<ChatLogDto> items = chatRepository.findRecent(safeLimit, safeOffset, onlyVisitor)
                .stream()
                .map(row -> ChatLogDto.of(row.chat(), row.locationText()))
                .toList();
        long total = chatRepository.count(onlyVisitor);
        return new ChatPage(items, total, safeLimit, safeOffset);
    }

    public Optional<ChatLogDetailDto> detail(long id) {
        return chatRepository.findById(id).map(row ->
                ChatLogDetailDto.of(row.chat(), row.locationText(), readSources(row.chat().getSourcesJson())));
    }

    public int delete(long id) {
        return chatRepository.deleteById(id);
    }

    public int deleteAll() {
        return chatRepository.deleteAll();
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
