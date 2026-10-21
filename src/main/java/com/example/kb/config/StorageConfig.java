package com.example.kb.config;

import com.example.kb.infrastructure.storage.FileStorage;
import com.example.kb.infrastructure.storage.LocalStorage;
import com.example.kb.infrastructure.storage.OssStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 存储实现的选择。
 *
 * <p>按 {@code rag.storage.type} 决定用哪个实现：</p>
 * <ul>
 *   <li>{@code local}（默认）—— {@link LocalStorage}</li>
 *   <li>{@code oss} —— {@link OssStorage}，需要 endpoint / bucket / ak 全部齐全</li>
 * </ul>
 *
 * <p>⚠️ 这里用 {@code @Bean} 手动 new 而不是两个 {@code @Component} + {@code @ConditionalOnProperty}：
 * OSS 实现故意不加 {@code @Component}（见 {@link OssStorage} 注释），
 * 手动装配能让「凭据不全」这个错误在启动时就报出来，而不是等到第一次上传才炸。</p>
 */
@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    public FileStorage fileStorage(
            @Value("${rag.storage.type:local}") String type,
            @Value("${rag.storage.local-dir:${java.io.tmpdir}/rag-kb-media}") String localDir,
            @Value("${rag.storage.oss.endpoint:}") String endpoint,
            @Value("${rag.storage.oss.bucket:}") String bucket,
            @Value("${rag.storage.oss.access-key-id:}") String akId,
            @Value("${rag.storage.oss.access-key-secret:}") String akSecret,
            @Value("${rag.storage.oss.public-base:}") String publicBase,
            @Value("${rag.storage.oss.key-prefix:}") String keyPrefix) {

        if ("oss".equalsIgnoreCase(type)) {
            // 这三项缺一个都签不出请求，直接在启动时挡掉，不带病运行
            if (isBlank(endpoint) || isBlank(bucket) || isBlank(akId) || isBlank(akSecret)) {
                throw new IllegalStateException(
                        "rag.storage.type=oss 但凭据不全：需要 endpoint / bucket / "
                                + "access-key-id / access-key-secret 四项都填");
            }
            log.info("文件存储：阿里云 OSS");
            return new OssStorage(endpoint, bucket, akId, akSecret, publicBase, keyPrefix, null);
        }
        log.info("文件存储：本地磁盘（生产建议切 OSS，详见 README）");
        return new LocalStorage(localDir);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
