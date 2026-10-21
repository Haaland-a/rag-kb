package com.example.kb.infrastructure.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * 阿里云 OSS 存储（{@code rag.storage.type=oss}）。
 *
 * <p>⚠️ 这个类<b>不是 @Component</b>，由 {@link StorageConfig} 在配置齐全时才 new 出来。
 * 原因：用本地存储时不该加载 OSS SDK 的类；而且凭据没填时如果硬创建会启动即失败。</p>
 *
 * <p>返回的 URL 形态：{@code https://<bucket>.<endpoint>/<key>}。
 * 公共读 bucket 下这个 URL 可以直接放进 {@code <img src>}，不需要签名。</p>
 */
public class OssStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(OssStorage.class);

    private final OSS client;
    private final String bucket;
    /** 形如 https://my-bucket.oss-cn-hangzhou.aliyuncs.com，结尾无斜杠 */
    private final String publicBase;
    /** 对象键前缀，空串表示写在 bucket 根下。见构造器注释 */
    private final String keyPrefix;
    private final boolean closeClient;

    /**
     * @param endpoint 区域节点，如 {@code oss-cn-hangzhou.aliyuncs.com}（不带 https://）
     * @param bucket   bucket 名
     * @param akId     AccessKey ID
     * @param akSecret AccessKey Secret
     * @param publicBase 公共访问域名。留空则自动拼 {@code https://bucket.endpoint}
     * @param keyPrefix 对象键前缀，如 {@code my-app}。留空则直接写在 bucket 根下。
     *                  ⚠️ 这个参数是为了<b>不污染 bucket 里已有的文件</b>：
     *                  多个项目共用一个 bucket 时，各自加前缀就互不干扰，
     *                  删除时也只会删到自己前缀下的对象。
     */
    public OssStorage(String endpoint, String bucket, String akId, String akSecret,
                      String publicBase, String keyPrefix, OSS existingClient) {
        this.bucket = bucket;
        this.keyPrefix = normalizePrefix(keyPrefix);
        if (existingClient != null) {
            // 测试时可以注入现成的 client
            this.client = existingClient;
            this.closeClient = false;
        }
        else {
            this.client = new OSSClientBuilder().build(endpoint, akId, akSecret);
            this.closeClient = true;
        }
        String base = (publicBase == null || publicBase.isBlank())
                ? "https://" + bucket + "." + endpoint
                : publicBase;
        this.publicBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        log.info("OSS 存储已启用，bucket={}，访问前缀 {}，对象键前缀 {}",
                bucket, this.publicBase, this.keyPrefix.isEmpty() ? "(无)" : this.keyPrefix);
    }

    /** 去掉首尾斜杠；空串表示不加前缀 */
    private static String normalizePrefix(String keyPrefix) {
        if (keyPrefix == null) {
            return "";
        }
        String p = keyPrefix.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** 逻辑键 → OSS 里真实的对象键 */
    private String fullKey(String key) {
        return keyPrefix.isEmpty() ? key : keyPrefix + "/" + key;
    }

    /** OSS 里真实的对象键 → 逻辑键（去掉前缀） */
    private String logicalKey(String fullKey) {
        if (keyPrefix.isEmpty()) {
            return fullKey;
        }
        String prefix = keyPrefix + "/";
        return fullKey.startsWith(prefix) ? fullKey.substring(prefix.length()) : fullKey;
    }

    @Override
    public String put(String key, InputStream in, long size, String contentType) throws IOException {
        ObjectMetadata meta = new ObjectMetadata();
        if (size > 0) {
            meta.setContentLength(size);
        }
        if (contentType != null && !contentType.isBlank()) {
            meta.setContentType(contentType);
        }
        // ⚠️ 不设 ACL：bucket 是公共读的话默认继承；单独设 per-object ACL
        // 在某些 bucket 策略下反而会失败。权限统一在 bucket 层管。
        String objectKey = fullKey(key);
        try {
            client.putObject(bucket, objectKey, in, meta);
        }
        catch (RuntimeException e) {
            // OSS SDK 抛的是 unchecked（OSSException / ClientException），
            // 统一转成 IOException，让上层只处理一种异常
            throw new IOException("上传 OSS 失败: " + e.getMessage(), e);
        }
        return publicBase + "/" + objectKey;
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        String objectKey = fullKey(key);
        try {
            client.deleteObject(bucket, objectKey);
            log.info("已删除 OSS 对象 {}", objectKey);
        }
        catch (RuntimeException e) {
            log.warn("删除 OSS 对象失败 {}：{}", objectKey, e.getMessage());
        }
    }

    /**
     * 从公开 URL 反推逻辑键（去掉前缀）。
     *
     * <p>⚠️ 必须先去掉 {@code keyPrefix} 再返回，因为 {@link #delete} 会再拼一次。
     * 如果这里返回带前缀的完整键，删除时会变成 {@code my-app/my-app/xxx}，
     * 结果「删除成功但文件还在」。这个 bug 只有在开了前缀时才会暴露。</p>
     */
    @Override
    public String keyOf(String url) {
        if (url == null || !url.startsWith(publicBase + "/")) {
            return null;
        }
        return logicalKey(url.substring(publicBase.length() + 1));
    }

    @Override
    public String type() {
        return "oss";
    }

    /** 容器关闭时释放连接池 */
    public void shutdown() {
        if (closeClient) {
            client.shutdown();
        }
    }
}
