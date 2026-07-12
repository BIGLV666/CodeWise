package org.example.servicecommunity.task;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommunity.config.RedisBucketSwitcher;
import org.example.servicecommunity.mapper.SolutionMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@Async
public class SolutionLookTask {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private RedisBucketSwitcher redisBucketSwitcher;
    @Autowired
    private SolutionMapper solutionMapper;
    @Autowired
    private RedissonClient redissonClient;

    @PostConstruct
    public void init() {
        redisTemplate.opsForValue().setIfAbsent(RedisContext.SOLUTION_LOOK_BUCKET_KEY, 0);
    }

    /** 每分钟切换浏览量桶，并把已封闭桶中的增量批量写回数据库。 */
    @Scheduled(cron = "0 */1 * * * *")
    public void updateSolutionLookCount() {
        RLock lock = redissonClient.getLock("task:community:solution-look");
        if (!lock.tryLock()) {
            return;
        }
        try {
            long bucketId = redisBucketSwitcher.switchBucket(RedisContext.SOLUTION_LOOK_BUCKET_KEY);
            String lookKey = RedisContext.SOLUTION_LOOK_KEY + "-" + bucketId;
            Map<Object, Object> lookMap = redisTemplate.opsForHash().entries(lookKey);
            if (lookMap.isEmpty()) {
                return;
            }

            int updated = solutionMapper.updateLookCount(lookMap);
            if (updated == 0) {
                log.info("题解浏览量未更新，桶编号：{}，增量：{}", bucketId, lookMap);
            }
            redisTemplate.delete(lookKey);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
