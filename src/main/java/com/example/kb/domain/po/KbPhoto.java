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
 * 照片墙的一张图。
 *
 * <p>对应表 {@code kb_photo}。字段与列的映射靠 {@code map-underscore-to-camel-case}，只有列名与字段名对不上的才写 {@code @TableField}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "kb_photo")
public class KbPhoto {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /** /media/xxx 或 https://bucket.oss/xxx */
    private String url;
    private String caption;
    private int sortOrder = 0;
    private boolean visible = true;
    private Integer width;
    private Integer height;
    private Long fileSize;
    private OffsetDateTime createdAt;
}
