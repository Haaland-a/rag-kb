package com.example.kb.infrastructure.security;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 口令散列 + token 生成。
 *
 * <p>为什么不用 {@code spring-boot-starter-security}：它是给「多角色 + 方法级权限 +
 * 各种 AuthenticationProvider」准备的。这个项目只有一个「登录了才能用」的需求，
 * 引整套 Security 要多 ~30MB 类加载、一条 FilterChain，2C2G 上不划算。
 * 自己写 BCrypt 校验 + UUID token，代码量 60 行，逻辑一眼看完。</p>
 *
 * <p>⚠️ 安全边界（诚实说明）：</p>
 * <ul>
 *   <li>token 是随机 UUID，存在服务端内存里（见 TokenStore），重启全部失效</li>
 *   <li>不抗暴力破解 —— 没有登录次数限制。单机演示场景可接受，
 *       真要上线必须加限流 + HTTPS（现在 token 是明文过 HTTP 的）</li>
 *   <li>密码永不明文存储、永不明文进日志</li>
 * </ul>
 */
@Component
public class PasswordHasher {

    /** BCrypt 固定 60 字符；短于这个长度说明库里存的是明文，直接拒 */
    private static final int BCRYPT_LENGTH = 60;

    private final SecureRandom random = new SecureRandom();

    /**
     * 校验明文口令是否匹配库里的散列。
     *
     * <p>用 {@code BCrypt.checkpw} 而不是自己比字符串：BCrypt 的散列自带盐，
     * 每次结果都不同，必须走它自己的常量时间比较。</p>
     */
    public boolean matches(String rawPassword, String passwordHash) {
        if (rawPassword == null || passwordHash == null || passwordHash.length() != BCRYPT_LENGTH) {
            return false;
        }
        try {
            return org.springframework.security.crypto.bcrypt.BCrypt.checkpw(rawPassword, passwordHash);
        }
        catch (IllegalArgumentException e) {
            // 散列格式不对（比如库里是明文），按不匹配处理
            return false;
        }
    }

    /** 生成 BCrypt 散列（logRounds=10，约 100ms，够慢能扛离线爆破又不至于卡死登录） */
    public String hash(String rawPassword) {
        return org.springframework.security.crypto.bcrypt.BCrypt.hashpw(
                rawPassword, org.springframework.security.crypto.bcrypt.BCrypt.gensalt(10));
    }

    /**
     * 生成登录 token。
     *
     * <p>用 128 位随机数而不是 {@code UUID.randomUUID()}：UUID v4 的 122 位有效随机性
     * 也够，但走 SecureRandom 更直白。Base64 URL 安全编码（不带 padding）后 22 字符，
     * 放 HTTP header 里没有转义问题。</p>
     */
    public String newToken() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 常量时间比较两个 token，防时序侧信道。
     *
     * <p>虽然 token 比对失不失败的差别本身不敏感，但养成习惯成本为零。</p>
     */
    public boolean tokenEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** SHA-256 十六进制（给默认管理员密码等内部用途，非口令存储） */
    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 没有 SHA-256", e);
        }
    }
}
