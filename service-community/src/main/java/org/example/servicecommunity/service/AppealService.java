package org.example.servicecommunity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.notification.NotificationAppealDto;
import org.example.serviceapi.dto.notification.NotificationDto;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.until.UserContext;
import org.example.servicecommunity.Dto.AppealDto;
import org.example.servicecommunity.entry.Appeal;
import org.example.servicecommunity.entry.Comment;
import org.example.servicecommunity.entry.Post;
import org.example.servicecommunity.entry.Solution;
import org.example.servicecommunity.enums.PostType;
import org.example.servicecommunity.mapper.AppealMapper;
import org.example.servicecommunity.mapper.CommentMapper;
import org.example.servicecommunity.mapper.PostMapper;
import org.example.servicecommunity.mapper.SolutionMapper;
import org.example.servicecommunity.vo.AppealVo;
import org.example.servicecommunity.vo.CursorPageResult;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AppealService {

    @Autowired
    private AppealMapper appealMapper;
    @Autowired
    private PostMapper postMapper;
    @Autowired
    private SolutionMapper solutionMapper;
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private UserFeignClient userFeignClient;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String APPEAL_HANDLE = "appeal-handle";

    private Object getPost(Long Id, PostType postType) {
        switch (postType) {
            case COMMENT -> {
                return commentMapper.selectById(Id);
            }
            case POST -> {
                return postMapper.selectById(Id);
            }
            case SOLUTION -> {
                return solutionMapper.selectById(Id);
            }
        }
        return null;
    }

    private Integer getstatus(PostType postType, Object post) {
        switch (postType) {
            case COMMENT -> {
                Comment comment = (Comment) post;
                return comment.getStatus();
            }
            case SOLUTION -> {
                Solution solution = (Solution) post;
                return solution.getStatus();
            }
            case POST -> {
                Post post2 = (Post) post;
                return post2.getStatus();
            }
        }
        return null;
    }

    private Long getOwnerId(PostType postType, Object post) {
        switch (postType) {
            case COMMENT -> {
                Comment comment = (Comment) post;
                return comment.getUserId();
            }
            case SOLUTION -> {
                Solution solution = (Solution) post;
                return solution.getSolutionUserId();
            }
            case POST -> {
                Post post2 = (Post) post;
                return post2.getUserId();
            }
        }
        return null;
    }

    private String getTitle(PostType postType, Object post) {
        switch (postType) {
            case COMMENT -> {
                Comment comment = (Comment) post;
                return "评论";
            }
            case SOLUTION -> {
                Solution solution = (Solution) post;
                return solution.getSolutionTitle();
            }
            case POST -> {
                Post post2 = (Post) post;
                return post2.getPostTitle();
            }
        }
        return "";
    }

    public void submitAppeal(AppealDto dto) {
        Appeal appeal ;
        boolean is=true;

        appeal=appealMapper.selectOne(new QueryWrapper<Appeal>().eq("post_id",dto.getPostId()).eq("post_type",dto.getPostType()));
        if(appeal==null){
            is=false;
            appeal= new Appeal(dto);
        }
        if(appeal.getStatus()!=null&&appeal.getStatus()==0){
            throw new IllegalArgumentException("请耐心等待审核不要重复提交");
        }
        if (appeal.getPostType() == null) {
            throw new IllegalArgumentException("不存在的类型");
        }
        Object postBody = getPost(appeal.getPostId(), appeal.getPostType());
        if (postBody == null) {
            throw new IllegalArgumentException("未找到相关内容");
        }
        Integer s = getstatus(appeal.getPostType(), postBody);
        if (s == null) {
            throw new IllegalArgumentException("状态异常");
        }
        if (s != 2 && s != 3) {
            throw new IllegalArgumentException("该状态无需申诉");
        }
        appeal.setReason(dto.getReason());
        Long ownerId = getOwnerId(appeal.getPostType(), postBody);
        Long currentUserId = UserContext.getUserId();
        if (ownerId == null || !ownerId.equals(currentUserId)) {
            throw new IllegalArgumentException("只能申诉自己的内容");
        }


        appeal.setStatus(0);
        appeal.setUserId(currentUserId);
        try {
            int r;
            if(is){
                r=appealMapper.updateById(appeal);
            }
            else{ r = appealMapper.insert(appeal);}
            if (r != 1) {
                throw new IllegalArgumentException("提交失败");
            }

            // 不再向管理员发送通知，管理员通过后台查询待处理申诉列表

        } catch (Exception e) {
            if (e instanceof DuplicateKeyException) {
                throw new IllegalArgumentException("该请求已存在请勿重复提交");
            } else {
                log.error(e.getMessage(), e);
                throw new IllegalArgumentException("提交失败请稍后重试");
            }
        }
    }

    /**
     * 管理员获取申诉列表
     */
    public CursorPageResult<AppealVo> getAppealList(Long lastId, Integer pageSize, Integer status) {
        LambdaQueryWrapper<Appeal> wrapper = new LambdaQueryWrapper<>();
        if (lastId != null) {
            wrapper.gt(Appeal::getAppealId, lastId);
        }
        if (status != null) {
            wrapper.eq(Appeal::getStatus, status);
        }
        wrapper.orderByAsc(Appeal::getAppealId);
        wrapper.last("LIMIT " + (pageSize + 1));

        List<Appeal> list = appealMapper.selectList(wrapper);
        List<AppealVo> records = new ArrayList<>();

        for (Appeal appeal : list) {
            AppealVo vo = new AppealVo();
            vo.setAppealId(appeal.getAppealId());
            vo.setPostId(appeal.getPostId());
            vo.setPostType(appeal.getPostType());
            vo.setReason(appeal.getReason());
            vo.setTakeDownReason(appeal.getTakeDownReason());
            vo.setStatus(appeal.getStatus());
            vo.setCreateTime(appeal.getCreateTime());
            vo.setUpdateTime(appeal.getUpdateTime());

            // 获取申诉用户信息
            try {
                Result<UserDto> userResult = userFeignClient.getUserInfo(appeal.getUserId());
                if (userResult != null && userResult.getData() != null) {
                    vo.setUser(userResult.getData());
                }
            } catch (Exception e) {
                log.error("获取用户信息失败: userId={}", appeal.getUserId(), e);
            }

            // 获取内容标题
            Object post = getPost(appeal.getPostId(), appeal.getPostType());
            if (post != null) {
                vo.setTitle(getTitle(appeal.getPostType(), post));
            }

            records.add(vo);
        }

        Long nextCursor = null;
        Boolean hasNext = false;
        if (!list.isEmpty() && list.size() > pageSize) {
            hasNext = true;
            records.remove(records.size() - 1);
            nextCursor = records.get(records.size() - 1).getAppealId();
        }

        return new CursorPageResult<>(records, nextCursor, hasNext, (long) records.size());
    }

    /**
     * 管理员处理申诉
     * @param appealId 申诉ID
     * @param pass true=恢复内容, false=拒绝申诉
     * @param adminReason 管理员回复
     */
    public void handleAppeal(Long appealId, boolean pass, String adminReason) {
        Appeal appeal = appealMapper.selectById(appealId);
        if (appeal == null) {
            throw new IllegalArgumentException("申诉不存在");
        }
        if (appeal.getStatus() != 0) {
            throw new IllegalArgumentException("该申诉已处理");
        }

        Long adminUserId = UserContext.getUserId();

        if (pass) {
            // 恢复内容
            Object post = getPost(appeal.getPostId(), appeal.getPostType());
            if (post == null) {
                throw new IllegalArgumentException("内容不存在");
            }

            switch (appeal.getPostType()) {
                case POST -> {
                    Post p = (Post) post;
                    p.setStatus(1);
                    postMapper.updateById(p);
                }
                case COMMENT -> {
                    Comment c = (Comment) post;
                    c.setStatus(1);
                    commentMapper.updateById(c);
                }
                case SOLUTION -> {
                    Solution s = (Solution) post;
                    s.setStatus(1);
                    solutionMapper.updateById(s);
                }
            }

            appeal.setStatus(2); // 已恢复
            appeal.setAdminUserId(adminUserId);
            appealMapper.updateById(appeal);

            // 发送恢复通知给用户
            sendAppealHandleNotification(appeal, post, true, adminReason);

        } else {
            // 拒绝申诉
            appeal.setStatus(1); // 已拒绝
            appeal.setAdminUserId(adminUserId);
            appeal.setTakeDownReason(adminReason);
            try {
                int r = appealMapper.updateById(appeal);
            } catch (Exception e){
                if (e instanceof DuplicateKeyException){}
                else {
                    throw new IllegalArgumentException("修改失败");
                }
            }
            Object post = getPost(appeal.getPostId(), appeal.getPostType());
            // 发送拒绝通知给用户
            sendAppealHandleNotification(appeal, post, false, adminReason);
        }
    }

    /**
     * 管理员处理申诉后，发送通知给用户
     */
    private void sendAppealHandleNotification(Appeal appeal, Object post, boolean passed, String adminReason) {
        CompletableFuture.runAsync(() -> {
            try {
                String title = getTitle(appeal.getPostType(), post);
                String contentType = appeal.getPostType().name();

                NotificationAppealDto appealDto = new NotificationAppealDto();
                appealDto.setAppealId(appeal.getAppealId());
                appealDto.setPostId(appeal.getPostId());
                appealDto.setPostType(contentType);
                appealDto.setTitle(title);
                appealDto.setReason(appeal.getReason());
                appealDto.setAdminReason(adminReason);
                appealDto.setPassed(passed);

                NotificationDto notification = new NotificationDto();
                notification.setType(NotificationCenterType.APPEAL);
                notification.setUserId(appeal.getUserId());
                notification.setExtraData(objectMapper.writeValueAsString(appealDto));

                String messageId = APPEAL_HANDLE + ":" + appeal.getAppealId() + ":" + (passed ? "pass" : "reject");
                notification.setMessageId(messageId);

                String redisKey = RedisContext.NOTIFICATION_IDEMPOTENT_KEY + messageId;
                Boolean set = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", 7, TimeUnit.DAYS);
                if (Boolean.TRUE.equals(set)) {
                    rabbitTemplate.convertAndSend(
                            MqContexts.NOTIFICATION_EXCHANGE,
                            MqContexts.NOTIFICATION_APPEAL_ROUTING_KEY,
                            notification
                    );
                    log.info("申诉处理通知已发送: appealId={}, passed={}", appeal.getAppealId(), passed);
                }
            } catch (Exception e) {
                log.error("发送申诉处理通知失败: appealId={}", appeal.getAppealId(), e);
            }
        });
    }

}
