package com.example.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.domain.po.KbChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * kb_chunk 表访问。
 *
 * <p>⚠️ 批量插入用 {@link BaseMapper#insert} 逐条走 MyBatis-Plus，
 * 由它把自增 id 回填到实体的 {@code id} 字段上 —— 这正是
 * {@code VectorStoreGateway} 需要的（要拿 chunk.id 塞进向量 metadata 的 chunkId）。
 * 早先用 JdbcTemplate 时得靠 {@code RETURNING id} 手动回填。</p>
 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    @Select("SELECT * FROM kb_chunk WHERE document_id = #{documentId} ORDER BY chunk_index")
    List<KbChunk> selectByDocumentId(@Param("documentId") long documentId);

    @Select("SELECT * FROM kb_chunk WHERE document_id = #{documentId} "
            + "ORDER BY chunk_index LIMIT #{limit} OFFSET #{offset}")
    List<KbChunk> selectPageByDocumentId(@Param("documentId") long documentId,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    @Select("<script>SELECT * FROM kb_chunk WHERE vector_id IN "
            + "<foreach collection='vectorIds' item='v' open='(' separator=',' close=')'>#{v}</foreach>"
            + "</script>")
    List<KbChunk> selectByVectorIds(@Param("vectorIds") List<String> vectorIds);

    @Select("<script>SELECT * FROM kb_chunk WHERE id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<KbChunk> selectByIds(@Param("ids") List<Long> ids);

    /** 回填 vector_store.id，双轨关联的另一半 */
    @Update("UPDATE kb_chunk SET vector_id = #{vectorId} WHERE id = #{chunkId}")
    int updateVectorId(@Param("chunkId") long chunkId, @Param("vectorId") String vectorId);

    @Select("SELECT count(*) FROM kb_chunk WHERE document_id = #{documentId}")
    long countByDocumentId(@Param("documentId") long documentId);

    /**
     * 关键词检索（RRF 的一路，当前未接入检索链路）。
     *
     * <p>pg_trgm 的 gin 索引对中文子串匹配有效，相似度阈值用
     * {@code pg_trgm.similarity}，走 {@code idx_kb_chunk_content_trgm}。</p>
     */
    @Select("SELECT * FROM kb_chunk "
            + "WHERE content % #{keyword} AND similarity(content, #{keyword}) >= #{threshold} "
            + "ORDER BY similarity(content, #{keyword}) DESC LIMIT #{limit}")
    List<KbChunk> keywordSearch(@Param("keyword") String keyword,
                                @Param("limit") int limit,
                                @Param("threshold") double threshold);

    /** 旧 Repository API 兼容：逐条插入，MyBatis-Plus 会把自增 id 回填到实体上 */
    default void insertAll(java.util.List<KbChunk> chunks) {
        for (KbChunk c : chunks) {
            insert(c);
        }
    }

    /** 旧 Repository API 兼容：整篇的全部切片 */
    default java.util.List<KbChunk> findByDocumentId(long documentId) {
        return selectByDocumentId(documentId);
    }

    /** 旧 Repository API 兼容：分页（limit, offset） */
    default java.util.List<KbChunk> findByDocumentId(long documentId, int limit, int offset) {
        return selectPageByDocumentId(documentId, limit, offset);
    }

    /** 旧 Repository API 兼容 */
    default java.util.List<KbChunk> findByVectorIds(java.util.List<String> vectorIds) {
        return selectByVectorIds(vectorIds);
    }

    /** 旧 Repository API 兼容 */
    default java.util.List<KbChunk> findByIds(java.util.List<Long> ids) {
        return selectByIds(ids);
    }

    /** 旧 Repository API 兼容：删整篇的切片 */
    default void deleteByDocumentId(long documentId) {
        delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocumentId, documentId));
    }
}
