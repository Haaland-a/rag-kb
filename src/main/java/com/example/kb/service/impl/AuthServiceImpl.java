package com.example.kb.service.impl;

import com.example.kb.common.TimeFormat;
import com.example.kb.domain.dto.AuthDtos.UserInfo;
import com.example.kb.domain.po.KbUser;
import com.example.kb.domain.vo.UserPage;
import com.example.kb.domain.vo.UserRowVO;
import com.example.kb.infrastructure.security.PasswordHasher;
import com.example.kb.infrastructure.security.TokenStore;
import com.example.kb.mapper.KbUserMapper;
import com.example.kb.service.IAuthService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements IAuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    /** 同 IP 在多少小时内最多注册几个账号 */
    private static final int REGISTER_IP_WINDOW_HOURS = 24;
    /** 该窗口内允许的账号数（含第一个，所以 2 表示「还能再注册 1 个」） */
    private static final int REGISTER_IP_MAX = 2;
    /** 用户列表单页上限：后台列表页不需要更多，也防止被当导出接口用 */
    private static final int MAX_USER_PAGE_SIZE = 200;

    private final KbUserMapper userMapper;
    private final PasswordHasher passwordHasher;
    private final TokenStore tokenStore;

    /** 站主用户名。注册时禁止占用，否则有人抢注这个名字就乱套了 */
    private final String adminUsername;

    public AuthServiceImpl(KbUserMapper userMapper,
                       PasswordHasher passwordHasher,
                       TokenStore tokenStore,
                       @Value("${rag.admin.username:demo}") String adminUsername) {
        this.userMapper = userMapper;
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

    /**
     * 认证并建立会话。
     *
     * @return 失败时返回 {@link Optional#empty()}（不区分用户不存在和密码错）
     */
    public Optional<LoginSuccess> authenticate(String username, String password) {
        Optional<KbUser> found = userMapper.findByUsername(username);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        KbUser user = found.get();
        if (!passwordHasher.matches(password, user.getPasswordHash())) {
            return Optional.empty();
        }
        userMapper.updateLastLoginAt(user.getId());
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

        // ⚠️ 站主用户名不可注册。否则有人抢注管理员用户名，
        // 虽然登录不进去（密码不同），但界面上会显示成两个同名账号，能骗到访客
        if (name.equalsIgnoreCase(adminUsername)) {
            throw new IllegalStateException("这个名字被站主保留了，换一个吧");
        }

        // 设备指纹：数据库唯一索引是硬约束，这里先查一次给出人话提示，
        // 免得用户看到 PG 的 duplicate key 报错原文
        if (userMapper.existsByDeviceId(deviceId)) {
            log.info("注册被拒：设备已注册过账号 device={}", deviceId);
            throw new IllegalStateException("这个浏览器已经注册过账号了，一台设备只能注册一个");
        }

        // 同 IP 软限制
        long sameIp = userMapper.countByRegisterIpSince(ip, REGISTER_IP_WINDOW_HOURS);
        if (sameIp >= REGISTER_IP_MAX) {
            log.info("注册被拒：同 IP 超限 ip={} 已有={}", ip, sameIp);
            throw new IllegalStateException(
                    "这个网络下今天注册的账号有点多了，换个网络或者明天再来");
        }

        // 用户名唯一：让数据库抛，但转成人话
        if (userMapper.findByUsername(name).isPresent()) {
            throw new IllegalStateException("这个用户名已经有人用了");
        }

        KbUser user = new KbUser();
        user.setUsername(name);
        user.setPasswordHash(passwordHasher.hash(password));
        user.setDisplayName(displayName == null || displayName.isBlank() ? name : displayName.trim());
        user.setDeviceId(deviceId == null || deviceId.isBlank() ? null : deviceId);
        user.setRegisterIp(ip);
        // ⚠️ insert() 返回的是受影响行数（int），不是自增 id；id 由 MP 回填到实体上
        userMapper.insert(user);
        long id = user.getId();
        log.info("新用户注册成功 id={} username={} device={} ip={}", id, name, deviceId, ip);
        return id;
    }

    /** 登出：删服务端会话。token 无效也当成功（幂等） */
    public void logout(String token) {
        tokenStore.remove(token);
    }

    /** 改密：校验旧密码 → 更新散列 → 踢掉该用户所有已登录会话 */
    public boolean changePassword(long userId, String oldPassword, String newPassword) {
        Optional<KbUser> found = userMapper.findById(userId);
        if (found.isEmpty()) {
            return false;
        }
        KbUser user = found.get();
        if (!passwordHasher.matches(oldPassword, user.getPasswordHash())) {
            return false;
        }
        userMapper.updatePasswordHash(userId, passwordHasher.hash(newPassword));
        tokenStore.removeByUserId(userId);
        return true;
    }

    /** 当前登录用户信息（给 /api/auth/me 用） */
    public Optional<KbUser> currentUser(long userId) {
        return userMapper.findById(userId);
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
     * 用户列表（站主后台用）。
     *
     * <p>分页参数在这里夹逼：{@code size} 上限 200，避免有人传
     * {@code size=100000} 把整张表捞出来当导出接口用。</p>
     *
     * <p>⚠️ 三个数字分别 try/catch：后台页面「有几个账号」算错了不该让
     * 整个列表 500 —— 列表本身才是这个页面存在的理由。</p>
     */
    public UserPage listUsers(int page, int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_USER_PAGE_SIZE));
        int safePage = Math.max(1, page);
        int offset = (safePage - 1) * safeSize;

        List<KbUser> rows = userMapper.selectRecent(safeSize, offset);

        long total = 0;
        long adminCount = 0;
        long newIn7Days = 0;
        try {
            total = userMapper.count();
        }
        catch (Exception e) {
            log.warn("统计账号总数失败：{}", e.getMessage());
        }
        try {
            newIn7Days = userMapper.countNewSince(7 * 24);
        }
        catch (Exception e) {
            log.warn("统计 7 天新注册数失败：{}", e.getMessage());
        }

        List<UserRowVO> items = new ArrayList<>(rows.size());
        for (KbUser u : rows) {
            UserRowVO vo = toRow(u);
            items.add(vo);
            if (vo.isAdmin()) {
                adminCount++;
            }
        }
        return new UserPage(items, total, adminCount, newIn7Days, safePage, safeSize);
    }

    private UserRowVO toRow(KbUser u) {
        UserRowVO vo = new UserRowVO();
        vo.setId(u.getId());
        vo.setUsername(u.getUsername());
        vo.setDisplayName(u.getDisplayName());
        vo.setCreatedAt(TimeFormat.format(u.getCreatedAt()));
        vo.setLastLoginAt(TimeFormat.format(u.getLastLoginAt()));
        vo.setRegisterIp(u.getRegisterIp());
        vo.setAdmin(isAdmin(u.getUsername()));
        vo.setDeviceBound(u.getDeviceId() != null && !u.getDeviceId().isBlank());
        return vo;
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
