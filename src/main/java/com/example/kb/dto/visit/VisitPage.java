package com.example.kb.dto.visit;

import com.example.kb.domain.KbVisit;

import java.util.List;

/**
 * 访问流水一页（{@code GET /api/visit} 的 data）。
 */
public record VisitPage(List<KbVisit> items, long total, int limit, int offset) {
}
