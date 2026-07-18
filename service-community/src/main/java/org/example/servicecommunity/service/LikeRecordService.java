package org.example.servicecommunity.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.notification.NotificationLikeDto;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.enums.BusinessType;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.serviceapi.dto.notification.NotificationDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.until.UserContext;
import org.example.servicecommunity.Dto.LikeTargetMetaDto;
import org.example.servicecommunity.entry.Comment;
import org.example.servicecommunity.entry.LikeRecord;
import org.example.servicecommunity.entry.Post;
import org.example.servicecommunity.entry.Solution;
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
                        LikeTargetMetaDto targetMeta = getLikeTargetMeta(postId, BusinessType.POST);
                        if (likeRecord.getUserId().equals(targetMeta.getOwnerUserId())) {
                            return;
                        }
                        Result<UserDto> userDtoResult = userFeignClient.getUserInfo(likeRecord.getUserId());
                        NotificationDto notificationDto = new NotificationDto();
                        notificationDto.setUserId(targetMeta.getOwnerUserId());
                        notificationDto.setMessageId(messageId);
                        notificationDto.setType(NotificationCenterType.LIKE);
                        notificationDto.setBusinessType(BusinessType.POST);
                        notificationDto.setBusinessId(postId);
                        try {
                            NotificationLikeDto notificationLikeDto = buildNotificationLikeDto(
                                    userDtoResult.getData(), targetMeta
                            );
                            notificationDto.setExtraData(objectMapper.writeValueAsString(notificationLikeDto));
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
                    LikeTargetMetaDto targetMeta = getLikeTargetMeta(postId, BusinessType.COMMENT);
                    if (likeRecord.getUserId().equals(targetMeta.getOwnerUserId())) {
                        return;
                    }
                    Result<UserDto>userDtoResult=userFeignClient.getUserInfo(likeRecord.getUserId());
                    NotificationDto notificationDto=new NotificationDto();
                    notificationDto.setUserId(targetMeta.getOwnerUserId());
                    notificationDto.setMessageId(messageId);
                    notificationDto.setType(NotificationCenterType.LIKE);
                    notificationDto.setBusinessType(BusinessType.COMMENT);
                    notificationDto.setBusinessId(postId);
                    try {
                        NotificationLikeDto notificationLikeDto = buildNotificationLikeDto(
                                userDtoResult.getData(), targetMeta
                        );
                        notificationDto.setExtraData(objectMapper.writeValueAsString(notificationLikeDto));
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
                        LikeTargetMetaDto targetMeta = getLikeTargetMeta(postId, BusinessType.SOLUTION);
                        if (likeRecord.getUserId().equals(targetMeta.getOwnerUserId())) {
                            return;
                        }
                        Result<UserDto>userDtoResult=userFeignClient.getUserInfo(likeRecord.getUserId());
                        NotificationDto notificationDto=new NotificationDto();
                        notificationDto.setUserId(targetMeta.getOwnerUserId());
                        notificationDto.setMessageId(messageId);
                        notificationDto.setType(NotificationCenterType.LIKE);
                        notificationDto.setBusinessType(BusinessType.SOLUTION);
                        notificationDto.setBusinessId(postId);
                        try {
                            NotificationLikeDto notificationLikeDto = buildNotificationLikeDto(
                                    userDtoResult.getData(), targetMeta
                            );
                            notificationDto.setExtraData(objectMapper.writeValueAsString(notificationLikeDto));
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



    private LikeTargetMetaDto getLikeTargetMeta(Long targetId, BusinessType businessType) {
        String ownerCacheKey = getOwnerCacheKey(businessType);
        LikeTargetMetaDto targetMeta = (LikeTargetMetaDto) redisTemplate.opsForHash()
                .get(ownerCacheKey, targetId.toString());
        if (targetMeta != null) {
            return targetMeta;
        }

        RLock lock = redissonClient.getLock(
                "lock:community:owner:" + businessType + ":" + targetId
        );
        boolean locked = false;
        try {
            locked = lock.tryLock(1, 5, TimeUnit.SECONDS);
            if (locked) {
                // 双重检查，避免多个实例同时回源数据库。
                targetMeta = (LikeTargetMetaDto) redisTemplate.opsForHash()
                        .get(ownerCacheKey, targetId.toString());
                if (targetMeta != null) {
                    return targetMeta;
                }

                targetMeta = loadLikeTargetMeta(targetId, businessType);
                redisTemplate.opsForHash().put(ownerCacheKey, targetId.toString(), targetMeta);
                return targetMeta;
            }

            // 其他实例正在重建缓存时短暂等待；超时后仍会查库兜底，不丢通知。
            for (int i = 0; i < 5; i++) {
                Thread.sleep(50);
                targetMeta = (LikeTargetMetaDto) redisTemplate.opsForHash()
                        .get(ownerCacheKey, targetId.toString());
                if (targetMeta != null) {
                    return targetMeta;
                }
            }
            return loadLikeTargetMeta(targetId, businessType);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取点赞目标信息被中断", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private LikeTargetMetaDto loadLikeTargetMeta(Long targetId, BusinessType businessType) {
        return switch (businessType) {
            case POST -> {
                Post post = postMapper.selectById(targetId);
                if (post == null) {
                    throw new IllegalArgumentException("帖子不存在");
                }
                yield new LikeTargetMetaDto(post.getUserId(), post.getPostId(), PostType.POST, null, null);
            }
            case COMMENT -> {
                Comment comment = commentMapper.selectById(targetId);
                if (comment == null) {
                    throw new IllegalArgumentException("评论不存在");
                }
                PostType rootType = comment.getType() == null ? PostType.POST : comment.getType();
                Long rootCommentId = comment.getRootCommentId();
                if (rootCommentId == null || rootCommentId <= 0) {
                    rootCommentId = comment.getCommentId();
                }
                Long questionId = null;
                if (rootType == PostType.SOLUTION) {
                    Solution solution = solutionMapper.selectById(comment.getPostId());
                    if (solution == null) {
                        throw new IllegalArgumentException("评论所属题解不存在");
                    }
                    questionId = solution.getQuestionId();
                }
                yield new LikeTargetMetaDto(
                        comment.getUserId(), comment.getPostId(), rootType, rootCommentId, questionId
                );
            }
            case SOLUTION -> {
                Solution solution = solutionMapper.selectById(targetId);
                if (solution == null) {
                    throw new IllegalArgumentException("题解不存在");
                }
                yield new LikeTargetMetaDto(
                        solution.getSolutionUserId(), solution.getSolutionId(), PostType.SOLUTION,
                        null, solution.getQuestionId()
                );
            }
            default -> throw new IllegalArgumentException("不支持的点赞业务类型：" + businessType);
        };
    }

    private NotificationLikeDto buildNotificationLikeDto(UserDto actor, LikeTargetMetaDto targetMeta) {
        if (actor == null) {
            throw new IllegalArgumentException("未找到点赞用户信息");
        }
        NotificationLikeDto dto = new NotificationLikeDto();
        dto.setUserId(actor.getUserId());
        dto.setUserName(actor.getUserName());
        dto.setNickName(actor.getNickName());
        dto.setAvatarUrl(actor.getAvatarUrl());
        dto.setRootId(targetMeta.getRootId().toString());
        dto.setRootType(targetMeta.getRootType().name());
        dto.setRootCommentId(targetMeta.getRootCommentId() == null
                ? null : targetMeta.getRootCommentId().toString());
        dto.setQuestionId(targetMeta.getQuestionId() == null
                ? null : targetMeta.getQuestionId().toString());
        return dto;
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
