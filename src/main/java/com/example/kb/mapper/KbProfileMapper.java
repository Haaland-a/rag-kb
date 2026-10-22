package com.example.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.domain.po.KbProfile;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

/**
 * kb_profile 表访问（「关于我」，单行表）。
 *
 * <p>这张表永远只有 {@code id = 1} 一行（建表时有
 * {@code CHECK (id = 1)} 约束），所以没有「插一行」而是「不存在就插、
 * 存在就整行覆盖」，用 PG 的 {@code ON CONFLICT} 一条语句解决，
 * 不用先 select 判断再 insert/update（那样并发下会撞唯一键）。</p>
 *
 * <p>三个 JSONB 列 {@code about / links / tags} 靠实体上的
 * {@code @TableField(typeHandler = ...)} 转换，这里不用管。</p>
 */
@Mapper
public interface KbProfileMapper extends BaseMapper<KbProfile> {

    /**
     * 读单行。
     *
     * <p>⚠️ SQL 放在 XML 里而不是用 {@code @Select}：三个 jsonb 列必须走
     * resultMap 上显式声明的 TypeHandler。实体上的 {@code autoResultMap = true}
     * 只对 BaseMapper 自带的方法生效，自定义注解语句不会应用字段级 typeHandler ——
     * 线上实证：保存后数据库里 about 有值，接口却返回空数组。</p>
     */
    KbProfile selectSingleton();

    /**
     * 整行写入（id 恒为 1）。
     *
     * <p>SQL 在 {@code resources/mapper/ProfileMapper.xml}：
     * {@code INSERT ... ON CONFLICT (id) DO UPDATE SET ...} 这种带括号冲突目标
     * 的方言，用注解拼出来可读性太差，而且还要处理三个 jsonb 列的类型转换。</p>
     */
    int saveSingleton(KbProfile profile);

    @Delete("DELETE FROM kb_profile WHERE id = 1")
    int deleteSingleton();

    // ===== 旧 Repository API 兼容（service 里调用点保持原样） =====

    /** 旧 Repository API 兼容：读单行，空表返回 empty */
    default java.util.Optional<KbProfile> find() {
        return java.util.Optional.ofNullable(selectSingleton());
    }

    /** 旧 Repository API 兼容：整行写入 */
    default void save(KbProfile p) {
        saveSingleton(p);
    }

    /** 旧 Repository API 兼容：删单行（回到「用配置文件默认值」） */
    default int delete() {
        return deleteSingleton();
    }

}
