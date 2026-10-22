package com.example.kb.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应体。
 *
 * <p>只此一套，前后端联调不许多种分页/包装模型（三套并存的坑）。</p>
 *
 * <p>⚠️ {@code data} 字段必须始终序列化（哪怕值是 null）：前端要能稳定地
 * 读 {@code res.data.data} 判断登录状态。如果 Jackson 把 null 键省了，
 * 前端拿到的就是 {@code {code:0,message:"ok"}}，取值变 undefined，
 * 而且这不是能一眼看出来的 bug。</p>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public final class R {

    private final int code;
    private final String message;
    private final Object data;

    private R(int code, String message, Object data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static R ok(Object data) {
        return new R(0, "ok", data);
    }

    public static R ok() {
        return new R(0, "ok", null);
    }

    public static R fail(String message) {
        return new R(1, message, null);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public Object getData() {
        return data;
    }
}
