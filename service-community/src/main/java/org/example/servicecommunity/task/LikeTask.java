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
    @Scheduled(cron = "0 */5 * * * *")
    public void updatePostLike(){
        Map<Object, Object> postMap=redisTemplate.opsForHash().entries(RedisContext.LIKE_POST_KEY+RedisContext.LIKE_POST_BUCKET_KEY);
        redisTemplate.opsForValue().set(RedisContext.LIKE_POST_BUCKET_KEY,((Integer)redisTemplate.opsForValue().get(RedisContext.LIKE_POST_BUCKET_KEY)+1)%2);
        int r=postMapper.updateLikeCount(postMap);
        if(r< postMap.size()){
            log.info("更新失败,桶为{},map{}",((Integer)redisTemplate.opsForValue().get(RedisContext.LIKE_POST_BUCKET_KEY)-1)%2,postMap);
        }
    }
    @Transactional
    @Scheduled(cron = "0 */5 * * * *")
    public void updateCommentLike(){
        Map<Object, Object> Map =redisTemplate.opsForHash().entries(RedisContext.LIKE_COMMENT_KEY+RedisContext.LIKE_COMMENT_BUCKET_KEY);
        redisTemplate.opsForValue().set(RedisContext.LIKE_COMMENT_BUCKET_KEY,((Integer)redisTemplate.opsForValue().get(RedisContext.LIKE_COMMENT_BUCKET_KEY)+1)%2);
        int r=commentMapper.updateLikeCount(Map);
        if(r< Map.size()){
            log.info("更新失败,桶为{},map{}",((Integer)redisTemplate.opsForValue().get(RedisContext.LIKE_COMMENT_BUCKET_KEY)-1)%2, Map);
        }
    }
}
