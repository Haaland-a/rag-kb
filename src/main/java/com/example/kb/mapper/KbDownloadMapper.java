package com.example.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.domain.po.KbDownload;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * kb_download 表访问（简历 / 作品集下载区）。
 */
@Mapper
public interface KbDownloadMapper extends BaseMapper<KbDownload> {

    /** 站主视角：含隐藏的 */
    @Select("SELECT * FROM kb_download ORDER BY sort_order ASC, id DESC")
    List<KbDownload> selectAllOrdered();

    /** 访客视角：只要 visible=true */
    @Select("SELECT * FROM kb_download WHERE visible = TRUE ORDER BY sort_order ASC, id DESC")
    List<KbDownload> selectVisibleOrdered();

    /**
     * 改标题 / 排序 / 可见性，null 表示不改（同 KbPhotoMapper.updateMeta）。
     */
    @Update("UPDATE kb_download SET title = COALESCE(#{title}, title),"
            + " sort_order = COALESCE(#{sortOrder}, sort_order),"
            + " visible = COALESCE(#{visible}, visible) WHERE id = #{id}")
    int updateMeta(@Param("id") long id,
                   @Param("title") String title,
                   @Param("sortOrder") Integer sortOrder,
                   @Param("visible") Boolean visible);

    /**
     * 下载数 +1。
     *
     * <p>⚠️ 必须写成 {@code download_count = download_count + 1}（数据库端自增），
     * <b>不能</b>「先 select 出当前值、Java 里 +1、再 update 回去」——
     * 两个人同时点下载会读到同一个旧值，最后只加了一次。</p>
     */
    @Update("UPDATE kb_download SET download_count = download_count + 1 WHERE id = #{id}")
    int incrementDownload(@Param("id") long id);

    /** 旧 Repository API 兼容 */
    default java.util.List<KbDownload> findAll() {
        return selectAllOrdered();
    }

    /** 旧 Repository API 兼容 */
    default java.util.List<KbDownload> findVisible() {
        return selectVisibleOrdered();
    }

    /** 旧 Repository API 兼容 */
    default java.util.Optional<KbDownload> findById(long id) {
        return java.util.Optional.ofNullable(selectById(id));
    }
}
