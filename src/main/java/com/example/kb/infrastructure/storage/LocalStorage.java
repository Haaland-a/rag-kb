package com.example.kb.infrastructure.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘存储（{@code rag.storage.type=local}）。
 *
 * <p>文件落在 {@code rag.storage.local-dir}（默认 {@code ./data/media}），
 * 通过 {@code /media/**} 静态映射暴露出去（见 {@code WebConfig}）。</p>
 *
 * <p>⚠️ 缺点必须知道：</p>
 * <ol>
 *   <li>图片流量走服务器带宽。2C2G 轻量机通常 3-5Mbps，一张 3MB 的手机照
 *       要传 5 秒，访客多了会互相拖慢</li>
 *   <li>重装服务器 / 换机器要手动搬目录</li>
 *   <li>不备份就没了</li>
 * </ol>
 * <p>所以这条路只用于「暂时没 OSS 凭据」或本地开发。生产建议切 OSS。</p>
 *
 * <p>⚠️ 这个类<b>不是 @Component</b>：它由 {@link com.example.kb.config.StorageConfig}
 * 按配置决定是否创建。加了 @Component 会和 StorageConfig 里的 @Bean 冲突，
 * Spring 报「required a single bean, but 2 were found」启动即失败。</p>
 */
public class LocalStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalStorage.class);

    /** 访问前缀，和 WebConfig 里的资源映射保持一致 */
    public static final String URL_PREFIX = "/media/";

    private final Path root;

    public LocalStorage(String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        }
        catch (IOException e) {
            // 建不出目录就让启动失败：等到第一次上传才发现目录不存在更难受
            throw new IllegalStateException("无法创建本地存储目录: " + root, e);
        }
        log.info("本地文件存储已启用，目录 {}", root);
    }

    @Override
    public String put(String key, InputStream in, long size, String contentType) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        return URL_PREFIX + key;
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            Path target = resolve(key);
            boolean deleted = Files.deleteIfExists(target);
            if (deleted) {
                log.info("已删除本地文件 {}", target);
            }
        }
        catch (IOException e) {
            // 删失败只记日志：文件残留比让删除操作报错更可接受
            log.warn("删除本地文件失败 {}：{}", key, e.getMessage());
        }
    }

    @Override
    public String keyOf(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            return null;
        }
        return url.substring(URL_PREFIX.length());
    }

    @Override
    public String type() {
        return "local";
    }

    /**
     * key → 绝对路径，并挡住目录穿越。
     *
     * <p>⚠️ 必须校验：key 里如果出现 {@code ../../etc/passwd}，
     * 直接拼路径就能覆盖任意文件。虽然 key 是我们自己生成的，
     * 但删除接口会从数据库里读 URL 反解 key —— 数据库里的值被改过就危险了。</p>
     */
    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("非法的文件路径: " + key);
        }
        return p;
    }
}
