package com.example.kb.domain.vo;

import com.example.kb.domain.po.KbProject;

import java.util.List;

/**
 * 项目卡片（主页「项目」区 + 后台管理共用）。
 *
 * @param id          项目 id
 * @param title       项目名
 * @param description 一句话说明
 * @param coverUrl    截图地址，可为 null（有的项目不放图）
 * @param tech        技术栈标签，已拆成数组（数据库里是逗号分隔字符串）
 * @param repoUrl     GitHub 等仓库地址
 * @param demoUrl     在线演示地址
 * @param sortOrder   排序值
 * @param visible     是否在主页显示
 * @param createdAt   创建时间
 * @param updatedAt   最后修改时间
 */
public record ProjectDto(long id,
                         String title,
                         String description,
                         String coverUrl,
                         List<String> tech,
                         String repoUrl,
                         String demoUrl,
                         int sortOrder,
                         boolean visible,
                         String createdAt,
                         String updatedAt) {

    /**
     * 实体 → DTO。
     *
     * <p>⚠️ 这里把 {@code tech} 从逗号串拆成数组：前端直接 {@code v-for} 渲染标签，
     * 不用每个组件都写一次 {@code split(',')}。拆分逻辑集中在边界处，
     * 后端内部仍然用逗号串存（只有站主一个人用，不值得为它建关联表）。</p>
     */
    public static ProjectDto of(KbProject p) {
        List<String> techList = p.getTech() == null || p.getTech().isBlank()
                ? List.of()
                : java.util.Arrays.stream(p.getTech().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        return new ProjectDto(
                p.getId() == null ? 0L : p.getId(),
                p.getTitle(),
                p.getDescription(),
                p.getCoverUrl(),
                techList,
                p.getRepoUrl(),
                p.getDemoUrl(),
                p.getSortOrder(),
                p.isVisible(),
                p.getCreatedAt() == null ? null : p.getCreatedAt().toString(),
                p.getUpdatedAt() == null ? null : p.getUpdatedAt().toString());
    }
}
