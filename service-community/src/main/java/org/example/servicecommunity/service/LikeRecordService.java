package org.example.servicecommunity.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.UserDto;
import org.example.serviceapi.enums.BusinessType;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.serviceapi.mqMessages.NotificationDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.until.UserContext;

import org.example.servicecommunity.entry.LikeRecord;

import org.example.servicecommunity.enums.PostType;
import org.example.servicecommunity.mapper.CommentMapper;
import org.example.servicecommunity.mapper.LikeRecordMapper;
import org.example.servicecommunity.mapper.PostMapper;

import org.example.servicecommunity.mapper.SolutionMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class LikeRecordService {
    @Autowired
    private LikeRecordMapper likeRecordMapper;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;
    @Autowired
    private UserFeignClient  userFeignClient;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private PostMapper postMapper;
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private SolutionMapper solutionMapper;
    @Autowired
    private ObjectMapper objectMapper;
    private final String LIKE="like";
    public boolean PostLike(Long postId){
        String key= "post-like--" + postId + "--" + UserContext.getUserId();
        RLock  lock = redissonClient.getLock(key);
        Number bucketId=(Number) redisTemplate.opsForValue().get(RedisContext.LIKE_POST_BUCKET_KEY);
        try{
            boolean tryLock = lock.tryLock(2, 10, TimeUnit.SECONDS);
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


                CompletableFuture.runAsync(() -> {
                        log.info("点赞通知异步任务开始，type=POST，targetId={}", postId);
                        String messageId = LIKE + ":" + BusinessType.POST + ":" + likeRecord.getUserId() + ":" + postId;
                        Long postUserId=getPostUserID(postId,BusinessType.POST);
                        if (likeRecord.getUserId().equals(postUserId)) {
                            return;
                        }
                        Result<UserDto> userDtoResult = userFeignClient.getUserInfo(likeRecord.getUserId());
                        NotificationDto notificationDto = new NotificationDto();
                        notificationDto.setUserId(postUserId);
                        notificationDto.setMessageId(messageId);
                        notificationDto.setType(NotificationCenterType.LIKE);
                        notificationDto.setBusinessType(BusinessType.POST);
                        notificationDto.setBusinessId(postId);
                        try {
                            notificationDto.setExtraData(objectMapper.writeValueAsString(userDtoResult.getData()));
                        } catch (JsonProcessingException e) {
                            log.error("点赞通知用户数据序列化失败，type=POST，targetId={}", postId, e);
                            return;
                        }
                        rabbitTemplate.convertAndSend(
                                MqContexts.NOTIFICATION_EXCHANGE,
                                MqContexts.NOTIFICATION_LIKE_ROUTING_KEY,
                                notificationDto
                        );
                    }).exceptionally(exception -> {
                        log.error("点赞通知异步任务失败，type=POST，targetId={}", postId, exception);
                        return null;
                    });



                return true;
            }else {
                redisTemplate.opsForZSet().incrementScore(RedisContext.HOST_POST_KEY,postId.toString(),-0.5);
                redisTemplate.opsForHash().increment(
                        RedisContext.LIKE_POST_KEY+"-"+bucketId,postId.toString(),-1
                );
                return false;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("点赞操作被中断", e);
        } catch (Exception e){
            throw new IllegalArgumentException("修改失败");
        }
        finally {
            if(lock.isHeldByCurrentThread()){
            lock.unlock();}
        }
    }

    public boolean CommentLike(Long postId){
        String key= "comment-like--" + postId + "--" + UserContext.getUserId();
        RLock  lock = redissonClient.getLock(key);
        Number bucketID=(Number) redisTemplate.opsForValue().get(RedisContext.LIKE_COMMENT_BUCKET_KEY);
        try{
            boolean tryLock = lock.tryLock(2, 10, TimeUnit.SECONDS);
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
                CompletableFuture.runAsync(() -> {
                    log.info("点赞通知异步任务开始，type=COMMENT，targetId={}", postId);
                    String messageId=LIKE+":"+BusinessType.COMMENT+":"+likeRecord.getUserId()+":"+postId;
                    Long commentUserId=getPostUserID(postId,BusinessType.COMMENT);
                    if (likeRecord.getUserId().equals(commentUserId)) {
                        return;
                    }
                    Result<UserDto>userDtoResult=userFeignClient.getUserInfo(likeRecord.getUserId());
                    NotificationDto notificationDto=new NotificationDto();
                    notificationDto.setUserId(commentUserId);
                    notificationDto.setMessageId(messageId);
                    notificationDto.setType(NotificationCenterType.LIKE);
                    notificationDto.setBusinessType(BusinessType.COMMENT);
                    notificationDto.setBusinessId(postId);
                    try {
                        notificationDto.setExtraData(objectMapper.writeValueAsString(userDtoResult.getData()));
                    } catch (JsonProcessingException e) {
                       log.error("点赞通知用户数据序列化失败，type=COMMENT，targetId={}", postId, e);
                       return;
                    }
                    rabbitTemplate.convertAndSend(
                            MqContexts.NOTIFICATION_EXCHANGE,
                            MqContexts.NOTIFICATION_LIKE_ROUTING_KEY,
                            notificationDto
                    );
                }).exceptionally(exception -> {
                    log.error("点赞通知异步任务失败，type=COMMENT，targetId={}", postId, exception);
                    return null;
                });
                return true;
            }else {
                redisTemplate.opsForHash().increment(
                        RedisContext.LIKE_COMMENT_KEY+"-"+bucketID,postId.toString(),-1
                );
                return false;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("点赞操作被中断", e);
        } catch (Exception e){
            throw e;
            //throw new IllegalArgumentException("修改失败");
        }
        finally {
            if(lock.isHeldByCurrentThread()){
                lock.unlock();}
        }
    }


    /**
     *  为题解点赞
     *
     */
    public boolean SolutionLike(Long postId){
        String key= "solution-like--" + postId + "--" + UserContext.getUserId();
        RLock lock = redissonClient.getLock(key);
        Number bucketID=(Number) redisTemplate.opsForValue().get(RedisContext.LIKE_SOLUTION_BUCKET_KEY);
        try {
            boolean tryLock = lock.tryLock(2, 10, TimeUnit.SECONDS);
            if (!tryLock){
                throw new IllegalArgumentException("操作过于频繁，请稍后重试");
            }
            int r = likeRecordMapper.delete(new QueryWrapper<LikeRecord>().eq("post_id",postId).eq("user_id",UserContext.getUserId()).eq("type", PostType.SOLUTION));
                if(r==0){
                    LikeRecord likeRecord=new LikeRecord();
                    likeRecord.setPostId(postId);
                    likeRecord.setUserId(UserContext.getUserId());
                    likeRecord.setType(PostType.SOLUTION.getType());
                    likeRecordMapper.insert(likeRecord);
                    redisTemplate.opsForHash().increment(
                            RedisContext.LIKE_SOLUTION_KEY+"-"+bucketID,postId.toString(),1
                    );


                    CompletableFuture.runAsync(() -> {
                        log.info("点赞通知异步任务开始，线程={}，targetId={}",
                                Thread.currentThread().getName(), postId);
                        String messageId=LIKE+":"+BusinessType.SOLUTION+":"+likeRecord.getUserId()+":"+postId;
                        Long commentUserId=getPostUserID(postId,BusinessType.SOLUTION);
                        if (likeRecord.getUserId().equals(commentUserId)) {
                            return;
                        }
                        Result<UserDto>userDtoResult=userFeignClient.getUserInfo(likeRecord.getUserId());
                        NotificationDto notificationDto=new NotificationDto();
                        notificationDto.setUserId(commentUserId);
                        notificationDto.setMessageId(messageId);
                        notificationDto.setType(NotificationCenterType.LIKE);
                        notificationDto.setBusinessType(BusinessType.SOLUTION);
                        notificationDto.setBusinessId(postId);
                        try {
                            notificationDto.setExtraData(objectMapper.writeValueAsString(userDtoResult.getData()));
                        } catch (JsonProcessingException e) {
                            log.error("点赞通知用户数据序列化失败，targetId={}", postId, e);
                            return;
                        }
                        log.info("消息发送{}",notificationDto);
                        rabbitTemplate.convertAndSend(
                                MqContexts.NOTIFICATION_EXCHANGE,
                                MqContexts.NOTIFICATION_LIKE_ROUTING_KEY,
                                notificationDto
                        );
                    }).exceptionally(exception -> {
                        log.error("点赞通知异步任务失败，type=SOLUTION，targetId={}", postId, exception);
                        return null;
                    });

                    return true;
                }else {
                    redisTemplate.opsForHash().increment(
                            RedisContext.LIKE_SOLUTION_KEY+"-"+bucketID,postId.toString(),-1
                    );
                    return false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("点赞操作被中断", e);
            } catch (Exception e){
            throw e;
        }finally {
            if(lock.isHeldByCurrentThread()){
                lock.unlock();
            }
        }
    }




    private Long getPostUserID(Long postId,BusinessType businessType){
        String ownerCacheKey = getOwnerCacheKey(businessType);
        Long ownerId = (Long) redisTemplate.opsForHash().get(ownerCacheKey, postId.toString());
        if (ownerId != null) {
            return ownerId;
        }

        RLock lock = redissonClient.getLock(
                "lock:community:owner:" + businessType + ":" + postId
        );

        boolean locked = false;
        try {
            locked = lock.tryLock(1, 5, TimeUnit.SECONDS);

            if (locked) {
                // 双重检查，可能前一个线程已经写入缓存
                switch (businessType) {
                    case POST-> ownerId = (Long) redisTemplate.opsForHash().get(RedisContext.POST_AND_USER_ID_KEY, postId.toString());
                    case COMMENT->ownerId = (Long) redisTemplate.opsForHash().get(RedisContext.COMMENT_AND_USER_ID, postId.toString());
                    case SOLUTION -> ownerId = (Long) redisTemplate.opsForHash().get(RedisContext.SOLUTION_AND_USER_ID, postId.toString());
                }

                if (ownerId != null) {
                    return ownerId;
                }
                if(BusinessType.POST.equals(businessType)){
                    ownerId =postMapper.selectById(postId).getUserId();}
                if(BusinessType.COMMENT.equals(businessType)){
                    ownerId =commentMapper.selectById(postId).getUserId();
                }
                if(BusinessType.SOLUTION.equals(businessType)){
                    ownerId=solutionMapper.selectById(postId).getSolutionUserId();
                }

                switch (businessType) {
                    case POST->  redisTemplate.opsForHash().put(RedisContext.POST_AND_USER_ID_KEY,postId.toString(),ownerId);
                    case COMMENT->redisTemplate.opsForHash().put(RedisContext.COMMENT_AND_USER_ID,postId.toString(),ownerId);
                    case SOLUTION ->redisTemplate.opsForHash().put(RedisContext.SOLUTION_AND_USER_ID,postId.toString(),ownerId);
                }
                return ownerId;
            }

            // 没抢到说明其他线程大概率正在重建，短暂等待后再读
            for (int i = 0; i < 5; i++) {
                Thread.sleep(50);
                switch (businessType) {
                    case POST-> ownerId = (Long) redisTemplate.opsForHash().get(RedisContext.POST_AND_USER_ID_KEY, postId.toString());
                    case COMMENT->ownerId = (Long) redisTemplate.opsForHash().get(RedisContext.COMMENT_AND_USER_ID, postId.toString());
                    case SOLUTION -> ownerId = (Long) redisTemplate.opsForHash().get(RedisContext.SOLUTION_AND_USER_ID, postId.toString());
                }
                if (ownerId != null) {
                    return ownerId;
                }
            }

            // 最终兜底查数据库，不能直接退出，否则通知会丢
            if(BusinessType.POST.equals(businessType)){
                ownerId =postMapper.selectById(postId).getUserId();}
            if(BusinessType.COMMENT.equals(businessType)){
                ownerId =commentMapper.selectById(postId).getUserId();
            }
            if(BusinessType.SOLUTION.equals(businessType)){
                ownerId=solutionMapper.selectById(postId).getSolutionUserId();
            }
            return ownerId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取内容作者被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String getOwnerCacheKey(BusinessType businessType) {
        return switch (businessType) {
            case POST -> RedisContext.POST_AND_USER_ID_KEY;
            case COMMENT -> RedisContext.COMMENT_AND_USER_ID;
            case SOLUTION -> RedisContext.SOLUTION_AND_USER_ID;
            default -> throw new IllegalArgumentException("不支持的点赞业务类型：" + businessType);
        };
    }
}
