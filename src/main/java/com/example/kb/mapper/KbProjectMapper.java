package com.example.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.domain.po.KbProject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * kb_project 表访问（主页「做过的项目」卡片）。
 *
 * <p>编辑用 {@link BaseMapper#updateById} 整行覆盖，<b>不做 COALESCE 式局部更新</b>：
 * 封面图要能被清空，而「null 表示不改」的写法恰恰清不掉。这是它和
 * 照片墙 / 下载区那几个 updateMeta 不一样的地方 —— 那些只有文本框和开关，
 * 这个有可选图片。</p>
 */
@Mapper
public interface KbProjectMapper extends BaseMapper<KbProject> {

    /** 站主视角：含隐藏的 */
    @Select("SELECT * FROM kb_project ORDER BY sort_order ASC, id DESC")
    List<KbProject> selectAllOrdered();

    /** 访客视角：只要 visible=true */
    @Select("SELECT * FROM kb_project WHERE visible = TRUE ORDER BY sort_order ASC, id DESC")
    List<KbProject> selectVisibleOrdered();

    /** 旧 Repository API 兼容 */
    default java.util.List<KbProject> findAll() {
        return selectAllOrdered();
    }

    /** 旧 Repository API 兼容 */
    default java.util.List<KbProject> findVisible() {
        return selectVisibleOrdered();
    }

    /** 旧 Repository API 兼容 */
    default java.util.Optional<KbProject> findById(long id) {
        return java.util.Optional.ofNullable(selectById(id));
    }

    /** 旧 Repository API 兼容：整行覆盖（封面图要能被清空，所以不走 COALESCE） */
    default void update(KbProject p) {
        updateById(p);
    }

    /** 旧 Repository API 兼容 */
    default void deleteById(long id) {
        deleteById((java.io.Serializable) id);
    }
}
