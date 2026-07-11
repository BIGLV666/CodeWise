package org.example.servicecommunity.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicecommunity.entry.LikeRecord;
import org.example.servicecommunity.mapper.LikeRecordMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class LikeRecordService {
    @Autowired
    private LikeRecordMapper likeRecordMapper;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;
    public boolean PostLike(Long postId){
        String key= String.valueOf(postId +"--"+ UserContext.getUserId());
        RLock  lock = redissonClient.getLock(key);
        Number bucketId=(Number) redisTemplate.opsForValue().get(RedisContext.LIKE_POST_BUCKET_KEY);
        try{
            boolean tryLock = lock.tryLock();
            if (!tryLock){
                throw new IllegalArgumentException("操作过于频繁请稍后重试");
            }
            int r = likeRecordMapper.delete(new QueryWrapper<LikeRecord>().eq("post_id",postId).eq("user_id",UserContext.getUserId()).eq("type","POST"));
            if(r==0){
                LikeRecord likeRecord=new LikeRecord();
                likeRecord.setPostId(postId);
                likeRecord.setUserId(UserContext.getUserId());
                likeRecord.setType("POST");
                likeRecordMapper.insert(likeRecord);
                redisTemplate.opsForHash().increment(
                        RedisContext.LIKE_POST_KEY+"-"+bucketId,postId.toString(),1
                );
                redisTemplate.opsForZSet().incrementScore(RedisContext.HOST_POST_KEY,postId.toString(),0.5);
                return true;
            }else {
                redisTemplate.opsForZSet().incrementScore(RedisContext.HOST_POST_KEY,postId.toString(),-0.5);
                redisTemplate.opsForHash().increment(
                        RedisContext.LIKE_POST_KEY+"-"+bucketId,postId.toString(),-1
                );
                return false;
            }

        }catch (Exception e){
            throw new IllegalArgumentException("修改失败");
        }
        finally {
            if(lock.isHeldByCurrentThread()){
            lock.unlock();}
        }
    }

    public boolean CommentLike(Long postId){
        String key= postId + "--" + UserContext.getUserId();
        RLock  lock = redissonClient.getLock(key);
        Number bucketID=(Number) redisTemplate.opsForValue().get(RedisContext.LIKE_COMMENT_BUCKET_KEY);
        try{
            boolean tryLock = lock.tryLock();
            if (!tryLock){
                throw new IllegalArgumentException("操作过于频繁请稍后重试");
            }
            int r = likeRecordMapper.delete(new QueryWrapper<LikeRecord>().eq("post_id",postId).eq("user_id",UserContext.getUserId()).eq("type","COMMENT"));
            if(r==0){
                LikeRecord likeRecord=new LikeRecord();
                likeRecord.setPostId(postId);
                likeRecord.setUserId(UserContext.getUserId());
                likeRecord.setType("COMMENT");
                likeRecordMapper.insert(likeRecord);
                redisTemplate.opsForHash().increment(
                        RedisContext.LIKE_COMMENT_KEY+"-"+bucketID,postId.toString(),1
                );
                return true;
            }else {
                redisTemplate.opsForHash().increment(
                        RedisContext.LIKE_COMMENT_KEY+"-"+bucketID,postId.toString(),-1
                );
                return false;
            }

        }catch (Exception e){
            throw e;
            //throw new IllegalArgumentException("修改失败");
        }
        finally {
            if(lock.isHeldByCurrentThread()){
                lock.unlock();}
        }
    }

}
