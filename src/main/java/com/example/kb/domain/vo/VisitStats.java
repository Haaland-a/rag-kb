package com.example.kb.domain.vo;

/**
 * 仪表盘统计（{@code GET /api/visit/stats} 的 data）。
 *
 * @param totalVisits   累计访问次数
 * @param uniqueIps     独立 IP 数
 * @param questionCount 提问次数（有 question 字段的记录数）
 */
public record VisitStats(long totalVisits, long uniqueIps, long questionCount) {
}
