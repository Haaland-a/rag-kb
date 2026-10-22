package com.example.kb.service;

import com.example.kb.domain.po.KbVisit;
import com.example.kb.domain.vo.VisitPage;
import com.example.kb.domain.vo.VisitStats;
import com.example.kb.domain.vo.VisitorSummary;
import com.example.kb.mapper.KbVisitMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Flux;

public interface IVisitService {

    /**
     * 记一条访问。
     *
     * @param ip     客户端 IP（已由 {@link ClientIpResolver} 解析）
     * @param isAdmin 是否站主本人（站主访问不计入访客统计）
     */
    public void record(String ip, String path, String question, String userAgent, String referer, boolean isAdmin);

    public boolean isTrustProxy();

    /**
     * 倒序分页的原始访问流水。
     *
     * <p>这里用 limit/offset 而不是 page/size：这个接口只有站主自己看，
     * 不需要「第几页」的语义，前端算 offset 更直接。</p>
     */
    public VisitPage recent(int limit, int offset);

    /** 按 IP 聚合：谁来过、来了几次、最近一次什么时候、从哪来 */
    public List<VisitorSummary> summary(int limit);

    /**
     * 仪表盘统计。
     *
     * <p>⚠️ 三个数字分别容错：单个查询失败只让那个数字为 0，
     * 不要让整个仪表盘 500。站主打开后台看到 0 总比看到报错页好。</p>
     */
    public VisitStats stats();

}
