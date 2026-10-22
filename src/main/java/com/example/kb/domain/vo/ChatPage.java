package com.example.kb.domain.vo;

import java.util.List;

/**
 * 问答记录分页。
 *
 * @param items  当前页
 * @param total  总条数
 * @param limit  生效的每页条数（被服务端夹过上限）
 * @param offset 生效的偏移量
 */
public record ChatPage(List<ChatLogDto> items, long total, int limit, int offset) {
}
