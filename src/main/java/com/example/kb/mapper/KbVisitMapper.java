package com.example.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.domain.po.KbVisit;
import com.example.kb.domain.vo.VisitorSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * kb_visit 表访问（访客日志）。
 *
 * <p>⚠️ 这张表会随时间无限增长。当前没做自动清理 —— 2C2G 磁盘 40G，
 * 每行按 300 字节算，100 万行才 300MB，够用很久。要清理时加一条
 * {@code DELETE FROM kb_visit WHERE created_at < now() - interval '90 days'}
 * 的定时任务即可。</p>
 */
@Mapper
public interface KbVisitMapper extends BaseMapper<KbVisit> {

    /** 后台补归属地用：取一批还没查过归属地的记录 */
    @Select("SELECT * FROM kb_visit WHERE geo_status = 'PENDING' ORDER BY id LIMIT #{limit}")
    List<KbVisit> selectPendingGeo(@Param("limit") int limit);

    /** 归属地查到了，写回五个字段并把状态改成 DONE */
    @Update("UPDATE kb_visit SET country = #{country}, region = #{region}, city = #{city},"
            + " isp = #{isp}, geo_status = 'DONE' WHERE id = #{id}")
    int updateGeoSingle(@Param("id") long id,
                        @Param("country") String country,
                        @Param("region") String region,
                        @Param("city") String city,
                        @Param("isp") String isp);

    /** 归属地查不到（内网 IP / 接口全挂），标记 FAILED 避免每轮重复扫同一行 */
    @Update("UPDATE kb_visit SET geo_status = 'FAILED' WHERE id = #{id}")
    int markGeoFailed(@Param("id") long id);

    /** 后台列表用，倒序分页 */
    @Select("SELECT * FROM kb_visit ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}")
    List<KbVisit> selectRecent(@Param("limit") int limit, @Param("offset") int offset);

    /**
     * 提问总次数。
     *
     * <p>只算 {@code question IS NOT NULL} 的行 —— 单纯的「访问主页」也会写一条
     * kb_visit（path 为 /api/chat），只有真发了问题才带 question 字段。</p>
     */
    @Select("SELECT count(*) FROM kb_visit WHERE question IS NOT NULL")
    long countQuestions();

    /**
     * 按 IP 聚合，看谁来得最勤 —— 站主最关心的那张表。
     *
     * <p>⚠️ 归属地取「最近一次」而不是 {@code max(country)}：同一 IP 上一秒
     * 可能标成 PENDING、下一秒查到「中国 山西 晋中」，用 max() 会按字母序
     * 挑出一个错的。{@code (array_agg(... ORDER BY created_at DESC))[1]}
     * 才是「最新那条」。SQL 在 {@code resources/mapper/KbVisitMapper.xml}。</p>
     */
    List<VisitorSummary> summarizeByIp(@Param("limit") int limit);

    /** 旧 Repository API 兼容 */
    default java.util.List<KbVisit> findRecent(int limit, int offset) {
        return selectRecent(limit, offset);
    }

    /** 旧 Repository API 兼容 */
    default java.util.Optional<KbVisit> findById(long id) {
        return java.util.Optional.ofNullable(selectById(id));
    }

    /** 旧 Repository API 兼容：总访问数 */
    default long count() {
        return selectCount(null);
    }

    // ⚠️ summarizeByIp 不用写兼容包装：上面那个 @Select 方法（XML 里实现）
    //    签名就是 summarizeByIp(int limit)，service 直接调即可。
    //    自动生成过同名 default 方法，签名重复编译失败、方法体还会自己调自己。

    /** 旧 Repository API 兼容：待补归属地的记录 */
    default java.util.List<KbVisit> findPendingGeo(int limit) {
        return selectPendingGeo(limit);
    }

    /** 旧 Repository API 兼容：写回归属地 */
    default void updateGeo(long id, String country, String region, String city, String isp) {
        updateGeoSingle(id, country, region, city, isp);
    }

}
