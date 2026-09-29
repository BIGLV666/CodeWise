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

    /**
     * 点赞通知专用线程池。
     *
     * <p>任务体是阻塞 IO（读 Redis 元数据缓存、Feign 取用户信息、RabbitMQ 投递），
     * 必须与桶消费线程池隔离：{@code LikeTask}/{@code SolutionLookTask} 的桶消费是长期
     * 占用线程的刷库循环，混用会让点赞计数落库被通知任务饿死。</p>
     *
     * <p>队列满时 {@code CallerRunsPolicy} 让任务退化为调用线程（HTTP 线程）执行——
     * 通知不丢，代价只是该次点赞请求同步等一次 IO。</p>
     */
    @Bean(name = "communityLikeNotifyExecutor")
    public ThreadPoolTaskExecutor communityLikeNotifyExecutor(
            @Value("${codewise.async.like-notify.core-pool-size:2}") int corePoolSize,
            @Value("${codewise.async.like-notify.max-pool-size:8}") int maxPoolSize,
            @Value("${codewise.async.like-notify.queue-capacity:500}") int queueCapacity,
            @Value("${codewise.async.like-notify.keep-alive-seconds:60}") int keepAliveSeconds,
            @Value("${codewise.async.like-notify.await-termination-seconds:30}") int awaitTerminationSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("community-like-notify-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }
}
