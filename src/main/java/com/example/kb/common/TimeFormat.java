package com.example.kb.common;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间格式化：给后台页面用的「2026-10-20 18:54:58」。
 *
 * <h3>⚠️ 为什么必须转时区，不能直接 format</h3>
 * <p>数据库列是 {@code TIMESTAMPTZ}，PG 内部存 UTC，JDBC 读出来再被
 * RowMapper 转成 {@code OffsetDateTime}（偏移量 +00）。如果直接
 * {@code DateTimeFormatter.format(ts)}，得到的是 <b>UTC 时间</b> ——
 * 服务器本地 18:54 的事件会显示成 10:54，整整差 8 小时。</p>
 *
 * <p>这是本项目真实踩过的坑：{@code kb_visit} 走 Jackson 序列化
 * {@code OffsetDateTime}（输出带 Z 的 ISO-8601，浏览器 {@code new Date()}
 * 会自动转本地，所以是对的），而手工格式化的那几个 DTO 少了这一步，
 * 于是同一时刻在两个页面显示成两个时间。</p>
 *
 * <h3>为什么转到服务器时区而不是浏览器时区</h3>
 * <p>这些时间是给<b>站主</b>看的：他想知道「别人几点访问的」，
 * 参照系是自己的作息。访客在哪个时区不影响这个判断。
 * 所以统一按服务器时区（{@code Asia/Shanghai}）呈现，
 * 和服务器 {@code date} 命令的输出一致，对得上日志。</p>
 *
 * <p>⚠️ 需要给<b>访客</b>看的时间（比如动态发布时间）仍应保留
 * ISO-8601 让浏览器自己转 —— 那种情况下访客的本地时间才是有意义的。</p>
 */
public final class TimeFormat {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeFormat() {
    }

    /**
     * 转成服务器时区的「yyyy-MM-dd HH:mm:ss」。
     *
     * @param t 从数据库读出的 UTC 时间
     * @return 格式化结果；入参为 null 时返回 null（前端按「未知」处理）
     */
    public static String format(OffsetDateTime t) {
        if (t == null) {
            return null;
        }
        return FMT.format(t.atZoneSameInstant(ZoneId.systemDefault()));
    }
}
