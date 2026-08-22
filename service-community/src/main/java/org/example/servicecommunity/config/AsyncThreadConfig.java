package org.example.servicecommunity.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncThreadConfig {
    @Bean(name = "communityCascadeDeleteExecutor")
    public ThreadPoolTaskExecutor communityCascadeDeleteExecutor(
            @Value("${codewise.async.cascade-delete.core-pool-size:2}") int corePoolSize,
            @Value("${codewise.async.cascade-delete.max-pool-size:8}") int maxPoolSize,
            @Value("${codewise.async.cascade-delete.queue-capacity:100}") int queueCapacity,
            @Value("${codewise.async.cascade-delete.keep-alive-seconds:60}") int keepAliveSeconds,
            @Value("${codewise.async.cascade-delete.await-termination-seconds:30}") int awaitTerminationSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("community-cascade-delete-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }

    @Bean(name = "communityBucketConsumerExecutor")
    public ThreadPoolTaskExecutor communityBucketConsumerExecutor(
            @Value("${codewise.async.bucket-consumer.core-pool-size:2}") int corePoolSize,
            @Value("${codewise.async.bucket-consumer.max-pool-size:4}") int maxPoolSize,
            @Value("${codewise.async.bucket-consumer.queue-capacity:100}") int queueCapacity,
            @Value("${codewise.async.bucket-consumer.await-termination-seconds:30}") int awaitTerminationSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("community-bucket-consumer-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }
}
