package com.example.kb.domain.vo;

/**
 * 一个外链卡片。
 *
 * <p>⚠️ 这个 record 存在的理由：外链有两个来源 ——
 * 配置文件的 {@code rag.site.links} 和数据库的 {@code kb_profile.links}。
 * 两种来源的 Java 类型原本不同（{@code SiteProperties.Link} 和
 * {@code KbProfile.Link}），如果 {@link SiteOwner} 直接引用其中一个，
 * 另一个来源就得在 Service 里手工转换。改成统一用小 record 之后，
 * 两个来源各自转换成它，{@code SiteOwner} 只认识这一种类型。</p>
 *
 * @param label 显示文字，如「GitHub」
 * @param url   完整地址，可以是 https 也可以是 mailto:
 */
public record LinkDto(String label, String url) {
}
