package org.example.servicecommunity.task;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommunity.mapper.CommentMapper;
import org.example.servicecommunity.mapper.PostMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    @PostConstruct
    public void init(){
        redisTemplate.opsForValue().set(RedisContext.LIKE_POST_BUCKET_KEY,0);
        redisTemplate.opsForValue().set(RedisContext.LIKE_COMMENT_BUCKET_KEY,0);
    }
    @Transactional
    @Scheduled(cron = "0 */1 * * * *")
    public void updatePostLike(){
        Number bucketId=(Number) redisTemplate.opsForValue().get(RedisContext.LIKE_POST_BUCKET_KEY);
        Map<Object, Object> postMap=redisTemplate.opsForHash().entries(RedisContext.LIKE_POST_KEY+"-"+bucketId);


            redisTemplate.opsForValue().set(RedisContext.LIKE_POST_BUCKET_KEY,(bucketId.intValue()+1)%2);
        if(postMap.isEmpty()){
            return;
        }
        int r=postMapper.updateLikeCount(postMap);
        if(r==0){
            log.info("更新失败,桶为{},map{}",bucketId,postMap);
        }
        redisTemplate.delete(RedisContext.LIKE_POST_KEY+"-"+bucketId);
    }
    @Transactional
    @Scheduled(cron = "0 */1 * * * *")
    public void updateCommentLike(){
        Number bucketId=(Number) redisTemplate.opsForValue().get(RedisContext.LIKE_COMMENT_BUCKET_KEY);
        Map<Object, Object> Map =redisTemplate.opsForHash().entries(RedisContext.LIKE_COMMENT_KEY+"-"+bucketId);
        redisTemplate.opsForValue().set(RedisContext.LIKE_COMMENT_BUCKET_KEY,(bucketId.intValue()+1)%2);
        if(Map.isEmpty()){
            log.info("无点赞");
            return;
        }
        int r=commentMapper.updateLikeCount(Map);
        if(r==0){
            log.info("更新失败,桶为{},map{}",bucketId ,Map);
        }
        redisTemplate.delete(RedisContext.LIKE_COMMENT_KEY+"-"+bucketId);
    }
}
