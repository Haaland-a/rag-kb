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
 * 切块结果。{@code vectorId} 指向 pgvector 的 vector_store.id。
 *
 * <p>对应表 {@code kb_chunk}。字段与列的映射靠 {@code map-underscore-to-camel-case}，只有列名与字段名对不上的才写 {@code @TableField}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "kb_chunk")
public class KbChunk {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long documentId;
    /** 同一文档内从 0 连续编号 */
    private Integer chunkIndex;
    /** 内存载体，不落库（省一次 JOIN） */
    @TableField(exist = false)
    private String filename;
    /** PDF 才有；MD / TXT 为 null */
    private Integer pageNumber;
    /** MD 的标题层级，如「架构 &gt; 存储」 */
    private String headingPath;
    private String content;
    private int tokenCount = 0;
    private String contentHash;
    /** vector_store.id（UUID 字符串） */
    private String vectorId;
    private OffsetDateTime createdAt;
}
