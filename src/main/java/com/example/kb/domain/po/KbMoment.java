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
 * 日常动态，可带一张图或一段视频。
 *
 * <p>对应表 {@code kb_moment}。字段与列的映射靠 {@code map-underscore-to-camel-case}，只有列名与字段名对不上的才写 {@code @TableField}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "kb_moment")
public class KbMoment {

    /** 图片类媒体 */
    public static final String MEDIA_IMAGE = "IMAGE";

    /** 视频类媒体 */
    public static final String MEDIA_VIDEO = "VIDEO";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String content;
    private String mediaUrl;
    /** IMAGE / VIDEO */
    private String mediaType;
    private Integer mediaWidth;
    private Integer mediaHeight;
    private Long mediaSize;
    /** 同步到照片墙后对应的 kb_photo.id */
    private Long syncedPhotoId;
    private boolean visible = true;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
