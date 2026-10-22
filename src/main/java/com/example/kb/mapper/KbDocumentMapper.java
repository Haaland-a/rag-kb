package com.example.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.domain.po.KbDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * kb_document 表访问。
 *
 * <p>简单 CRUD（按主键查、按主键删、插一行）直接继承 {@link BaseMapper}，
 * 不再手写；只有需要拼业务条件的才在下面声明。</p>
 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {

    /** 上传查重：整篇 SHA-256 相同直接复用已有文档 */
    @Select("SELECT * FROM kb_document WHERE content_hash = #{contentHash} LIMIT 1")
    KbDocument selectByContentHash(@Param("contentHash") String contentHash);

    /** 列表按 id 倒序（新的在前） */
    @Select("SELECT * FROM kb_document ORDER BY id DESC")
    List<KbDocument> selectAllOrdered();

    /**
     * 改状态 + 错误信息。
     *
     * <p>⚠️ 显式写 {@code error_msg = #{errorMsg}} 而不是靠 {@code updateById}：
     * 成功时要把它清成 null，而 {@code updateById} 对 null 字段是「不更新」，
     * 会把上一次的失败原因留在成功记录上。</p>
     */
    @Update("UPDATE kb_document SET status = #{status}, error_msg = #{errorMsg}, "
            + "updated_at = now() WHERE id = #{id}")
    int updateStatusAndError(@Param("id") long id,
                             @Param("status") String status,
                             @Param("errorMsg") String errorMsg);

    @Update("UPDATE kb_document SET page_count = #{pageCount}, updated_at = now() WHERE id = #{id}")
    int updatePageCount(@Param("id") long id, @Param("pageCount") int pageCount);

    @Update("UPDATE kb_document SET chunk_count = #{chunkCount}, updated_at = now() WHERE id = #{id}")
    int updateChunkCount(@Param("id") long id, @Param("chunkCount") int chunkCount);

    /** 旧 Repository API 兼容：按 id 查，返回 Optional（找不到不抛） */
    default java.util.Optional<KbDocument> findById(long id) {
        return java.util.Optional.ofNullable(selectById(id));
    }

    /** 旧 Repository API 兼容：全表按 id 倒序 */
    default java.util.List<KbDocument> findAll() {
        return selectAllOrdered();
    }

    /** 旧 Repository API 兼容 */
    default java.util.Optional<KbDocument> findByContentHash(String contentHash) {
        return java.util.Optional.ofNullable(selectByContentHash(contentHash));
    }

    /** 旧 Repository API 兼容：改状态与错误信息 */
    default void updateStatus(long id, String status, String errorMsg) {
        updateStatusAndError(id, status, errorMsg);
    }
}
