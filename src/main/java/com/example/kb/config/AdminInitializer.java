package com.example.kb.config;

import com.example.kb.domain.po.KbUser;
import com.example.kb.infrastructure.security.PasswordHasher;
import com.example.kb.mapper.KbUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
/**
 * 启动时保证「唯一管理员」存在，且密码是配置里那个。
 *
 * <p>需求是「只有我一个用户是真正的管理员，没有别的用户」。所以这里的策略不是
 * 「有用户就不管」，而是<b>强制对齐</b>：</p>
 * <ol>
 *   <li>库里没有 haaland → 建一个，密码取配置</li>
 *   <li>库里有 haaland 但密码对不上配置 → 改成配置的密码</li>
 *   <li>删掉除 haaland 以外的所有用户（保证「没有别的用户」）</li>
 * </ol>
 *
 * <p>⚠️ 第 2 条是把<b>配置文件当唯一事实源</b>。好处是改密码不用进服务器跑 SQL，
 * 改生产配置文件重启即可；代价是配置文件里存了明文密码，
 * 必须保证文件权限 600、不进 git。README 里已注明。</p>
 *
 * <p>⚠️ BCrypt 是 100ms 级的慢操作。如果每次启动都无条件 hash 一遍再比对，
 * 启动会多 100ms —— 可以接受，但更重要的是<b>不能</b>在每次启动都 update，
 * 那会让每行 created_at/updated_at 无意义地跳动。所以先比对再决定改不改。</p>
 */
@Component
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final KbUserMapper userMapper;
    private final PasswordHasher passwordHasher;

    /** 管理员账号，默认 haaland（需求指定） */
    private final String adminUsername;

    /** 管理员初始密码。默认空串，必须由配置或环境变量 RAG_ADMIN_PASSWORD 提供 */
    private final String adminPassword;

    public AdminInitializer(KbUserMapper userMapper,
                            PasswordHasher passwordHasher,
                            @Value("${rag.admin.username:demo}") String adminUsername,
                            @Value("${rag.admin.password:}") String adminPassword) {
        this.userMapper = userMapper;
        this.passwordHasher = passwordHasher;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("未配置 rag.admin.password，跳过管理员初始化。"
                    + "登录会一直失败 —— 请在 application-prod.yml 或环境变量 RAG_ADMIN_PASSWORD 里配置。");
            return;
        }

        try {
            ensureAdmin();
            // ⚠️ 不再调用 removeOtherUsers()：开放注册后它会把注册用户全删掉。
            // 普通注册用户不是管理员（管理端点有 requireAdmin 拦着），不需要清理。
        }
        catch (Exception e) {
            // 管理员初始化失败不应该阻止应用启动：站点还能匿名访问，
            // 只是站主登录不了。日志里留着线索，人去看。
            log.error("管理员初始化失败：{}", e.toString(), e);
        }
    }

    private void ensureAdmin() {
        var existing = userMapper.findByUsername(adminUsername);
        if (existing.isEmpty()) {
            KbUser user = new KbUser();
            user.setUsername(adminUsername);
            user.setPasswordHash(passwordHasher.hash(adminPassword));
            user.setDisplayName(adminUsername);
            long id = userMapper.insert(user);
            log.info("已创建管理员账号 {}（id={}），密码取自配置", adminUsername, id);
            return;
        }

        KbUser user = existing.get();
        if (passwordHasher.matches(adminPassword, user.getPasswordHash())) {
            log.info("管理员账号 {} 已存在，密码与配置一致", adminUsername);
            return;
        }

        // 密码对不上 → 以配置为准。这就是「改配置重启即生效」的实现
        userMapper.updatePasswordHash(user.getId(), passwordHasher.hash(adminPassword));
        log.info("管理员账号 {} 的密码已按配置重置", adminUsername);
    }

    /**
     * 删掉除管理员以外的所有用户。
     *
     * <p>⚠️ <b>已废弃，不再调用</b>。原需求是「只有我一个用户是真正的管理员，
     * 没有别的用户」，所以每次启动兜底清理一次。现在需求改成「用户可以注册，
     * 但注册门槛是同一设备不能注册多账号」，所以这个清理会<b>把注册用户全删掉</b> ——
     * 必须停用。</p>
     *
     * <p>保留方法体而不是直接删除：万一将来又要回到单用户模式，
     * 这里改一行调用就恢复了，且历史原因写在旁边不用重新推理。
     * 唯一的用户隔离现在靠「管理端点白名单 + requireAdmin」保证，
     * 也就是普通注册用户<u>根本不是管理员</u>，不需要删。</p>
     *
     * @deprecated 开放注册后不可调用
     */
    @Deprecated
    @SuppressWarnings("unused")
    private void removeOtherUsers() {
        long total = userMapper.count();
        if (total <= 1) {
            return;
        }
        int removed = userMapper.deleteAllExcept(adminUsername);
        if (removed > 0) {
            log.warn("检测到 {} 个非管理员账号，已删除（本站只允许 {} 一个用户）。"
                    + "如需多用户请先删除 AdminInitializer.removeOtherUsers()",
                    removed, adminUsername);
        }
    }
}
