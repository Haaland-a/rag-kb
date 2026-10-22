package com.example.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.domain.po.KbUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * kb_user 表访问。
 *
 * <p>注意这里<b>没有</b>「按用户名模糊查」之类的方法：账号数是个位数，
 * 也没有用户列表页。需要列表时用 {@link BaseMapper#selectList} 带 Wrapper。</p>
 */
@Mapper
public interface KbUserMapper extends BaseMapper<KbUser> {

    /**
     * 按用户名查（登录用）。
     *
     * <p>⚠️ 返回 null 而不是抛异常 —— 调用方必须把「用户不存在」和
     * 「密码错误」处理成完全一样的响应，否则就有用户枚举漏洞。</p>
     */
    @Select("SELECT * FROM kb_user WHERE username = #{username}")
    KbUser selectByUsername(@Param("username") String username);

    /**
     * 这台设备是否已经注册过账号。
     *
     * <p>设备指纹存在 localStorage，清缓存就能换一个新的 —— 所以这是
     * 「提高批量注册成本」，不是「绝对防止」。真正兜底的是同 IP 限流。</p>
     */
    @Select("SELECT count(*) FROM kb_user WHERE device_id = #{deviceId}")
    long countByDeviceId(@Param("deviceId") String deviceId);

    /**
     * 这个 IP 在最近 hours 小时内注册了几个账号。
     *
     * <p>⚠️ {@code created_at > now() - (? || ' hours')::interval}：
     * 直接把整数拼进 SQL 是注入风险，用 {@code ||} 拼成字符串再转 interval
     * 是最短的合法写法（PG 没有 {@code make_interval} 的简写重载能配合 ?）。</p>
     */
    @Select("SELECT count(*) FROM kb_user"
            + " WHERE register_ip = #{ip} AND created_at > now() - (#{hours} || ' hours')::interval")
    long countByRegisterIpSince(@Param("ip") String ip, @Param("hours") int hours);

    /**
     * 用户列表（站主后台用），新注册的排前面。
     *
     * <p>用 {@code LIMIT/OFFSET} 分页而不是 MP 的 {@code selectPage}：
     * 账号量是个位数到几十，一次 count + 一次 limit 就够，
     * 不必为一个后台列表页去装分页拦截器。真要上十万账号再说。</p>
     *
     * <p>⚠️ 返回的是 {@code KbUser} 实体，里面带 {@code passwordHash}。
     * <b>绝对不要在 Controller 里直接返回它</b> —— 必须经
     * {@code IAuthService#listUsers} 转成
     * {@link com.example.kb.domain.vo.UserRowVO}。</p>
     */
    @Select("SELECT * FROM kb_user ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}")
    java.util.List<KbUser> selectRecent(@Param("limit") int limit, @Param("offset") int offset);

    /** 最近 hours 小时内新注册的账号数（后台概览用） */
    @Select("SELECT count(*) FROM kb_user WHERE created_at > now() - (#{hours} || ' hours')::interval")
    long countNewSince(@Param("hours") int hours);

    @Update("UPDATE kb_user SET password_hash = #{passwordHash} WHERE id = #{id}")
    int updatePasswordHash(@Param("id") long id, @Param("passwordHash") String passwordHash);

    @Update("UPDATE kb_user SET last_login_at = now() WHERE id = #{id}")
    int updateLastLoginAt(@Param("id") long id);

    /**
     * 删掉除指定用户名以外的所有账号。
     *
     * <p>⚠️ 单用户模式下每次启动都会调用它；开注册之后<b>绝不能</b>再调，
     * 否则重启一次就把所有注册用户删光。这行 SQL 现在只被
     * {@code AdminInitializer} 里那段已注释掉的代码引用。</p>
     */
    @Update("DELETE FROM kb_user WHERE username <> #{keepUsername}")
    int deleteAllExcept(@Param("keepUsername") String keepUsername);

    /** 旧 Repository API 兼容 */
    default java.util.Optional<KbUser> findByUsername(String username) {
        return java.util.Optional.ofNullable(selectByUsername(username));
    }

    /** 旧 Repository API 兼容 */
    default java.util.Optional<KbUser> findById(long id) {
        return java.util.Optional.ofNullable(selectById(id));
    }

    /** 旧 Repository API 兼容 */
    default boolean existsByDeviceId(String deviceId) {
        return countByDeviceId(deviceId) > 0;
    }

    /** 旧 Repository API 兼容：用户总数 */
    default long count() {
        return selectCount(null);
    }
}
