package com.example.kb.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/**
 * 留言。{@code momentId} 为 null 表示发在留言板上，非 null 表示发在某条动态下。
 *
 * <p>一条记录同时承载「公开留言」和「悄悄话」，靠 {@link #visibility} 区分；
 * 「被站主屏蔽」是另一个维度，靠 {@link #status}。两者正交：
 * 一条悄悄话可以被屏蔽，一条公开留言也可以。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "kb_comment")
public class KbComment {

    /** 公开留言，所有人可见 */
    public static final String VISIBILITY_PUBLIC = "PUBLIC";

    /** 悄悄话，只有站主和作者本人可见 */
    public static final String VISIBILITY_PRIVATE = "PRIVATE";

    /** 正常状态 */
    public static final String STATUS_VISIBLE = "VISIBLE";

    /** 已被站主屏蔽：其他人看不到，作者本人仍能看到 */
    public static final String STATUS_HIDDEN = "HIDDEN";

    /** 游客没填昵称时的兜底 */
    public static final String DEFAULT_NICKNAME = "匿名访客";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** null = 留言板；非 null = 某条动态下 */
    private Long momentId;

    /** null = 游客；非 null = 注册用户 */
    private Long userId;

    private String nickname;

    private String content;

    private String ip;

    private String deviceId;

    /** {@link #VISIBILITY_PUBLIC} / {@link #VISIBILITY_PRIVATE} */
    private String visibility = VISIBILITY_PUBLIC;

    /** {@link #STATUS_VISIBLE} / {@link #STATUS_HIDDEN} */
    private String status = STATUS_VISIBLE;

    /** 回复的父留言 id，null 表示一级留言 */
    private Long parentId;

    @TableField("is_read")
    private boolean read = false;

    private OffsetDateTime createdAt;

    /**
     * 兜底访问器：库里有历史脏数据把 visibility 写成了 null 时按公开处理。
     *
     * <p>⚠️ 不能图省事让 null 变成「悄悄话」——那会导致一条本该公开的留言
     * 突然只有站主看得到，属于「静默丢数据」。</p>
     */
    public String getVisibilityOrDefault() {
        return visibility == null || visibility.isBlank() ? VISIBILITY_PUBLIC : visibility;
    }

    /** 兜底访问器：status 为 null 时按正常可见处理 */
    public String getStatusOrDefault() {
        return status == null || status.isBlank() ? STATUS_VISIBLE : status;
    }
}
