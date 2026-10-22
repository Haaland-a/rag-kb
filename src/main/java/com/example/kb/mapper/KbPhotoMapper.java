package com.example.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.domain.po.KbPhoto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * kb_photo 表访问（主页照片墙）。
 *
 * <p>站主视角与访客视角分成两个方法，<b>不用一个方法加个 boolean 参数</b>：
 * 带 boolean 的写法在调用处看不出「这次传的是哪个视角」，漏传或传反了
 * 就是「隐藏的照片漏到主页」，而且不会有任何编译错误。两个名字不同的方法
 * 至少逼调用方读一遍方法名。</p>
 */
@Mapper
public interface KbPhotoMapper extends BaseMapper<KbPhoto> {

    /** 站主视角：含 visible=false 的 */
    @Select("<script>SELECT * FROM kb_photo ORDER BY sort_order ASC, id DESC"
            + "<if test='limit != null and limit > 0'> LIMIT #{limit}</if></script>")
    List<KbPhoto> selectAllOrdered(@Param("limit") Integer limit);

    /** 访客视角：只要 visible=true */
    @Select("<script>SELECT * FROM kb_photo WHERE visible = TRUE"
            + " ORDER BY sort_order ASC, id DESC"
            + "<if test='limit != null and limit > 0'> LIMIT #{limit}</if></script>")
    List<KbPhoto> selectVisibleOrdered(@Param("limit") Integer limit);

    /**
     * 只改这三项，null 表示「这一项不动」。
     *
     * <p>用 SQL 的 COALESCE 而不是「先查出来改完再整行更新」：
     * 后台可能有两个人同时开着编辑框，整行更新会用旧值盖掉对方刚改的字段。</p>
     */
    @Update("UPDATE kb_photo SET caption = COALESCE(#{caption}, caption),"
            + " sort_order = COALESCE(#{sortOrder}, sort_order),"
            + " visible = COALESCE(#{visible}, visible) WHERE id = #{id}")
    int updateMeta(@Param("id") long id,
                   @Param("caption") String caption,
                   @Param("sortOrder") Integer sortOrder,
                   @Param("visible") Boolean visible);

    /** 旧 Repository API 兼容：站主视角 */
    default java.util.List<KbPhoto> findAll(int limit) {
        return selectAllOrdered(limit);
    }

    /** 旧 Repository API 兼容：访客视角 */
    default java.util.List<KbPhoto> findVisible(int limit) {
        return selectVisibleOrdered(limit);
    }

    /** 旧 Repository API 兼容 */
    default java.util.Optional<KbPhoto> findById(long id) {
        return java.util.Optional.ofNullable(selectById(id));
    }

    /** 旧 Repository API 兼容：全站照片数 */
    default long count() {
        return selectCount(null);
    }
}
