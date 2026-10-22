package com.example.kb.service;

import com.example.kb.domain.dto.AuthDtos.UserInfo;
import com.example.kb.domain.po.KbUser;
import com.example.kb.infrastructure.security.PasswordHasher;
import com.example.kb.infrastructure.security.TokenStore;
import com.example.kb.mapper.KbUserMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Flux;

public interface IAuthService {

    /**
     * 登录成功后的完整身份。
     *
     * <p>⚠️ 为什么把 userId / displayName / admin 一起返回，而不是让前端登录后
     * 再拉一次 {@code /api/auth/me}：多一次往返在 4G 下是 30-80ms 的白屏，
     * 而且中途失败会出现「有 token 但没有用户信息」的中间态，
     * 前端得为这个中间态写一遍兜底逻辑。</p>
     *
     * @param token      会话 token
     * @param userId     用户 id
     * @param username   登录名
     * @param displayName 显示名
     * @param admin      是不是站主
     */
    public record LoginSuccess(String token, long userId, String username,
                               String displayName, boolean admin) {
    }

    /**
     * 认证并建立会话。
     *
     * @return 失败时返回 {@link Optional#empty()}（不区分用户不存在和密码错）
     */
    public Optional<LoginSuccess> authenticate(String username, String password);

    /**
     * 登录。
     *
     * @return token；用户名或密码错误时返回 {@link Optional#empty()}
     */
    public Optional<String> login(String username, String password);

    /**
     * 注册一个普通用户。
     *
     * <p>⚠️ 不校验「管理员是否已存在」之类的东西 —— 管理员由
     * {@link com.example.kb.config.AdminInitializer} 保证，和注册流程无关。</p>
     *
     * @param deviceId 浏览器指纹，为空表示前端没给（此时只走 IP 限制）
     * @param ip       注册来源 IP
     * @return 新用户 id
     * @throws IllegalArgumentException 参数不合法（→ 400）
     * @throws IllegalStateException    设备/IP 超限或用户名已存在（→ 409）
     */
    public long register(String username, String password, String displayName, String deviceId, String ip);

    /** 登出：删服务端会话。token 无效也当成功（幂等） */
    public void logout(String token);

    /** 改密：校验旧密码 → 更新散列 → 踢掉该用户所有已登录会话 */
    public boolean changePassword(long userId, String oldPassword, String newPassword);

    /** 当前登录用户信息（给 /api/auth/me 用） */
    public Optional<KbUser> currentUser(long userId);

    /**
     * 当前登录用户的对外视图（拆掉实体）。
     *
     * <p>⚠️ 为什么不让 Controller 拿到 {@link KbUser} 再自己转：
     * 实体上有 {@code passwordHash}，一旦某个端点不小心
     * {@code R.ok(user)} 就直接把散列发出去了。让实体不出 Service ，
     * Controller 只能拿到 {@link UserInfo}，从类型上就不可能泄露。</p>
     */
    public Optional<UserInfo> currentUserInfo(long userId);

    /**
     * 判断某个用户是不是站主。
     *
     * <p>⚠️ 站主身份靠<b>用户名匹配配置</b>，不是数据库里的 is_admin 标志位。
     * 理由：标志位可以被 SQL 误改（一条 UPDATE 就把普通用户提权了）；
     * 用户名匹配配置则要求改服务器上的 yml 文件，门槛高得多。</p>
     *
     * <p>对外暴露这个方法是因为留言的可见性规则要用它：
     * 站主能看到悄悄话和所有被屏蔽的留言。</p>
     */
    public boolean isAdmin(String username);

    /**
     * 用户列表（站主后台用）。
     *
     * <p>⚠️ 参数夹逼在 Service 里做，Controller 只传原值：分页参数是外部输入，
     * {@code size=100000} 打进来会把整张表捞出来。上限 200 是「一屏最多看几十行，
     * 200 已经够翻到」，同时避免有人拿这个接口当导出用。</p>
     *
     * <p>⚠️ 返回的 {@link com.example.kb.domain.vo.UserRowVO} 里没有
     * {@code passwordHash}，也没有 {@code deviceId}。</p>
     *
     * @param page 页码，从 1 开始；小于 1 按 1 处理
     * @param size 每页条数；小于 1 按 20 处理，大于 200 按 200 处理
     */
    public com.example.kb.domain.vo.UserPage listUsers(int page, int size);

}
