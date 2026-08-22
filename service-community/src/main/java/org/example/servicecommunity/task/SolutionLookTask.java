package org.example.servicecommunity.task;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommunity.config.RedisBucketSwitcher;
import org.example.servicecommunity.mapper.SolutionMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class SolutionLookTask {
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisBucketSwitcher redisBucketSwitcher;
    private final SolutionMapper solutionMapper;
    private final RedissonClient redissonClient;
    private final ThreadPoolTaskExecutor consumerExecutor;
    private final ConcurrentLinkedQueue<BucketTask> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean consuming = new AtomicBoolean();

    public SolutionLookTask(
            RedisTemplate<String, Object> redisTemplate,
            RedisBucketSwitcher redisBucketSwitcher,
            SolutionMapper solutionMapper,
            RedissonClient redissonClient,
            @Qualifier("communityBucketConsumerExecutor") ThreadPoolTaskExecutor consumerExecutor) {
        this.redisTemplate = redisTemplate;
        this.redisBucketSwitcher = redisBucketSwitcher;
        this.solutionMapper = solutionMapper;
        this.redissonClient = redissonClient;
        this.consumerExecutor = consumerExecutor;
    }

    @PostConstruct
    public void init() {
        redisTemplate.opsForValue().setIfAbsent(RedisContext.SOLUTION_LOOK_BUCKET_KEY, 0);
        log.info("题解浏览量桶任务初始化完成");
    }

    @Scheduled(cron = "0 */1 * * * *")
    public void updateSolutionLookCount() {
        RLock lock = redissonClient.getLock("task:community:solution-look");
        if (!lock.tryLock()) {
            log.debug("浏览量桶切换跳过，已有其他实例执行");
            return;
        }
        try {
            long bucketId = redisBucketSwitcher.switchBucket(RedisContext.SOLUTION_LOOK_BUCKET_KEY);
            String sourceKey = RedisContext.SOLUTION_LOOK_KEY + "-" + bucketId;
            String processingKey = sourceKey + ":processing:" + UUID.randomUUID();
            Boolean renamed = redisTemplate.renameIfAbsent(sourceKey, processingKey);
            if (!Boolean.TRUE.equals(renamed)) {
                log.info("浏览量桶为空，bucketId={}", bucketId);
                return;
            }
            Map<Object, Object> bucket = redisTemplate.opsForHash().entries(processingKey);
            if (bucket.isEmpty()) {
                redisTemplate.delete(processingKey);
                log.info("浏览量桶无增量，bucketId={}", bucketId);
                return;
            }
            queue.offer(new BucketTask(bucketId, processingKey, bucket));
            log.info("浏览量桶已入队，bucketId={}, processingKey={}, entries={}, queueSize={}",
                    bucketId, processingKey, bucket.size(), queue.size());
            startConsumer();
        } catch (Exception exception) {
            log.error("浏览量桶切换或入队失败", exception);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void startConsumer() {
        if (!consuming.compareAndSet(false, true)) {
            log.debug("浏览量桶消费者已运行，queueSize={}", queue.size());
            return;
        }
        log.info("启动浏览量桶消费者，queueSize={}", queue.size());
        consumerExecutor.execute(this::consume);
    }

    private void consume() {
        try {
            BucketTask task;
            while ((task = queue.poll()) != null) {
                try {
                    log.info("开始消费浏览量桶，bucketId={}, entries={}, queueSize={}",
                            task.bucketId(), task.bucket().size(), queue.size());
                    int updated = solutionMapper.updateLookCount(task.bucket());
                    if (updated <= 0) {
                        throw new IllegalStateException("数据库未更新任何浏览量记录");
                    }
                    redisTemplate.delete(task.processingKey());
                    log.info("浏览量桶消费成功，bucketId={}, updated={}, queueSize={}",
                            task.bucketId(), updated, queue.size());
                } catch (Exception exception) {
                    queue.offer(task);
                    log.error("浏览量桶消费失败，已重新入队，bucketId={}, queueSize={}",
                            task.bucketId(), queue.size(), exception);
                    break;
                }
            }
        } finally {
            consuming.set(false);
            if (!queue.isEmpty()) {
                startConsumer();
            } else {
                log.info("浏览量桶消费者完成，queueSize=0");
            }
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("浏览量桶任务停止，queueSize={}", queue.size());
    }

    private record BucketTask(long bucketId, String processingKey, Map<Object, Object> bucket) { }
}
