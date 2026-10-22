package com.example.kb.service.impl;

import com.example.kb.domain.po.KbVisit;
import com.example.kb.domain.vo.VisitPage;
import com.example.kb.domain.vo.VisitStats;
import com.example.kb.domain.vo.VisitorSummary;
import com.example.kb.mapper.KbVisitMapper;
import com.example.kb.service.IVisitService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VisitServiceImpl implements IVisitService {

    private static final Logger log = LoggerFactory.getLogger(VisitServiceImpl.class);

    /** UA / referer 是 TEXT 但没必要存几 KB，截断 */
    private static final int MAX_UA_LEN = 400;
    private static final int MAX_REFERER_LEN = 400;
    private static final int MAX_PATH_LEN = 300;
    private static final int MAX_QUESTION_LEN = 1000;

    private final KbVisitMapper visitMapper;

    /** 是否信任 X-Forwarded-For（有反向代理时才开） */
    @Value("${rag.visit.trust-proxy:true}")
    private boolean trustProxy;

    public VisitServiceImpl(KbVisitMapper visitMapper) {
        this.visitMapper = visitMapper;
    }

    /**
     * 记一条访问。
     *
     * @param ip     客户端 IP（已由 {@link ClientIpResolver} 解析）
     * @param isAdmin 是否站主本人（站主访问不计入访客统计）
     */
    public void record(String ip, String path, String question,
                       String userAgent, String referer, boolean isAdmin) {
        try {
            KbVisit v = new KbVisit();
            v.setIp(ip);
            v.setPath(truncate(path, MAX_PATH_LEN));
            v.setQuestion(truncate(question, MAX_QUESTION_LEN));
            v.setUserAgent(truncate(userAgent, MAX_UA_LEN));
            v.setReferer(truncate(referer, MAX_REFERER_LEN));
            v.setAdmin(isAdmin);
            visitMapper.insert(v);
        } catch (Exception e) {
            // 观测失败不能拖垮业务：记一行日志就算了
            log.warn("记录访客失败（不影响主流程）：{}", e.toString());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public boolean isTrustProxy() {
        return trustProxy;
    }

    // ==================== 查询（站主后台用） ====================
    //
    // ⚠️ 这几个方法原来直接写在 VisitController 里，Controller 注入了
    // KbVisitMapper 并现场拼 Map —— 越层。搬到 Service 后：
    //   Controller 只做「收参数 → 调这里 → 包 R」
    //   Repository 只管 SQL
    //   分页上限、聚合口径这类规则集中在这里，不会散落

    /** 分页上限：防止前端传个 1000000 把 2G 机器内存打爆 */
    private static final int MAX_PAGE_LIMIT = 200;

    /**
     * 倒序分页的原始访问流水。
     *
     * <p>这里用 limit/offset 而不是 page/size：这个接口只有站主自己看，
     * 不需要「第几页」的语义，前端算 offset 更直接。</p>
     */
    public VisitPage recent(int limit, int offset) {
        int safeLimit = Math.min(Math.max(1, limit), MAX_PAGE_LIMIT);
        int safeOffset = Math.max(0, offset);
        List<KbVisit> rows = visitMapper.findRecent(safeLimit, safeOffset);
        long total = visitMapper.count();
        return new VisitPage(rows, total, safeLimit, safeOffset);
    }

    /** 按 IP 聚合：谁来过、来了几次、最近一次什么时候、从哪来 */
    public List<VisitorSummary> summary(int limit) {
        int safeLimit = Math.min(Math.max(1, limit), MAX_PAGE_LIMIT);
        return visitMapper.summarizeByIp(safeLimit);
    }

    /**
     * 仪表盘统计。
     *
     * <p>⚠️ 三个数字分别容错：单个查询失败只让那个数字为 0，
     * 不要让整个仪表盘 500。站主打开后台看到 0 总比看到报错页好。</p>
     */
    public VisitStats stats() {
        long total = 0;
        long questions = 0;
        long tracked = 0;
        try {
            total = visitMapper.count();
        }
        catch (Exception e) {
            log.warn("统计总访问数失败：{}", e.getMessage());
        }
        try {
            questions = visitMapper.countQuestions();
        }
        catch (Exception e) {
            log.warn("统计提问数失败：{}", e.getMessage());
        }
        try {
            tracked = visitMapper.summarizeByIp(1000).size();
        }
        catch (Exception e) {
            log.warn("统计独立 IP 失败：{}", e.getMessage());
        }
        return new VisitStats(total, tracked, questions);
    }
}
