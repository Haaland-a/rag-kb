package com.example.kb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 文档索引用独立线程池。
 *
 * <p>为什么不用 @Async 默认的默认执行器：上传 100 页 PDF 解析 + embedding 要几十秒，
 * 会占满默认池把健康检查之类的小任务堵死。核心池给 2（对齐 2C2G 的 2 核），
 * 队列 100，拒绝策略 CallerRuns —— 队列满时退回上传线程同步执行，
 * 至少不丢任务（用户会看到上传接口变慢，但索引一定写完）。</p>
 */
@Configuration
public class AsyncConfig {

    public static final String INDEXING_EXECUTOR = "indexingExecutor";

    @Bean(name = INDEXING_EXECUTOR)
    public Executor indexingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("kb-indexing-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
