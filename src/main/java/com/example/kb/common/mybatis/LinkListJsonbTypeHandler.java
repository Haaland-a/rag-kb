package com.example.kb.common.mybatis;

import com.example.kb.domain.po.KbProfile;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;

/**
 * {@code List<KbProfile.Link>} ↔ PG {@code jsonb}。
 *
 * <p>只用于 kb_profile.links。外链是 {label, url} 的小对象，
 * 单独建表不值当，JSONB 更省事。</p>
 */
public class LinkListJsonbTypeHandler extends AbstractListJsonbTypeHandler<KbProfile.Link> {

    public LinkListJsonbTypeHandler() {
        super(new TypeReference<List<KbProfile.Link>>() {
        });
    }
}
