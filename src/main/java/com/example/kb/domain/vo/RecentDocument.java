package com.example.kb.domain.vo;

/**
 * 主页「最近收录」的一项。
 *
 * <p>只暴露文件名 / 块数 / 类型，不含全文 —— 游客能知道站里有多少内容，
 * 但看不到内容本身（要看内容得问 AI，走检索）。</p>
 */
public record RecentDocument(String filename, int chunkCount, String fileType) {
}
