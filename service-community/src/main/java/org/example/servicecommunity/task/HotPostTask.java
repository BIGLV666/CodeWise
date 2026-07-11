package org.example.servicecommunity.task;

import lombok.Synchronized;
import org.example.servicecommon.RedisDto.RedisContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Async
public class HotPostTask {
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;
    @Scheduled(cron = "0 */5 * * * *")
    public void DeletePostVo() {
        redisTemplate.delete(RedisContext.POST_VO_KEY);
    }
}
