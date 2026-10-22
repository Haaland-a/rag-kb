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
 * 访客记录。核心是 ip + createdAt；归属地五个字段异步回填。
 *
 * <p>对应表 {@code kb_visit}。字段与列的映射靠 {@code map-underscore-to-camel-case}，只有列名与字段名对不上的才写 {@code @TableField}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "kb_visit")
public class KbVisit {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String ip;
    private String path;
    /** 若这次是问答请求，记录问题原文 */
    private String question;
    /** 以下五列由异步任务回填 */
    private String country;
    private String region;
    private String city;
    private String isp;
    private String userAgent;
    private String referer;
    @TableField("is_admin")
    private boolean admin = false;
    /** PENDING / DONE / FAILED */
    private String geoStatus = "PENDING";
    private OffsetDateTime createdAt;

    /** 拼一个给人看的地点串，如「中国 山西 晋中」，全空时返回 null */
    public String locationText() {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{country, region, city}) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(part);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
