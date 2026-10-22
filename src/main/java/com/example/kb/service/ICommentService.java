package com.example.kb.service;

import com.example.kb.common.TimeFormat;
import com.example.kb.domain.dto.CommentRequests;
import com.example.kb.domain.po.KbComment;
import com.example.kb.domain.po.KbMoment;
import com.example.kb.domain.vo.CommentDto;
import com.example.kb.domain.vo.CommentPage;
import com.example.kb.mapper.KbCommentMapper;
import com.example.kb.mapper.KbMomentMapper;
import com.example.kb.mapper.KbUserMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

public interface ICommentService {

    /**
     * 提交结果。
     *
     * <p>⚠️ 必须把 {@code id} 一起返回：注册用户发完留言后要能立刻编辑/删除
     * 自己那条，没有 id 就只能整页刷新再从头找（游客不需要 id，但也无副作用）。</p>
     *
     * <p>游客的 {@code id} 也返回，但前端不会用它 —— 匿名留言改不了删不了，
     * 这是设计而不是遗漏。</p>
     *
     * @param id     新留言 id
     * @param notice 提示语（告诉留言的人「这条谁会看到」「能不能改」）
     * @param mine   是不是自己名下可编辑的留言（游客恒 false）
     */
    public record SubmitResult(long id, String notice, boolean mine) {
    }

    /**
     * 留言板列表（{@code momentId == null} 的那些）。
     *
     * @param viewerId 看的人。null = 游客。**这个参数是可见性规则的一部分，
     *                 不是可选优化** —— 少了它，「被屏蔽的留言作者仍可见」就失效
     */
    public CommentPage board(Long viewerId, int limit, int offset, boolean adminView);

    /** 某条动态下的评论 */
    public List<CommentDto> momentComments(long momentId, Long viewerId, boolean adminView);

    /**
     * 一批动态各自的留言预览（主页用）。
     *
     * <p>⚠️ 和 {@link #momentComments} 的区别不只是「批量」：这里
     * <b>只走访客视角</b>（没有 adminView 分支）。主页是公开页面，
     * 站主自己打开主页看到的也该是公开视图 —— 悄悄话不该在主页上冒出来，
     * 那是后台留言管理的事。</p>
     *
     * @param momentIds 要预览的动态 id
     * @param viewerId  看的人（null = 游客），决定「自己发的被屏蔽留言」是否可见
     * @param perMoment 每条动态最多预览几条
     * @return momentId → 留言 DTO 列表（没有留言的动态不在 map 里）
     */
    public Map<Long, List<CommentDto>> previewsForMoments(List<Long> momentIds, Long viewerId, int perMoment);

    /**
     * 一批动态各自的访客可见留言总数（角标用）。
     *
     * <p>⚠️ 必须和 {@link #previewsForMoments} 用同一套可见性条件，
     * 否则会出现「角标写 5 条，展开只看到 2 条」。两边共用
     * {@code KbCommentMapper} 里同一段 SQL 片段就是这个原因。</p>
     */
    public Map<Long, Long> visibleCountsForMoments(List<Long> momentIds, Long viewerId);

    /** 单条动态下访客可见的留言总数（详情页用，和列表同条件） */
    public long visibleCountForMoment(long momentId, Long viewerId);

    /**
     * 提交一条留言/评论。
     *
     * @param momentId 挂在哪条动态下；null = 留言板
     * @param viewerId 已登录用户的 id；null = 游客
     * @param username 已登录用户名；null = 游客
     * @return 新留言 id + 提示语
     */
    public SubmitResult submit(Long momentId, CommentRequests.Submit req, Long viewerId, String username, String ip);

    /**
     * 作者改自己的留言。
     *
     * <p>⚠️ 游客的留言（user_id 为 null）永远改不了：没有稳定身份，
     * 无法证明「这条是你写的」。这正是游客提示里强调「发出去就不能改」的原因。</p>
     */
    public void edit(long commentId, long viewerId, boolean admin, String content);

    /**
     * 删除一条留言。
     *
     * <p>作者能删自己的，站主能删任何一条。游客的留言只能由站主删。</p>
     */
    public void delete(long commentId, long viewerId, boolean admin);

    /** 站主屏蔽 / 恢复 */
    public void setStatus(long commentId, String status);

    /**
     * 站主后台的留言列表（全部可见性、全部状态）。
     *
     * @param scope all / board / moment，见 {@link KbCommentMapper#SCOPE_ALL}。
     *              传 null 或未知值等价于 all
     */
    public CommentPage adminList(int limit, int offset, Boolean unreadOnly, String scope);

    public long unreadCount();

    public int markRead(Long id);

}
