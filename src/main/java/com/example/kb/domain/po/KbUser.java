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
 * 注册用户。站主与普通用户同一张表，靠 {@code rag.admin.username} 判定身份。
 *
 * <p>对应表 {@code kb_user}。字段与列的映射靠 {@code map-underscore-to-camel-case}，只有列名与字段名对不上的才写 {@code @TableField}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "kb_user")
public class KbUser {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String username;
    /** BCrypt 散列，60 字符 */
    private String passwordHash;
    private String displayName;
    /** 浏览器指纹，防同设备多账号 */
    private String deviceId;
    /** 注册来源 IP，配合 device_id 做软限制 */
    private String registerIp;
    private OffsetDateTime createdAt;
    private OffsetDateTime lastLoginAt;
}
