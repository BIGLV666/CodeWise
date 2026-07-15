package org.example.servicemessage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;
@Configuration
@EnableAsync
public class AsyncThreadConfig {
    @Bean(name = "service-message")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 核心线程数（池中一直保留的线程数）
        executor.setCorePoolSize(5);

        // 最大线程数（任务多时最多扩展到的线程数）
        executor.setMaxPoolSize(20);

        // 队列容量（当核心线程满时，任务放入队列）
        executor.setQueueCapacity(100);

        // 线程空闲时间（秒）
        executor.setKeepAliveSeconds(60);

        // 线程名前缀
        executor.setThreadNamePrefix("service-message");

        // 拒绝策略（队列满时的处理）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();
        return executor;
    }
}
