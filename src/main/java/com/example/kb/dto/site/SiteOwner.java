package com.example.kb.dto.site;

import java.util.List;

/**
 * 站主资料（主页首屏 + 关于我区）。
 *
 * <p>⚠️ 内容来源有两个：数据库 {@code kb_profile}（站主在后台编辑过）或
 * 配置文件 {@code rag.site.*}（还没编辑过）。这个 record 不关心是哪个来源，
 * 由 {@code ProfileService.resolve()} 负责合并后交过来。</p>
 *
 * <p>⚠️ {@code links} 用的是 {@link LinkDto} 而不是 {@code SiteProperties.Link}：
 * 前者是接口契约，后者是配置绑定对象。混用会让「改配置项名字」变成
 * 「改前端接口字段名」。</p>
 *
 * @param name       站主名字
 * @param avatarText 头像文字（没有头像图片时用首字母）
 * @param avatarUrl  头像图片 URL。非空时前端显示图片，为空才退回 {@code avatarText}
 * @param tagline    名字下面一句话
 * @param intro      关于我第一段
 * @param about      关于我更多段落
 * @param tags       技能/兴趣标签
 * @param links      外链卡片
 */
public record SiteOwner(String name,
                        String avatarText,
                        String avatarUrl,
                        String tagline,
                        String intro,
                        List<String> about,
                        List<String> tags,
                        List<LinkDto> links) {
}
