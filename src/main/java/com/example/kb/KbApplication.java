package com.example.kb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RAG 知识库启动类。
 *
 * <p>@EnableAsync：文档索引入库（解析→切块→embedding→写库）走独立线程池，
 * 上传接口立即返回，不堵 Web 容器线程（100 页 PDF 要几十秒）。</p>
 *
 * <p>@EnableScheduling：访客 IP 归属地异步补全（{@code GeoIpService.enrichPending}）
 * 靠它驱动。不加这个注解 @Scheduled 是哑的 —— 日志里一行错误都没有，
 * 只是永远不跑，这种坑最难查。</p>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class KbApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbApplication.class, args);
    }
}
