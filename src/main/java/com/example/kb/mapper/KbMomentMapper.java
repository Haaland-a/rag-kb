package com.example.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.domain.po.KbMoment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * kb_moment 表访问（日常动态）。
 */
@Mapper
public interface KbMomentMapper extends BaseMapper<KbMoment> {

    /** 站主视角分页：含仅自己可见的 */
    @Select("SELECT * FROM kb_moment ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}")
    List<KbMoment> selectAllPage(@Param("limit") int limit, @Param("offset") int offset);

    /** 访客视角分页：只要 visible=true */
    @Select("SELECT * FROM kb_moment WHERE visible = TRUE"
            + " ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}")
    List<KbMoment> selectVisiblePage(@Param("limit") int limit, @Param("offset") int offset);

    @Select("<script>SELECT count(*) FROM kb_moment"
            + "<if test='visibleOnly'> WHERE visible = TRUE</if></script>")
    long countMoments(@Param("visibleOnly") boolean visibleOnly);

    /** 只改正文与可见性，null 表示不改 */
    @Update("UPDATE kb_moment SET content = COALESCE(#{content}, content),"
            + " visible = COALESCE(#{visible}, visible),"
            + " updated_at = now() WHERE id = #{id}")
    int updateContent(@Param("id") long id,
                      @Param("content") String content,
                      @Param("visible") Boolean visible);

    /**
     * 记下「这条动态同步到照片墙后对应的照片 id」。
     *
     * <p>传 null 是合法的，表示「那张照片被单独删了，这条动态不再算已同步」。
     * 所以这里<b>不能</b>用 COALESCE。</p>
     */
    @Update("UPDATE kb_moment SET synced_photo_id = #{photoId}, updated_at = now() WHERE id = #{id}")
    int updateSyncedPhoto(@Param("id") long id, @Param("photoId") Long photoId);

    /** 反查：照片墙那张图是从哪条动态同步过去的（删照片时要回头清 synced_photo_id） */
    @Select("SELECT * FROM kb_moment WHERE synced_photo_id = #{photoId}")
    List<KbMoment> selectBySyncedPhotoId(@Param("photoId") long photoId);

    /** 旧 Repository API 兼容：站主视角分页 */
    default java.util.List<KbMoment> findAll(int limit, int offset) {
        return selectAllPage(limit, offset);
    }

    /** 旧 Repository API 兼容：访客视角分页 */
    default java.util.List<KbMoment> findVisible(int limit, int offset) {
        return selectVisiblePage(limit, offset);
    }

    /** 旧 Repository API 兼容 */
    default java.util.Optional<KbMoment> findById(long id) {
        return java.util.Optional.ofNullable(selectById(id));
    }

    /** 旧 Repository API 兼容 */
    default long count(boolean visibleOnly) {
        return countMoments(visibleOnly);
    }

    /** 旧 Repository API 兼容 */
    default void deleteById(long id) {
        deleteById((java.io.Serializable) id);
    }

    /** 旧 Repository API 兼容：按照片墙 id 反查动态 */
    default java.util.List<KbMoment> findBySyncedPhotoId(long photoId) {
        return selectBySyncedPhotoId(photoId);
    }

}
