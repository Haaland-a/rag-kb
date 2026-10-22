package com.example.kb.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.example.kb.common.mybatis.StringJsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/**
 * 一次问答记录。{@code answer} 随流式输出增量写回，中断时可能只有半截。
 *
 * <p>对应表 {@code kb_chat}。字段与列的映射靠 {@code map-underscore-to-camel-case}，只有列名与字段名对不上的才写 {@code @TableField}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "kb_chat")
public class KbChat {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String ip;
    private String question;
    private String answer;
    /** 引用来源，JSONB */
    @TableField(value = "sources", typeHandler = StringJsonbTypeHandler.class)
    private String sourcesJson;
    private int sourceCount = 0;
    private Integer elapsedMs;
    private Integer answerChars;
    private boolean complete = true;
    @TableField("is_admin")
    private boolean admin = false;
    private OffsetDateTime createdAt;
}
