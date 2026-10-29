package com.example.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.domain.po.KbComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * kb_comment 表访问（留言板 + 动态评论共用）。
 *
 * <h3>⚠️ 三条可见性规则必须写在 SQL 里，不能在 Java 里 filter</h3>
 * <ol>
 *   <li><b>PUBLIC + VISIBLE</b> —— 所有人可见</li>
 *   <li><b>PRIVATE</b>（悄悄话）—— 只有站主可见，对访客必须排除，
 *       否则「悄悄话」这个名字就是假的</li>
 *   <li><b>HIDDEN</b>（被屏蔽）—— 除了留言者本人和站主，别人看不到。
 *       留言者仍能看到自己那条，这样他知道自己被屏蔽了，而不是以为系统出 bug</li>
 * </ol>
 *
 * <p>为什么必须在 SQL 里：如果在 Service 里查出全量再过滤，只要有一次忘记
 * filter，悄悄话就直接暴露了。放在 SQL 里，调用方拿不到「不该拿到的行」，
 * 从数据层面就不可能泄露。</p>
 *
 * <h3>为什么访客视角要多一个 viewerId 参数</h3>
 * <p>规则 3 要求「留言者仍能看到自己那条」，所以查询必须知道「看的人是谁」。
 * 游客传 null（调用方转成 0），注册用户传自己的 id。这不是可选优化，
 * 而是可见性规则的组成部分 —— 少了它就违反需求。</p>
 *
 * <p>⚠️ {@code viewerId} 在 SQL 里一律写成 {@code #{viewerId}}，且调用方必须
 * 传 0 而不是 null：{@code user_id = NULL} 恒为 null（不是 false），
 * 整个 OR 条件会退化成 null 而不是匹配「PUBLIC + VISIBLE」那半边。
 * 这是 SQL 三值逻辑的经典坑，所以每个公开方法的 {@code viewerId} 参数
 * 都在 service 层统一由 {@code viewerIdOrZero} 转换。</p>
 */
@Mapper
public interface KbCommentMapper extends BaseMapper<KbComment> {

    /** 站主后台的范围取值：全部 / 只要留言板 / 只要动态评论 */
    String SCOPE_ALL = "all";
    String SCOPE_BOARD = "board";
    String SCOPE_MOMENT = "moment";

    /** 访客可见性条件（拼进 WHERE 的片段） */
    String VISIBLE_CLAUSE =
            " AND (visibility = 'PUBLIC' AND status = 'VISIBLE'"
                    + " OR (user_id IS NOT NULL AND user_id = #{viewerId}))";

    /**
     * 显式列清单，<b>不要改用 {@code SELECT *}</b>。
     *
     * <p>⚠️ 这里踩过一次：{@code is_read} 经 {@code map-underscore-to-camel-case}
     * 会变成属性名 {@code isRead}，而实体字段叫 {@code read} —— 对不上就一直
     * 拿默认值 {@code false}，于是后台每条留言都顶着「新」标签，可未读数是对的
     * （未读数走的是另一个 count 查询），看起来像前端 bug，其实是映射漏了。</p>
     *
     * <p>⚠️ 别指望 {@code @TableField("is_read")} 救：它只对 MyBatis-Plus 自带的
     * {@code BaseMapper} 方法生效，自定义 {@code @Select} 完全不认。</p>
     *
     * <p>所以这里显式列出每一列，给 {@code is_read} 起别名 {@code read}，
     * 让列标签直接等于属性名，绕开驼峰推断。</p>
     */
    String COLUMNS =
            "id, moment_id, user_id, nickname, content, ip, device_id,"
                    + " visibility, status, parent_id, is_read AS read, created_at";

    // ==================== 访客视角 ====================

    /** 留言板（moment_id IS NULL）的访客视角分页 */
    @Select("SELECT " + COLUMNS + " FROM kb_comment WHERE moment_id IS NULL" + VISIBLE_CLAUSE
            + " ORDER BY created_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<KbComment> selectBoardForVisitor(@Param("viewerId") long viewerId,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);

    @Select("SELECT count(*) FROM kb_comment WHERE moment_id IS NULL" + VISIBLE_CLAUSE)
    long countBoardForVisitor(@Param("viewerId") long viewerId);

    /** 某条动态下的评论，访客视角 */
    @Select("SELECT " + COLUMNS + " FROM kb_comment WHERE moment_id = #{momentId}" + VISIBLE_CLAUSE
            + " ORDER BY created_at ASC LIMIT #{limit}")
    List<KbComment> selectMomentCommentsForVisitor(@Param("momentId") long momentId,
                                                   @Param("viewerId") long viewerId,
                                                   @Param("limit") int limit);

    /**
     * 一批动态<b>各自</b>的前 N 条访客可见留言（主页预览用）。
     *
     * <p>⚠️ 用窗口函数一次查完，不是「循环每个 momentId 查一次」：
     * 主页一次要预览 20 条动态，循环就是 20 次数据库往返。</p>
     *
     * <p>⚠️ {@code ROW_NUMBER()} 而不是 {@code LIMIT}：LIMIT 是全局的，
     * 只会给出「整批里最早的 N 条」，而不是「每条动态各 N 条」。</p>
     */
    @Select("<script>SELECT * FROM ("
            + "  SELECT c.id, c.moment_id, c.user_id, c.nickname, c.content, c.ip,"
            + "    c.device_id, c.visibility, c.status, c.parent_id,"
            + "    c.is_read AS read, c.created_at,"
            + "    ROW_NUMBER() OVER (PARTITION BY c.moment_id"
            + "      ORDER BY c.created_at ASC, c.id ASC) AS rn"
            + "  FROM kb_comment c WHERE c.moment_id IN "
            + "<foreach collection='momentIds' item='m' open='(' separator=',' close=')'>#{m}</foreach>"
            + "    AND (c.visibility = 'PUBLIC' AND c.status = 'VISIBLE'"
            + "         OR (c.user_id IS NOT NULL AND c.user_id = #{viewerId}))"
            + ") t WHERE t.rn &lt;= #{perMoment}"
            + " ORDER BY t.moment_id ASC, t.created_at ASC, t.id ASC</script>")
    List<KbComment> selectVisiblePreviews(@Param("momentIds") List<Long> momentIds,
                                          @Param("viewerId") long viewerId,
                                          @Param("perMoment") int perMoment);

    /**
     * 一批动态各自访客可见的留言总数。
     *
     * <p>主页预览只显示前几条，但角标要显示真实总数 —— 否则
     * 「10 条留言只看见 3 条，角标也写 3」，用户会以为留言丢了。</p>
     */
    @Select("<script>SELECT moment_id AS momentId, count(*) AS n FROM kb_comment"
            + " WHERE moment_id IN "
            + "<foreach collection='momentIds' item='m' open='(' separator=',' close=')'>#{m}</foreach>"
            + "   AND (visibility = 'PUBLIC' AND status = 'VISIBLE'"
            + "        OR (user_id IS NOT NULL AND user_id = #{viewerId}))"
            + " GROUP BY moment_id</script>")
    List<MomentCount> countVisibleByMoments(@Param("momentIds") List<Long> momentIds,
                                            @Param("viewerId") long viewerId);

    /** 单条动态下访客可见的留言总数（详情页用） */
    @Select("SELECT count(*) FROM kb_comment WHERE moment_id = #{momentId}" + VISIBLE_CLAUSE)
    long countVisibleForMoment(@Param("momentId") long momentId,
                               @Param("viewerId") long viewerId);

    // ==================== 站主视角 ====================

    /**
     * 某条动态下的评论，站主视角（含悄悄话与已屏蔽）。
     *
     * <p>⚠️ 不要用「拉全站留言再 filter」代替：留言攒到几百条以后每次看一条
     * 动态都要把整张表捞进内存，而且分页上限一截断就静默丢数据。</p>
     */
    @Select("SELECT " + COLUMNS + " FROM kb_comment WHERE moment_id = #{momentId}"
            + " ORDER BY created_at ASC LIMIT #{limit}")
    List<KbComment> selectMomentCommentsForAdmin(@Param("momentId") long momentId,
                                                 @Param("limit") int limit);

    @Select("SELECT count(*) FROM kb_comment WHERE moment_id = #{momentId}")
    long countMomentComments(@Param("momentId") long momentId);

    /**
     * 全部留言（含悄悄话、含已屏蔽），站主后台用。
     *
     * <p>⚠️ 范围参数是三态的 {@code scope}（all / board / moment），不是布尔
     * {@code boardOnly}。原来的布尔只有「是不是留言板」两个状态，表达不了
     * 「只要动态评论」—— 传 false 时既不是「只要留言板」，也不该是「全部」，
     * 于是前端只能拉全量再 filter，而 total 和分页还是错的。</p>
     */
    @Select("<script>SELECT " + COLUMNS + " FROM kb_comment WHERE 1 = 1"
            + "<if test='unreadOnly != null and unreadOnly'> AND is_read = FALSE</if>"
            + "<if test=\"scope != null and scope == 'board'\"> AND moment_id IS NULL</if>"
            + "<if test=\"scope != null and scope == 'moment'\"> AND moment_id IS NOT NULL</if>"
            + " ORDER BY created_at DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<KbComment> selectAllForAdmin(@Param("limit") int limit,
                                      @Param("offset") int offset,
                                      @Param("unreadOnly") Boolean unreadOnly,
                                      @Param("scope") String scope);

    @Select("<script>SELECT count(*) FROM kb_comment WHERE 1 = 1"
            + "<if test='unreadOnly != null and unreadOnly'> AND is_read = FALSE</if>"
            + "<if test=\"scope != null and scope == 'board'\"> AND moment_id IS NULL</if>"
            + "<if test=\"scope != null and scope == 'moment'\"> AND moment_id IS NOT NULL</if>"
            + "</script>")
    long countForAdmin(@Param("unreadOnly") Boolean unreadOnly, @Param("scope") String scope);

    @Select("SELECT count(*) FROM kb_comment WHERE is_read = FALSE")
    long countUnread();

    @Update("UPDATE kb_comment SET is_read = TRUE WHERE is_read = FALSE")
    int markAllRead();

    @Update("UPDATE kb_comment SET is_read = TRUE WHERE id = #{id}")
    int markReadById(@Param("id") long id);

    /** 某个设备指纹发过多少条留言（站主后台：同一设备换昵称刷留言时一眼看出） */
    @Select("SELECT count(*) FROM kb_comment WHERE device_id = #{deviceId}")
    long countByDevice(@Param("deviceId") String deviceId);

    /**
     * {@link #countVisibleByMoments} 的行类型。
     *
     * <p>用 {@code AS momentId / AS n} 让 MyBatis 按字段名映射，
     * 不用再写一个 ResultMap。</p>
     */
    class MomentCount {

        private Long momentId;
        private Long n;

        public Long getMomentId() {
            return momentId;
        }

        public void setMomentId(Long momentId) {
            this.momentId = momentId;
        }

        public Long getN() {
            return n;
        }

        public void setN(Long n) {
            this.n = n;
        }
    }

    /** 旧 Repository API 兼容：单条 */
    default java.util.Optional<KbComment> findById(long id) {
        return java.util.Optional.ofNullable(selectById(id));
    }

    /**
     * 旧 Repository API 兼容：改状态（VISIBLE / HIDDEN）。
     *
     * <p>⚠️ 返回受影响行数，不是 void —— service 里靠 {@code == 0} 判断
     * 「留言不存在」，返回 void 会让那个判断编译不过，也会让「留言已删还
     * 提示屏蔽成功」这种事静默发生。</p>
     */
    default int updateStatus(long id, String status) {
        KbComment c = selectById(id);
        if (c == null) {
            return 0;
        }
        c.setStatus(status);
        return updateById(c);
    }

    /** 旧 Repository API 兼容：改正文（编辑留言），返回受影响行数 */
    default int updateContent(long id, String content) {
        KbComment c = selectById(id);
        if (c == null) {
            return 0;
        }
        c.setContent(content);
        return updateById(c);
    }

    /** 旧 Repository API 兼容：id 为 null 时把所有未读标为已读 */
    default int markRead(Long id) {
        return id == null ? markAllRead() : markReadById(id);
    }

    /** 旧 Repository API 兼容：分页列表 */
    default java.util.List<KbComment> findAllForAdmin(int limit, int offset,
                                                      Boolean unreadOnly, String scope) {
        return selectAllForAdmin(limit, offset, unreadOnly, scope);
    }

    // ⚠️ countForAdmin / countUnread 不需要兼容包装：上面那两个 @Select 方法的签名
    //    与旧 Repository 完全一致（unreadOnly + scope / 无参），service 直接调就行。
    //    自动生成过一次同名 default 方法，结果签名重复直接编译失败，
    //    而且方法体 return countForAdmin(...) 会自己调自己（无限递归）—— 已删。

    /** 旧 Repository API 兼容：转成 Map 方便按 momentId 取 */
    default java.util.Map<Long, Long> countVisibleByMoments(java.util.List<Long> momentIds,
                                                            Long viewerId) {
        java.util.Map<Long, Long> out = new java.util.LinkedHashMap<>();
        for (MomentCount row : countVisibleByMoments(momentIds,
                viewerId == null ? 0L : viewerId)) {
            out.put(row.getMomentId(), row.getN());
        }
        return out;
    }

    // ===== 旧 Repository API 兼容（service 里调用点保持原样） =====

    /** 旧 Repository API 兼容：留言板列表（访客视角） */
    default java.util.List<KbComment> findBoardForVisitor(Long viewerId, int limit, int offset) {
        return selectBoardForVisitor(viewerId == null ? 0L : viewerId, limit, offset);
    }

    /** 旧 Repository API 兼容：动态下留言（访客视角） */
    default java.util.List<KbComment> findMomentCommentsForVisitor(long momentId, Long viewerId, int limit) {
        return selectMomentCommentsForVisitor(momentId, viewerId == null ? 0L : viewerId, limit);
    }

    /** 旧 Repository API 兼容：动态下留言（站主视角，含悄悄话与已屏蔽） */
    default java.util.List<KbComment> findMomentCommentsForAdmin(long momentId, int limit) {
        return selectMomentCommentsForAdmin(momentId, limit);
    }

    /** 旧 Repository API 兼容：主页每条动态的留言预览 */
    default java.util.List<KbComment> findVisiblePreviews(java.util.List<Long> momentIds,
                                                          Long viewerId, int perMoment) {
        return selectVisiblePreviews(momentIds, viewerId == null ? 0L : viewerId, perMoment);
    }

}
