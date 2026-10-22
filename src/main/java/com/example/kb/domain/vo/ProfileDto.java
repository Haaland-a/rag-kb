package com.example.kb.domain.vo;

import com.example.kb.domain.vo.LinkDto;

import java.util.List;

/**
 * 站主资料（后台编辑表单 + 主页「关于我」区）。
 *
 * <p>⚠️ 和 {@link SiteOwner} 的区别：这个 record 多带一个
 * {@code fromConfig} 字段，告诉后台「当前显示的内容来自哪里」。</p>
 * <ul>
 *   <li>{@code fromConfig = true} —— 数据库里还没有保存过，
 *       页面显示的是 {@code application.yml} 的 {@code rag.site.*}。
 *       后台应该提示「当前用的是配置文件内容，保存后以数据库为准」</li>
 *   <li>{@code fromConfig = false} —— 用的是数据库里的值</li>
 * </ul>
 *
 * <p>为什么要暴露这个标志：站主第一次进编辑页时，表单里会预填配置文件的内容。
 * 如果不说清楚，他改一个字点保存，之后所有字段就都固化成数据库版本了，
 * 再改 yml 就完全不生效 —— 这是个会让人困惑半小时的行为。</p>
 *
 * @param owner      名字
 * @param avatarText 头像文字
 * @param avatarUrl  头像图片 URL，空则前端用 {@code avatarText}
 * @param tagline    一句话介绍
 * @param intro      简介首段
 * @param about      更多段落
 * @param links      外链
 * @param tags       标签
 * @param updatedAt  最后保存时间，从未保存过为 null
 * @param fromConfig 是否正在使用配置文件兜底
 */
public record ProfileDto(String owner,
                         String avatarText,
                         String avatarUrl,
                         String tagline,
                         String intro,
                         List<String> about,
                         List<LinkDto> links,
                         List<String> tags,
                         String updatedAt,
                         boolean fromConfig) {
}
