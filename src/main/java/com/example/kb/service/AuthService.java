package com.example.kb.service;

import com.example.kb.domain.KbUser;
import com.example.kb.dto.auth.AuthDtos.UserInfo;
import com.example.kb.infrastructure.security.PasswordHasher;
import com.example.kb.infrastructure.security.TokenStore;
import com.example.kb.repository.KbUserRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
/**
 * 登录 / 注册 / 登出 / 改密。
 *
 * <p>三条设计约束：</p>
 * <ol>
 *   <li><b>登录失败不区分「用户不存在」和「密码错误」</b>，统一返回同一句话。
 *       否则等于告诉攻击者「这个账号存在」，是常见的用户枚举漏洞。</li>
 *   <li><b>密码校验走 BCrypt.checkpw</b>，不用字符串 equals —— BCrypt 自带盐，
 *       每次散列结果都不同，只能靠它自己的常量时间比较。</li>
 *   <li><b>token 存在服务端内存</b>（TokenStore），不落库。重启即全部失效，
 *       用户重登一次即可，换来零维护成本。</li>
 * </ol>
 *
 * <h3>⚠️ 为什么要开放注册，以及怎么保住「只有站主是管理员」</h3>
 * <p>需求从「只有我一个用户」变成「用户可以注册，但同一设备不能注册多账号」。
 * 关键区别在于：<b>普通注册用户不是管理员</b>。管理员身份不靠数据库标记，
 * 而靠 {@link com.example.kb.config.AuthInterceptor} 里的路径白名单 +
 * 各站主端点上的 {@code requireAdmin()} 检查。所以注册用户再多，
 * 他们也进不了后台 —— 删掉「清理非管理员账号」那段逻辑是安全的。</p>
 *
 * <h3>⚠️ 注册门槛是三层的，但都不是硬墙</h3>
 * <ol>
 *   <li><b>设备指纹唯一索引</b>（{@code uk_kb_user_device}）：数据库层硬约束，
 *       同一 deviceId 只能有一个账号。清浏览器缓存会换指纹，能绕过。</li>
 *   <li><b>同 IP 24 小时限 2 个</b>：挡「清缓存换指纹重来」。
 *       动态 IP / 校园网 NAT 会误伤，所以阈值宽松。</li>
 *   <li><b>用户名唯一</b>：数据库约束。</li>
 * </ol>
 * <p>真正的兜底是「注册了也只能留言」—— 没有站主权限，注册多个账号
 * 除了刷留言没有别的作用，而刷留言有 IP 限流。</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 同 IP 在多少小时内最多注册几个账号 */
    private static final int REGISTER_IP_WINDOW_HOURS = 24;
    /** 该窗口内允许的账号数（含第一个，所以 2 表示「还能再注册 1 个」） */
    private static final int REGISTER_IP_MAX = 2;

    private final KbUserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final TokenStore tokenStore;

    /** 站主用户名。注册时禁止占用，否则有人抢注这个名字就乱套了 */
    private final String adminUsername;

    public AuthService(KbUserRepository userRepository,
                       PasswordHasher passwordHasher,
                       TokenStore tokenStore,
                       @Value("${rag.admin.username:demo}") String adminUsername) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.tokenStore = tokenStore;
        this.adminUsername = adminUsername;
    }

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
    public Optional<LoginSuccess> authenticate(String username, String password) {
        Optional<KbUser> found = userRepository.findByUsername(username);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        KbUser user = found.get();
        if (!passwordHasher.matches(password, user.getPasswordHash())) {
            return Optional.empty();
        }
        userRepository.updateLastLoginAt(user.getId());
        String token = tokenStore.create(user);
        return Optional.of(new LoginSuccess(
                token,
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                isAdmin(user.getUsername())));
    }

    /**
     * 登录。
     *
     * @return token；用户名或密码错误时返回 {@link Optional#empty()}
     */
    public Optional<String> login(String username, String password) {
        return authenticate(username, password).map(LoginSuccess::token);
    }

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
    public long register(String username, String password, String displayName,
                         String deviceId, String ip) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (name.length() < 3 || name.length() > 20) {
            throw new IllegalArgumentException("用户名要 3 到 20 个字符");
        }
        // 只允许字母数字下划线：中文用户名在 URL、日志、@提及里都会出麻烦
        if (!name.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("用户名只能用字母、数字和下划线");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        if (password.length() > 128) {
            throw new IllegalArgumentException("密码太长了");
        }

        // ⚠️ 站主用户名不可注册。否则有人抢注，虽然登录不进去（密码不同），
        // 但界面上会显示成两个同名账号，能骗到访客
        if (name.equalsIgnoreCase(adminUsername)) {
            throw new IllegalStateException("这个名字被站主保留了，换一个吧");
        }

        // 设备指纹：数据库唯一索引是硬约束，这里先查一次给出人话提示，
        // 免得用户看到 PG 的 duplicate key 报错原文
        if (userRepository.existsByDeviceId(deviceId)) {
            log.info("注册被拒：设备已注册过账号 device={}", deviceId);
            throw new IllegalStateException("这个浏览器已经注册过账号了，一台设备只能注册一个");
        }

        // 同 IP 软限制
        long sameIp = userRepository.countByRegisterIpSince(ip, REGISTER_IP_WINDOW_HOURS);
        if (sameIp >= REGISTER_IP_MAX) {
            log.info("注册被拒：同 IP 超限 ip={} 已有={}", ip, sameIp);
            throw new IllegalStateException(
                    "这个网络下今天注册的账号有点多了，换个网络或者明天再来");
        }

        // 用户名唯一：让数据库抛，但转成人话
        if (userRepository.findByUsername(name).isPresent()) {
            throw new IllegalStateException("这个用户名已经有人用了");
        }

        KbUser user = new KbUser();
        user.setUsername(name);
        user.setPasswordHash(passwordHasher.hash(password));
        user.setDisplayName(displayName == null || displayName.isBlank() ? name : displayName.trim());
        user.setDeviceId(deviceId == null || deviceId.isBlank() ? null : deviceId);
        user.setRegisterIp(ip);
        long id = userRepository.insert(user);
        log.info("新用户注册成功 id={} username={} device={} ip={}", id, name, deviceId, ip);
        return id;
    }

    /** 登出：删服务端会话。token 无效也当成功（幂等） */
    public void logout(String token) {
        tokenStore.remove(token);
    }

    /** 改密：校验旧密码 → 更新散列 → 踢掉该用户所有已登录会话 */
    public boolean changePassword(long userId, String oldPassword, String newPassword) {
        Optional<KbUser> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return false;
        }
        KbUser user = found.get();
        if (!passwordHasher.matches(oldPassword, user.getPasswordHash())) {
            return false;
        }
        userRepository.updatePasswordHash(userId, passwordHasher.hash(newPassword));
        tokenStore.removeByUserId(userId);
        return true;
    }

    /** 当前登录用户信息（给 /api/auth/me 用） */
    public Optional<KbUser> currentUser(long userId) {
        return userRepository.findById(userId);
    }

    /**
     * 当前登录用户的对外视图（拆掉实体）。
     *
     * <p>⚠️ 为什么不让 Controller 拿到 {@link KbUser} 再自己转：
     * 实体上有 {@code passwordHash}，一旦某个端点不小心
     * {@code R.ok(user)} 就直接把散列发出去了。让实体不出 Service ，
     * Controller 只能拿到 {@link UserInfo}，从类型上就不可能泄露。</p>
     */
    public Optional<UserInfo> currentUserInfo(long userId) {
        return currentUser(userId).map(this::toInfo);
    }

    private UserInfo toInfo(KbUser user) {
        UserInfo info = new UserInfo();
        info.setId(user.getId());
        info.setUsername(user.getUsername());
        info.setDisplayName(user.getDisplayName());
        info.setAdmin(isAdmin(user.getUsername()));
        return info;
    }

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
    public boolean isAdmin(String username) {
        return username != null && username.equalsIgnoreCase(adminUsername);
    }
}
