package org.example.servicecommunity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommunity.entry.Post;
import org.example.servicecommunity.mapper.PostMapper;
import org.example.servicecommunity.vo.HomePostVo;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class HostPostService {
    private static final int HOT_POST_LIMIT = 10;

    @Autowired
    private PostMapper postMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private PostService postService;

    @PostConstruct
    public void init() {
        rebuildRanking();
    }

    @Scheduled(cron = "0 */5 * * * *")
    public void posts() {
        RLock lock = redissonClient.getLock(RedisContext.HOST_POST_KEY + ":rebuild-lock");
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 30, TimeUnit.SECONDS);
            if (locked) {
                rebuildRanking();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("hot post ranking rebuild interrupted", e);
        } catch (Exception e) {
            log.error("hot post ranking rebuild failed", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void rebuildRanking() {
        List<Post> allPosts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, 1));

        redisTemplate.delete(RedisContext.HOST_POST_KEY);
        redisTemplate.delete(RedisContext.POST_ID_KEY);
        for (Post post : allPosts) {
            redisTemplate.opsForZSet().add(
                    RedisContext.HOST_POST_KEY,
                    post.getPostId().toString(),
                    hotScore(post)
            );
        }

        Set<Object> ids = redisTemplate.opsForZSet()
                .reverseRange(RedisContext.HOST_POST_KEY, 0, HOT_POST_LIMIT - 1);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Long> postIds = ids.stream().map(id -> Long.valueOf(id.toString())).toList();
        for (Post post : postMapper.selectByIds(postIds)) {
            redisTemplate.opsForHash().put(RedisContext.POST_ID_KEY, post.getPostId().toString(), post);
        }
    }

    private double hotScore(Post post) {
        long likeCount = post.getLikeCount() == null ? 0L : post.getLikeCount();
        long commentCount = post.getCommentCount() == null ? 0L : post.getCommentCount();
        long hours = Math.max(0L, Duration.between(post.getCreateTime(), LocalDateTime.now()).toHours());
        return ((likeCount * 0.5) + (commentCount * 2.0)) / Math.sqrt(hours + 2.0);
    }

    public List<HomePostVo> getHostHomePost() {
        Set<Object> ids = getHotPostIds();
        if (ids == null || ids.isEmpty()) {
            RLock lock = redissonClient.getLock(RedisContext.HOST_POST_KEY + ":rebuild-lock");
            boolean locked = false;
            try {
                locked = lock.tryLock(2, 30, TimeUnit.SECONDS);
                if (locked && Boolean.FALSE.equals(redisTemplate.hasKey(RedisContext.HOST_POST_KEY))) {
                    rebuildRanking();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("hot post ranking initialization interrupted", e);
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
            ids = getHotPostIds();
        }
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> rankedIds = ids.stream().map(id -> Long.valueOf(id.toString())).toList();
        Map<Long, Post> postMap = new HashMap<>();
        List<Long> missingIds = new ArrayList<>();
        for (Long id : rankedIds) {
            Post post = (Post) redisTemplate.opsForHash().get(RedisContext.POST_ID_KEY, id.toString());
            if (post == null) {
                missingIds.add(id);
            } else {
                postMap.put(id, post);
            }
        }
        if (!missingIds.isEmpty()) {
            for (Post post : postMapper.selectByIds(missingIds)) {
                postMap.put(post.getPostId(), post);
                redisTemplate.opsForHash().put(RedisContext.POST_ID_KEY, post.getPostId().toString(), post);
            }
        }

        List<HomePostVo> result = new ArrayList<>();
        for (Long id : rankedIds) {
            Post post = postMap.get(id);
            if (post != null && Integer.valueOf(1).equals(post.getStatus())) {
                result.add(new HomePostVo(post));
            }
        }
        postService.fillPostDetails(result);
        return result;
    }

    private Set<Object> getHotPostIds() {
        return redisTemplate.opsForZSet()
                .reverseRange(RedisContext.HOST_POST_KEY, 0, HOT_POST_LIMIT - 1);
    }
}
