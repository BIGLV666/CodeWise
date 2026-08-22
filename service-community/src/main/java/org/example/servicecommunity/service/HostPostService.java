package org.example.servicecommunity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommunity.entry.Post;
import org.example.servicecommunity.enums.PostStatus;
import org.example.servicecommunity.mapper.PostMapper;
import org.example.servicecommunity.vo.HomePostVo;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * 热门帖子排行榜服务。
 *
 * <p>排行榜由 Redis ZSet 保存，成员是帖子 ID，分数是根据帖子点赞数、评论数
 * 和发布时间计算出的热度分数；热门帖子对象本身则缓存在 Redis Hash 中。</p>
 *
 * <p>全量重建采用临时 Key 构建模式。重建过程中始终保留线上正式 Key，避免
 * 先删除正式排行榜造成读请求看到空榜。切换前会将正式榜在重建期间产生的
 * 增量合并到临时榜，随后由单个 Lua 脚本完成两个正式 Key 的替换。</p>
 */
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
    private DefaultRedisScript<Long> snapshotRankingScript;
    private DefaultRedisScript<Long> swapRankingScript;

    /**
     * 加载排行榜重建脚本，并在应用启动时初始化一次排行榜。
     *
     * <p>脚本从 classpath 资源加载，避免在业务方法中重复解析脚本内容。
     * 初始化重建失败会向上抛出异常，由 Spring 启动流程处理。</p>
     */
    @PostConstruct
    public void init() {
        snapshotRankingScript = loadScript("lua/snapshot_hot_post_ranking.lua");
        swapRankingScript = loadScript("lua/swap_hot_post_ranking.lua");
        rebuildRanking();
    }

    /**
     * 每五分钟尝试执行一次排行榜重建。
     *
     * <p>使用 Redisson 分布式锁保证同一时刻只有一个实例执行重建。未获得锁时
     * 直接跳过本轮，下一次定时触发会再次尝试；线程中断会恢复中断标记。</p>
     */
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

    /**
     * 在不影响线上读请求的前提下重建热门排行榜。
     *
     * <ol>
     *     <li>先用 Lua 将当前正式 ZSet 复制到唯一的基线 Key，记录重建开始时的分数。</li>
     *     <li>从数据库查询当前可见帖子，并写入唯一的临时 ZSet。</li>
     *     <li>根据临时 ZSet 的前 {@value #HOT_POST_LIMIT} 个帖子构建临时对象 Hash。</li>
     *     <li>切换脚本比较正式榜与基线榜，将重建期间发生的新增、删除和分数变化合并到临时榜。</li>
     *     <li>在同一个 Redis 脚本中将临时 ZSet 和临时 Hash 替换为正式 Key。</li>
     * </ol>
     *
     * <p>临时 Key 使用 UUID 隔离，避免上一次异常重建遗留的数据与本次重建混用。
     * 任一数据库、Redis 或脚本操作失败时，只删除本次临时 Key，正式排行榜保持不变。</p>
     *
     * @throws RuntimeException 重建或原子切换失败时向调用方传播异常
     */
    private void rebuildRanking() {
        String suffix = ":rebuild:" + UUID.randomUUID();
        String temporaryRankingKey = RedisContext.HOST_POST_KEY + suffix;
        String baselineRankingKey = RedisContext.HOST_POST_KEY + suffix + ":baseline";
        String temporaryPostCacheKey = RedisContext.POST_ID_KEY + suffix;
        try {
            Long snapshotSize = redisTemplate.execute(
                    snapshotRankingScript,
                    List.of(RedisContext.HOST_POST_KEY, baselineRankingKey)
            );
            List<Post> allPosts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                    .eq(Post::getStatus, 1));
            log.info("开始重建热门帖子排行榜，posts={}, snapshotEntries={}, temporaryRankingKey={}",
                    allPosts.size(), snapshotSize, temporaryRankingKey);

            for (Post post : allPosts) {
                redisTemplate.opsForZSet().add(
                        temporaryRankingKey,
                        post.getPostId().toString(),
                        hotScore(post)
                );
            }

            Set<Object> ids = redisTemplate.opsForZSet()
                    .reverseRange(temporaryRankingKey, 0, HOT_POST_LIMIT - 1);
            if (ids != null && !ids.isEmpty()) {
                List<Long> postIds = ids.stream().map(id -> Long.valueOf(id.toString())).toList();
                for (Post post : postMapper.selectByIds(postIds)) {
                    redisTemplate.opsForHash().put(
                            temporaryPostCacheKey,
                            post.getPostId().toString(),
                            post
                    );
                }
            }

            Long swapped = redisTemplate.execute(
                    swapRankingScript,
                    List.of(
                            RedisContext.HOST_POST_KEY,
                            baselineRankingKey,
                            temporaryRankingKey,
                            RedisContext.HOST_POST_KEY,
                            temporaryPostCacheKey,
                            RedisContext.POST_ID_KEY
                    )
            );
            if (!Long.valueOf(1L).equals(swapped)) {
                throw new IllegalStateException("热门帖子排行榜原子切换失败");
            }
            log.info("热门帖子排行榜重建完成，posts={}, rankingKey={}",
                    allPosts.size(), RedisContext.HOST_POST_KEY);
        } catch (Exception exception) {
            redisTemplate.delete(temporaryRankingKey);
            redisTemplate.delete(baselineRankingKey);
            redisTemplate.delete(temporaryPostCacheKey);
            log.error("热门帖子排行榜重建失败，保留旧排行榜，temporaryRankingKey={}",
                    temporaryRankingKey, exception);
            throw exception;
        }
    }

    /**
     * 创建一个从 classpath 加载 Lua 源文件的 Redis 脚本对象。
     *
     * @param path classpath 下的脚本路径
     * @return 返回值按 {@link Long} 解析的 Redis 脚本
     */
    private DefaultRedisScript<Long> loadScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        return script;
    }

    /**
     * 计算帖子当前热度分数。
     *
     * <p>点赞和评论分别使用固定权重，发布时间通过平方根衰减。创建时间为空
     * 或未来时间不会产生负年龄；计数为空时按零处理。</p>
     *
     * @param post 待计算热度的帖子
     * @return 帖子热度分数
     */
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
            if (post != null && PostStatus.isVisible(post.getStatus())) {
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
