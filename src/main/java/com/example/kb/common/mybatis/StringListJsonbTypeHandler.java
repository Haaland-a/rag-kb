package com.example.kb.common.mybatis;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;

/**
 * {@code List<String>} ↔ PG {@code jsonb}。
 *
 * <p>用于 kb_profile 的 about / tags，以及 kb_chat 的 sources（后者是对象数组，
 * 但存的是 String 形态的 JSON，见 IChatLogService 的写入方式）。</p>
 */
public class StringListJsonbTypeHandler extends AbstractListJsonbTypeHandler<String> {

    public StringListJsonbTypeHandler() {
        super(new TypeReference<List<String>>() {
        });
    }
}
