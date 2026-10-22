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
 * 项目展示卡片。
 *
 * <p>对应表 {@code kb_project}。字段与列的映射靠 {@code map-underscore-to-camel-case}，只有列名与字段名对不上的才写 {@code @TableField}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "kb_project")
public class KbProject {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String title;
    private String description;
    private String coverUrl;
    /** 逗号分隔，前端拆成数组 */
    private String tech;
    private String repoUrl;
    private String demoUrl;
    private int sortOrder = 0;
    private boolean visible = true;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
