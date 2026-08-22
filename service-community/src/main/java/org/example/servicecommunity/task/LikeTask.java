package org.example.servicecommunity.task;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommunity.config.RedisBucketSwitcher;
import org.example.servicecommunity.mapper.CommentMapper;
import org.example.servicecommunity.mapper.PostMapper;
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
public class LikeTask {
    private final RedisTemplate<String, Object> redisTemplate;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final SolutionMapper solutionMapper;
    private final RedisBucketSwitcher redisBucketSwitcher;
    private final RedissonClient redissonClient;
    private final ThreadPoolTaskExecutor consumerExecutor;

    private final ConcurrentLinkedQueue<BucketTask> postQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BucketTask> commentQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BucketTask> solutionQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean postConsuming = new AtomicBoolean();
    private final AtomicBoolean commentConsuming = new AtomicBoolean();
    private final AtomicBoolean solutionConsuming = new AtomicBoolean();

    public LikeTask(
            RedisTemplate<String, Object> redisTemplate,
            PostMapper postMapper,
            CommentMapper commentMapper,
            SolutionMapper solutionMapper,
            RedisBucketSwitcher redisBucketSwitcher,
            RedissonClient redissonClient,
            @Qualifier("communityBucketConsumerExecutor") ThreadPoolTaskExecutor consumerExecutor) {
        this.redisTemplate = redisTemplate;
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
        this.solutionMapper = solutionMapper;
        this.redisBucketSwitcher = redisBucketSwitcher;
        this.redissonClient = redissonClient;
        this.consumerExecutor = consumerExecutor;
    }

    @PostConstruct
    public void init() {
        redisTemplate.opsForValue().setIfAbsent(RedisContext.LIKE_POST_BUCKET_KEY, 0);
        redisTemplate.opsForValue().setIfAbsent(RedisContext.LIKE_COMMENT_BUCKET_KEY, 0);
        redisTemplate.opsForValue().setIfAbsent(RedisContext.LIKE_SOLUTION_BUCKET_KEY, 0);
        log.info("点赞桶任务初始化完成");
    }

    @Scheduled(cron = "0 */1 * * * *")
    public void updatePostLike() {
        collectBucket("post-like", RedisContext.LIKE_POST_BUCKET_KEY, RedisContext.LIKE_POST_KEY,
                postQueue, postConsuming, postMapper::updateLikeCount);
    }

    @Scheduled(cron = "0 */1 * * * *")
    public void updateCommentLike() {
        collectBucket("comment-like", RedisContext.LIKE_COMMENT_BUCKET_KEY, RedisContext.LIKE_COMMENT_KEY,
                commentQueue, commentConsuming, commentMapper::updateLikeCount);
    }

    @Scheduled(cron = "0 */1 * * * *")
    public void updateSolutionLike() {
        collectBucket("solution-like", RedisContext.LIKE_SOLUTION_BUCKET_KEY, RedisContext.LIKE_SOLUTION_KEY,
                solutionQueue, solutionConsuming, solutionMapper::updateLikeCount);
    }

    private void collectBucket(
            String taskName,
            String bucketSelectorKey,
            String bucketPrefix,
            ConcurrentLinkedQueue<BucketTask> queue,
            AtomicBoolean consuming,
            BucketUpdater updater) {
        RLock lock = redissonClient.getLock("task:community:" + taskName);
        if (!lock.tryLock()) {
            log.debug("点赞桶切换跳过，已有其他实例执行，task={}", taskName);
            return;
        }
        try {
            long bucketId = redisBucketSwitcher.switchBucket(bucketSelectorKey);
            String sourceKey = bucketPrefix + "-" + bucketId;
            String processingKey = sourceKey + ":processing:" + UUID.randomUUID();
            Boolean renamed = redisTemplate.renameIfAbsent(sourceKey, processingKey);
            if (!renamed) {
                log.info("点赞桶为空，task={}, bucketId={}", taskName, bucketId);
                return;
            }
            Map<Object, Object> bucket = redisTemplate.opsForHash().entries(processingKey);
            if (bucket.isEmpty()) {
                redisTemplate.delete(processingKey);
                log.info("点赞桶无增量，task={}, bucketId={}", taskName, bucketId);
                return;
            }
            queue.offer(new BucketTask(bucketId, processingKey, bucket));
            log.info("点赞桶已入队，task={}, bucketId={}, processingKey={}, entries={}, queueSize={}",
                    taskName, bucketId, processingKey, bucket.size(), queue.size());
            startConsumer(taskName, queue, consuming, updater);
        } catch (Exception exception) {
            log.error("点赞桶切换或入队失败，task={}", taskName, exception);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void startConsumer(String taskName, ConcurrentLinkedQueue<BucketTask> queue,
                               AtomicBoolean consuming, BucketUpdater updater) {
        if (!consuming.compareAndSet(false, true)) {
            log.debug("点赞桶消费者已运行，task={}, queueSize={}", taskName, queue.size());
            return;
        }
        log.info("启动点赞桶消费者，task={}, queueSize={}", taskName, queue.size());
        consumerExecutor.execute(() -> consume(taskName, queue, consuming, updater));
    }

    private void consume(String taskName, ConcurrentLinkedQueue<BucketTask> queue,
                         AtomicBoolean consuming, BucketUpdater updater) {
        try {
            BucketTask task;
            while ((task = queue.poll()) != null) {
                try {
                    log.info("开始消费点赞桶，task={}, bucketId={}, entries={}, queueSize={}",
                            taskName, task.bucketId(), task.bucket().size(), queue.size());
                    int updated = updater.update(task.bucket());
                    if (updated <= 0) {
                        throw new IllegalStateException("数据库未更新任何点赞记录");
                    }
                    redisTemplate.delete(task.processingKey());
                    log.info("点赞桶消费成功，task={}, bucketId={}, updated={}, queueSize={}",
                            taskName, task.bucketId(), updated, queue.size());
                } catch (Exception exception) {
                    queue.offer(task);
                    log.error("点赞桶消费失败，已重新入队，task={}, bucketId={}, queueSize={}",
                            taskName, task.bucketId(), queue.size(), exception);
                    break;
                }
            }
        } finally {
            consuming.set(false);
            if (!queue.isEmpty()) {
                startConsumer(taskName, queue, consuming, updater);
            } else {
                log.info("点赞桶消费者完成，task={}, queueSize=0", taskName);
            }
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("点赞桶任务停止，postQueue={}, commentQueue={}, solutionQueue={}",
                postQueue.size(), commentQueue.size(), solutionQueue.size());
    }

    private record BucketTask(long bucketId, String processingKey, Map<Object, Object> bucket) { }

    @FunctionalInterface
    private interface BucketUpdater {
        int update(Map<Object, Object> bucket);
    }
}
