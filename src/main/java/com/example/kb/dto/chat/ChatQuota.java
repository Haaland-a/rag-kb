package com.example.kb.dto.chat;

/**
 * 问答配额（按 IP 每小时限流）。
 *
 * <p>前端进主页时调 {@code GET /api/chat/quota} 拿一次，用于显示
 * 「你还可以问 N 次」—— 让访客心里有数，好过突然被 429 拒绝。</p>
 */
public class ChatQuota {

    /** 每小时上限 */
    private int limit;

    /** 剩余次数；-1 表示不限（站主本人） */
    private int remaining;

    /** 当前请求者是不是站主 */
    private boolean admin;

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public int getRemaining() {
        return remaining;
    }

    public void setRemaining(int remaining) {
        this.remaining = remaining;
    }

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }
}
