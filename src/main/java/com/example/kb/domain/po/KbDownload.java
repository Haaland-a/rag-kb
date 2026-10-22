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
 * 可下载的简历 / 作品集。
 *
 * <p>对应表 {@code kb_download}。字段与列的映射靠 {@code map-underscore-to-camel-case}，只有列名与字段名对不上的才写 {@code @TableField}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "kb_download")
public class KbDownload {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String title;
    private String url;
    /** pdf / docx / zip */
    private String fileType;
    private Long fileSize;
    private long downloadCount = 0;
    private int sortOrder = 0;
    private boolean visible = true;
    private OffsetDateTime createdAt;
}
