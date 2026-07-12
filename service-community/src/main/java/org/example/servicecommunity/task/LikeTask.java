package org.example.servicecommunity.task;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommunity.config.RedisBucketSwitcher;
import org.example.servicecommunity.mapper.CommentMapper;
import org.example.servicecommunity.mapper.PostMapper;
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
public class LikeTask {
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;
    @Autowired
    private PostMapper postMapper;
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private SolutionMapper  solutionMapper;
    @Autowired
    private RedisBucketSwitcher redisBucketSwitcher;
    @Autowired
    private RedissonClient redissonClient;
    @PostConstruct
    public void init(){
        redisTemplate.opsForValue().setIfAbsent(RedisContext.LIKE_POST_BUCKET_KEY, 0);
        redisTemplate.opsForValue().setIfAbsent(RedisContext.LIKE_COMMENT_BUCKET_KEY, 0);
        redisTemplate.opsForValue().setIfAbsent(RedisContext.LIKE_SOLUTION_BUCKET_KEY,0);
    }
    @Scheduled(cron = "0 */1 * * * *")
    public void updatePostLike(){
        RLock lock = redissonClient.getLock("task:community:post-like");
        if (!lock.tryLock()) {
            return;
        }
        try {
            long bucketId = redisBucketSwitcher.switchBucket(RedisContext.LIKE_POST_BUCKET_KEY);
            Map<Object, Object> postMap=redisTemplate.opsForHash().entries(RedisContext.LIKE_POST_KEY+"-"+bucketId);
            if(postMap.isEmpty()){
                return;
            }
            int r=postMapper.updateLikeCount(postMap);
            if(r==0){
                log.info("帖子点赞数未更新，桶编号：{}，增量：{}",bucketId,postMap);
            }
            redisTemplate.delete(RedisContext.LIKE_POST_KEY+"-"+bucketId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Scheduled(cron = "0 */1 * * * *")
    public void updateCommentLike(){
        RLock lock = redissonClient.getLock("task:community:comment-like");
        if (!lock.tryLock()) {
            return;
        }
        try {
            long bucketId = redisBucketSwitcher.switchBucket(RedisContext.LIKE_COMMENT_BUCKET_KEY);
            Map<Object, Object> commentMap =redisTemplate.opsForHash().entries(RedisContext.LIKE_COMMENT_KEY+"-"+bucketId);
            if(commentMap.isEmpty()){
                return;
            }
            int r=commentMapper.updateLikeCount(commentMap);
            if(r==0){
                log.info("评论点赞数未更新，桶编号：{}，增量：{}",bucketId ,commentMap);
            }
            redisTemplate.delete(RedisContext.LIKE_COMMENT_KEY+"-"+bucketId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
    @Scheduled(cron = "0 */1 * * * *")
    public void updateSolutionLike(){
        RLock lock = redissonClient.getLock("task:community:solution-like");
        if (!lock.tryLock()) {
            return;
        }
        try {
            long bucketId = redisBucketSwitcher.switchBucket(RedisContext.LIKE_SOLUTION_BUCKET_KEY);
            Map<Object, Object> solutionMap =redisTemplate.opsForHash().entries(RedisContext.LIKE_SOLUTION_KEY+"-"+bucketId);
            if(solutionMap.isEmpty()){
                return;
            }
            int r=solutionMapper.updateLikeCount(solutionMap);
            if(r==0){
                log.info("题解点赞数未更新，桶编号：{}，增量：{}",bucketId ,solutionMap);
            }
            redisTemplate.delete(RedisContext.LIKE_SOLUTION_KEY+"-"+bucketId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
