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
 * 上传的文档（一篇 PDF / MD / TXT 一行）。
 *
 * <p>对应表 {@code kb_document}。字段与列的映射靠 {@code map-underscore-to-camel-case}，只有列名与字段名对不上的才写 {@code @TableField}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "kb_document")
public class KbDocument {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /** 原始文件名 */
    private String filename;
    /** 整篇 SHA-256，用于上传查重 */
    private String contentHash;
    /** pdf / md / txt */
    private String fileType;
    private Long fileSize;
    private Integer pageCount;
    private int chunkCount = 0;
    /** PENDING / INDEXING / READY / FAILED */
    private String status;
    private String errorMsg;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
