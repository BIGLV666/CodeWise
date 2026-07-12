package org.example.servicecommunity.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisBucketSwitcher {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final DefaultRedisScript<Long> switchBucketScript;

    public RedisBucketSwitcher() {
        switchBucketScript = new DefaultRedisScript<>();
        switchBucketScript.setResultType(Long.class);
        switchBucketScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("lua/switch_bucket.lua"))
        );
    }

    /** 原子切换双桶，并返回切换前需要消费的桶编号。 */
    public long switchBucket(String bucketKey) {
        Long bucketId = redisTemplate.execute(switchBucketScript, List.of(bucketKey));
        if (bucketId == null) {
            throw new IllegalStateException("Redis 双桶切换失败");
        }
        return bucketId;
    }
}
