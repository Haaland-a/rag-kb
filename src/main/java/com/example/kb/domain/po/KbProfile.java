package com.example.kb.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.kb.common.mybatis.LinkListJsonbTypeHandler;
import com.example.kb.common.mybatis.StringListJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * kb_profile 单行表：站主资料（「关于我」等）。
 *
 * <p>⚠️ 这张表存在的唯一理由是<b>让站主能在后台改关于我</b>。
 * 之前这些内容全在 {@code application.yml} 的 {@code rag.site.*} 里，
 * 改一句话要 SSH 上去改配置文件再重启 —— 对一个「个人网站」来说太别扭了。</p>
 *
 * <p>⚠️ <b>表里没数据时回落到配置文件</b>（见 {@code IProfileService}）：
 * 这样第一次部署不用先手工塞一行默认值，配置文件里的 value 依然可作兜底。</p>
 *
 * <p>⚠️ about / links / tags 在数据库里是 JSONB。用 {@code autoResultMap = true}
 * 让 MyBatis-Plus 在查询时按字段上声明的 {@code typeHandler} 解析，
 * 否则读到的是裸字符串，业务代码到处 {@code JsonNode}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "kb_profile", autoResultMap = true)
public class KbProfile {

    /** 恒为 1（表上有 CHECK 约束），不靠自增 */
    @TableId(value = "id", type = IdType.INPUT)
    private int id = 1;

    private String owner = "";

    private String avatarText = "";

    /** 头像图片 URL。非空时优先显示图片，为空退回 {@code avatarText} */
    private String avatarUrl = "";

    private String tagline = "";

    private String intro = "";

    /** 「关于我」更多段落，JSONB 字符串数组 */
    @TableField(typeHandler = StringListJsonbTypeHandler.class)
    private List<String> about = new ArrayList<>();

    /** 外链卡片，JSONB 的 {label, url} 数组 */
    @TableField(typeHandler = LinkListJsonbTypeHandler.class)
    private List<Link> links = new ArrayList<>();

    /** 技能/兴趣标签，JSONB 字符串数组 */
    @TableField(typeHandler = StringListJsonbTypeHandler.class)
    private List<String> tags = new ArrayList<>();

    private OffsetDateTime updatedAt;

    /**
     * 一个外链条目。
     *
     * <p>⚠️ 刻意<b>不</b>复用配置类里的 Link：那个是配置绑定的对象，
     * 配置项改名会波及数据库读出来的对象。两个方向的数据各自独立演进。</p>
     */
    @Data
    public static class Link {

        private String label = "";

        private String url = "";

        public Link() {
        }

        public Link(String label, String url) {
            this.label = label;
            this.url = url;
        }
    }
}
