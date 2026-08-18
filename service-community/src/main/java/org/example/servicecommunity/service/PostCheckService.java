package org.example.servicecommunity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.notification.NotificationCheckedDto;
import org.example.serviceapi.dto.notification.NotificationDto;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.enums.BusinessType;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.aop.RequireAdmin;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqContexts;

import org.example.servicecommon.until.UserContext;
import org.example.servicecommunity.entry.*;
import org.example.servicecommunity.enums.PostType;
import org.example.servicecommunity.mapper.CommentMapper;
import org.example.servicecommunity.mapper.PostMapper;
import org.example.servicecommunity.mapper.SolutionMapper;
import org.example.servicecommunity.mapper.TagsMapper;
import org.example.servicecommunity.mapper.TakeDownPostMapper;
import org.example.servicecommunity.vo.CheckDetailVo;
import org.example.servicecommunity.vo.CommentVo;
import org.example.servicecommunity.vo.CursorPageResult;
import org.example.servicecommunity.vo.HomePostVo;
import org.example.servicecommunity.vo.HomeSolutionVo;
import org.example.servicecommunity.vo.TakeDownPostVo;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
@RequireAdmin
public class PostCheckService {
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private PostMapper postMapper;
    @Autowired
    private TagsMapper tagsMapper;
    @Autowired
    private UserFeignClient userFeignClient;
    @Autowired
    private SolutionMapper solutionMapper;
    @Autowired
    private TakeDownPostMapper takeDownPostMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final int PENDING=0;
    private static final int NORMAL=1;
    private static final int TACK_DOWN=2;

    /** 通知 messageId 前缀，配合内容类型、目标 ID 与动作保证消费幂等。 */
    private static final String CONTENT_CHECK = "content-check";

    /** 审核台的四种动作，决定通知文案与幂等键，避免下架后恢复的通知被误判为重复。 */
    private enum CheckAction {
        PASS("pass"),
        REJECT("reject"),
        TAKE_DOWN("down"),
        RESTORE("restore");

        private final String code;

        CheckAction(String code) {
            this.code = code;
        }
    }


    public CursorPageResult<HomePostVo> cursorPost(Long lastId, Integer pageSize) {


        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        if (lastId != null) {
            wrapper.gt(Post::getPostId, lastId);
        }
        wrapper.eq(Post::getStatus, 0);
        wrapper.orderByAsc(Post::getPostId);
        wrapper.last("LIMIT " + (pageSize + 1));

        List<Post> list = postMapper.selectList(wrapper);
        List<HomePostVo> returnPostVoList = new ArrayList<>();
        for (Post post : list) {
            returnPostVoList.add(new HomePostVo(post));
        }

        List<HomePostVo> records;
        Long nextCursor = null;
        Boolean hasNext = false;
        if (list != null && !list.isEmpty()) {
            if (list.size() > pageSize) {
                records = returnPostVoList.subList(0, pageSize);
                HomePostVo lastRecord = records.getLast();
                nextCursor = lastRecord.getPostId();
                hasNext = true;
            } else {
                records = returnPostVoList;
            }
        } else {
            records = new ArrayList<>();
        }

        List<Long> postIds = new ArrayList<>();
        for (HomePostVo postVo : returnPostVoList) {
            postIds.add(postVo.getPostId());
        }
        Map<Long, List<String>> tagsMap = new HashMap<>();
        List<Tags> postTags = postIds.isEmpty() ? Collections.emptyList() : tagsMapper.batchSelectForPostId(postIds);
        for (Tags tag : postTags) {
            tagsMap.computeIfAbsent(tag.getPostId(), key -> new ArrayList<>()).add(tag.getTagName());
        }

        List<Long> userIds = new ArrayList<>();
        for (HomePostVo postVo : returnPostVoList) {
            userIds.add(Long.parseLong(postVo.getUserId()));
            postVo.setTags(tagsMap.getOrDefault(postVo.getPostId(), Collections.emptyList()));
        }
        Result<Map<Long, UserDto>> users = userFeignClient.getUserList(userIds);
        for (HomePostVo postVo : returnPostVoList) {
            UserDto user = users.getData() == null ? null : users.getData().get((Long.parseLong(postVo.getUserId())));
            postVo.setUserName(user == null ? null : user.getNickName());
            postVo.setAvatar(user == null ? null : user.getAvatarUrl());
        }

        return CursorPageResult.<HomePostVo>builder()
                .records(records)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }

    public void checkPost(Long postId, Integer status, String reason) {
        if(status!=2&&status!=1){
            throw new IllegalArgumentException("状态不合法");
        }

        Post post = postMapper.selectById(postId);
        if(post==null){
            throw new IllegalArgumentException("帖子不存在");
        }
        if(post.getStatus()!=0){
            throw new IllegalArgumentException("非待审核状态");
        }
        post.setStatus(status);
        int r= postMapper.updateById(post);
        if(r==0){
            throw new IllegalArgumentException("更新帖子状态失败");
        }
        if (status == NORMAL) {
            redisTemplate.opsForZSet().add(RedisContext.HOST_POST_KEY, postId.toString(), 0.0);
        } else {
            redisTemplate.opsForZSet().remove(RedisContext.HOST_POST_KEY, postId.toString());
            saveTakeDownReason(PostType.POST, postId, null, null, reason);
        }
        sendMessageToUser(post.getUserId(), status == NORMAL ? CheckAction.PASS : CheckAction.REJECT, PostType.POST,
                postId, post.getPostTitle(), PostType.POST, postId, null, null);
    }
    //下架
    public void TackDownPost(Long postId, Integer status, String reason) {
        if(status!=NORMAL&&status!=TACK_DOWN){
            throw new IllegalArgumentException("状态不合法");
        }
        Post post = postMapper.selectById(postId);
        if(post==null){
            throw new IllegalArgumentException("帖子不存在");
        }
        if(post.getStatus()!=1&&post.getStatus()!=TACK_DOWN){
            throw new IllegalArgumentException("非正常状态");
        }
        post.setStatus(status);
        int r= postMapper.updateById(post);
        if(r==0){
            throw new IllegalArgumentException("更新帖子状态失败");
        }
        
        // 下架时记录到下架表
        if (status == TACK_DOWN) {
            TakeDownPost takeDownPost = new TakeDownPost();
            takeDownPost.setRootType(PostType.POST);
            takeDownPost.setRootId(postId);
            takeDownPost.setRootCommentId(null);
            takeDownPost.setQuestionId(null);
            takeDownPost.setReason(reason);
            takeDownPost.setAdminId(UserContext.getUserId());
            takeDownPostMapper.insert(takeDownPost);
        }
        
        if (status == NORMAL) {
            redisTemplate.opsForZSet().add(RedisContext.HOST_POST_KEY, postId.toString(), 0.0);
        } else {
            redisTemplate.opsForZSet().remove(RedisContext.HOST_POST_KEY, postId.toString());
        }
        sendMessageToUser(post.getUserId(), status == NORMAL ? CheckAction.RESTORE : CheckAction.TAKE_DOWN,
                PostType.POST, postId, post.getPostTitle(), PostType.POST, postId, null, null);
    }
    public CursorPageResult<CommentVo> cursorQuestions(Long lastId, Integer pageSize,
                                                       Long rootCommentId, PostType type) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        List<CommentVo> res = new ArrayList<>();

        if (lastId != null) {
            wrapper.gt(Comment::getCommentId, lastId);
        }

        // 评论走「即时发布」，不存在待审核态；审核台在这里列出已发布评论用于下架处置
        wrapper.eq(Comment::getStatus, NORMAL);
        wrapper.eq(Comment::getType, type == null ? PostType.POST : type);
        wrapper.orderByAsc(Comment::getCommentId);
        wrapper.last("LIMIT " + (pageSize + 1));
        if (rootCommentId != null && !rootCommentId.equals(-1L)) {
            wrapper.eq(Comment::getRootCommentId, rootCommentId);
        }

        List<Comment> returnPostVoList = commentMapper.selectList(wrapper);

        List<Comment> records;
        Long nextCursor = null;
        Boolean hasNext = false;

        if (returnPostVoList != null && !returnPostVoList.isEmpty()) {
            if (returnPostVoList.size() > pageSize) {
                records = returnPostVoList.subList(0, pageSize);
                Comment lastRecord = records.getLast();
                nextCursor = lastRecord.getCommentId();
                hasNext = true;
            } else {
                records = returnPostVoList;
            }
        } else {
            records = new ArrayList<>();
        }


        for (Comment comment : records) {
            CommentVo commentVo = new CommentVo(comment);
            res.add(commentVo);
        }

        return CursorPageResult.<CommentVo>builder()
                .records(res)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }

    /**
     * 评论下架 / 恢复：status 2-下架 1-恢复。
     *
     * <p>评论为即时发布，不存在待审核态，故只有下架与恢复两种处置。</p>
     */
    public void UpdateComment(Long commentId, Integer status, String reason) {
        if(status!=NORMAL&&status!=TACK_DOWN){
            throw new IllegalArgumentException("状态不合法");
        }
        Comment comment = commentMapper.selectById(commentId);
        if(comment==null){
            throw new IllegalArgumentException("评论不存在");
        }
        if(comment.getStatus()!=NORMAL&&comment.getStatus()!=TACK_DOWN){
            throw new IllegalArgumentException("非正常状态");
        }
        comment.setStatus(status);
        int r= commentMapper.updateById(comment);
        if(r==0){
            throw new IllegalArgumentException("更新评论状态失败");
        }
        
        // 下架时记录到下架表
        if (status == TACK_DOWN) {
            TakeDownPost takeDownPost = new TakeDownPost();
            takeDownPost.setRootType(PostType.COMMENT);
            takeDownPost.setRootId(commentId);
            takeDownPost.setRootCommentId(comment.getRootCommentId());
            // 获取 questionId（如果是题解评论）
            PostType rootType = comment.getType() == null ? PostType.POST : comment.getType();
            if (rootType == PostType.SOLUTION) {
                Solution solution = solutionMapper.selectById(comment.getPostId());
                if (solution != null) {
                    takeDownPost.setQuestionId(solution.getQuestionId());
                }
            }
            takeDownPost.setReason(reason);
            takeDownPost.setAdminId(UserContext.getUserId());
            takeDownPostMapper.insert(takeDownPost);
        }
        
        sendCommentMessage(comment, status == NORMAL ? CheckAction.RESTORE : CheckAction.TAKE_DOWN);
    }


    //Solution审核

    public CursorPageResult<HomeSolutionVo> listSolutions( Long lastId, Integer pageSize) {
        LambdaQueryWrapper<Solution> wrapper = new LambdaQueryWrapper<Solution>()
                .eq(Solution::getStatus, 0)
                .orderByAsc(Solution::getSolutionId)
                .last("LIMIT " + (pageSize + 1));
        if (lastId != null) {
            wrapper.gt(Solution::getSolutionId, lastId);
        }
        List<Solution> queried = solutionMapper.selectList(wrapper);
        List<Long>userIds=getAllUserIds(queried);
        Result<Map<Long, UserDto>> userDtoMapBody=null;
        if(!userIds.isEmpty()){
            userDtoMapBody=userFeignClient.getUserList(userIds);}

        boolean hasNext = queried.size() > pageSize;
        List<Solution> records = hasNext ? queried.subList(0, pageSize) : queried;
        List<HomeSolutionVo> vos = toHomeSolutionVos(records);
        if(userDtoMapBody!=null&&userDtoMapBody.getData()!=null){
            for(HomeSolutionVo vo:vos){
                vo.setUserDto(userDtoMapBody.getData().getOrDefault(Long.parseLong(vo.getSolutionUserId()),null));
            }}
        Long nextCursor = hasNext && !records.isEmpty() ? records.getLast().getSolutionId() : null;
        return CursorPageResult.<HomeSolutionVo>builder()
                .records(vos)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }

    public void checkSolution(Long solutionId, Integer status, String reason) {
        if(status!=2&&status!=1){
            throw new IllegalArgumentException("状态不合法");
        }

        Solution solution = solutionMapper.selectById(solutionId);
        if(solution==null){
            throw new IllegalArgumentException("题解不存在");
        }
        if(solution.getStatus()!=0){
            throw new IllegalArgumentException("非待审核状态");
        }
        solution.setStatus(status);
        int r= solutionMapper.updateById(solution);
        if(r==0){
            throw new IllegalArgumentException("更新题解状态失败");
        }
        if (status == TACK_DOWN) {
            saveTakeDownReason(PostType.SOLUTION, solutionId, null, solution.getQuestionId(), reason);
        }
        sendMessageToUser(solution.getSolutionUserId(), status == NORMAL ? CheckAction.PASS : CheckAction.REJECT,
                PostType.SOLUTION, solutionId, solution.getSolutionTitle(), PostType.SOLUTION, solutionId, null,
                solution.getQuestionId());
    }
    private void saveTakeDownReason(PostType type, Long targetId, Long rootCommentId, Long questionId, String reason) {
        TakeDownPost record = new TakeDownPost();
        record.setRootType(type);
        record.setRootId(targetId);
        record.setRootCommentId(rootCommentId);
        record.setQuestionId(questionId);
        record.setReason(reason);
        record.setAdminId(UserContext.getUserId());
        takeDownPostMapper.insert(record);
    }

    public void updateSolution(Long solutionId, Integer status, String reason) {
        if(status!=2&&status!=1){
            throw new
                    IllegalArgumentException("状态不合法");
        }
        Solution solution = solutionMapper.selectById(solutionId);
        if(solution==null){
            throw new IllegalArgumentException("题解不存在");
        }
        if(solution.getStatus()!=NORMAL&&solution.getStatus()!=TACK_DOWN){
            throw new IllegalArgumentException("非正常状态");
        }
        solution.setStatus(status);
        int r= solutionMapper.updateById(solution);
        if(r==0){
            throw new IllegalArgumentException("更新题解状态失败");
        }
        
        // 下架时记录到下架表
        if (status == TACK_DOWN) {
            TakeDownPost takeDownPost = new TakeDownPost();
            takeDownPost.setRootType(PostType.SOLUTION);
            takeDownPost.setRootId(solutionId);
            takeDownPost.setRootCommentId(null);
            takeDownPost.setQuestionId(solution.getQuestionId());
            takeDownPost.setReason(reason);
            takeDownPost.setAdminId(UserContext.getUserId());
            takeDownPostMapper.insert(takeDownPost);
        }
        
        sendMessageToUser(solution.getSolutionUserId(),
                status == NORMAL ? CheckAction.RESTORE : CheckAction.TAKE_DOWN,
                PostType.SOLUTION, solutionId, solution.getSolutionTitle(), PostType.SOLUTION, solutionId, null,
                solution.getQuestionId());
    }


    //===================审核详情=====================

    /** 审核台查看帖子详情，包含正文、标签和作者信息，不受待审核状态限制。 */
    public CheckDetailVo getPostDetail(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new IllegalArgumentException("帖子不存在");
        }
        CheckDetailVo vo = CheckDetailVo.of(post);
        vo.setTags(getTagNames(postId, PostType.POST));
        vo.setUserDto(getUser(post.getUserId()));
        return vo;
    }

    /** 审核台查看评论详情，附带评论所属帖子或题解的标题，便于判断上下文。 */
    public CheckDetailVo getCommentDetail(Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new IllegalArgumentException("评论不存在");
        }
        CheckDetailVo vo = CheckDetailVo.of(comment);
        vo.setUserDto(getUser(comment.getUserId()));
        PostType type = comment.getType() == null ? PostType.POST : comment.getType();
        if (type == PostType.SOLUTION) {
            Solution solution = solutionMapper.selectById(comment.getPostId());
            if (solution != null) {
                vo.setTitle(solution.getSolutionTitle());
                vo.setQuestionId(solution.getQuestionId());
            }
        } else {
            Post post = postMapper.selectById(comment.getPostId());
            if (post != null) {
                vo.setTitle(post.getPostTitle());
            }
        }
        return vo;
    }

    /** 审核台查看题解详情，包含正文、标签、题目 ID 和作者信息。 */
    public CheckDetailVo getSolutionDetail(Long solutionId) {
        Solution solution = solutionMapper.selectById(solutionId);
        if (solution == null) {
            throw new IllegalArgumentException("题解不存在");
        }
        CheckDetailVo vo = CheckDetailVo.of(solution);
        vo.setTags(getTagNames(solutionId, PostType.SOLUTION));
        vo.setUserDto(getUser(solution.getSolutionUserId()));
        return vo;
    }

    /** 按内容类型分发到对应的详情查询。 */
    public CheckDetailVo getDetail(PostType type, Long targetId) {
        if (type == null) {
            throw new IllegalArgumentException("类型错误");
        }
        return switch (type) {
            case POST -> getPostDetail(targetId);
            case COMMENT -> getCommentDetail(targetId);
            case SOLUTION -> getSolutionDetail(targetId);
        };
    }

    private List<String> getTagNames(Long targetId, PostType type) {
        List<Tags> tags = tagsMapper.selectList(new LambdaQueryWrapper<Tags>()
                .eq(Tags::getPostId, targetId)
                .eq(Tags::getType, type));
        List<String> tagNames = new ArrayList<>();
        for (Tags tag : tags) {
            tagNames.add(tag.getTagName());
        }
        return tagNames;
    }

    private UserDto getUser(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            Result<UserDto> user = userFeignClient.getUserInfo(userId);
            return user == null ? null : user.getData();
        } catch (Exception e) {
            log.warn("审核详情查询作者信息失败，userId={}", userId, e);
            return null;
        }
    }




    private List<Long>getAllUserIds(List<Solution>solutions) {
        List<Long> userIds = new ArrayList<>();
        for (Solution solution : solutions) {
            userIds.add(solution.getSolutionUserId());
        }

        return userIds;
    }

    private List<HomeSolutionVo> toHomeSolutionVos(List<Solution> solutions) {
        if (solutions.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> ids = solutions.stream().map(Solution::getSolutionId).toList();
        Map<Long, List<String>> tagMap = new HashMap<>();
        for (Tags tag : tagsMapper.selectList(new LambdaQueryWrapper<Tags>()
                .in(Tags::getPostId, ids)
                .eq(Tags::getType, PostType.SOLUTION))) {
            tagMap.computeIfAbsent(tag.getPostId(), key -> new ArrayList<>()).add(tag.getTagName());
        }
        List<HomeSolutionVo> result = new ArrayList<>();
        for (Solution solution : solutions) {
            HomeSolutionVo vo = new HomeSolutionVo(solution);
            vo.setTags(tagMap.getOrDefault(solution.getSolutionId(), Collections.emptyList()));
            result.add(vo);
        }
        return result;
    }

    //check成功后发送消息给用户
    private String SuccessGetMessage(PostType type) {
       switch (type) {
           case POST:
               return "你的帖子已通过审核，现在其他用户可以看到它了。";
           case COMMENT:
               return "你的评论已通过审核，现在其他用户可以看到它了。";
           case SOLUTION:
                return "你的题解已通过审核，现在其他用户可以看到它了。";
           default:
               throw new IllegalArgumentException("类型错误");
       }

    }
    //check失败后发送消息给用户
    private String FailGetMessage(PostType type) {
        switch (type) {
            case POST:
                return "你的帖子未通过审核，请检查内容是否符合社区规范后重新发布。";
        case COMMENT:
                return "你的评论未通过审核，请检查内容是否符合社区规范后重新发布。";
            case SOLUTION:
                return "你的题解未通过审核，请检查内容是否符合社区规范后重新发布。";
            default:
                throw new IllegalArgumentException("类型错误");
        }
    }

    /** 内容被管理员下架后发送给用户的说明。 */
    private String TackDownGetMessage(PostType type) {
        switch (type) {
            case POST:
                return "你的帖子因不符合社区规范已被管理员下架，如有疑问可联系管理员申诉。";
            case COMMENT:
                return "你的评论因不符合社区规范已被管理员下架，如有疑问可联系管理员申诉。";
            case SOLUTION:
                return "你的题解因不符合社区规范已被管理员下架，如有疑问可联系管理员申诉。";
            default:
                throw new IllegalArgumentException("类型错误");
        }
    }

    /** 内容被恢复上架后发送给用户的说明。 */
    private String RestoreGetMessage(PostType type) {
        switch (type) {
            case POST:
                return "你的帖子已恢复展示，其他用户可以重新看到它了。";
            case COMMENT:
                return "你的评论已恢复展示，其他用户可以重新看到它了。";
            case SOLUTION:
                return "你的题解已恢复展示，其他用户可以重新看到它了。";
            default:
                throw new IllegalArgumentException("类型错误");
        }
    }

    /** 评论通知需要先定位所属帖子或题解，便于前端跳转。 */
    private void sendCommentMessage(Comment comment, CheckAction action) {
        PostType rootType = comment.getType() == null ? PostType.POST : comment.getType();
        Long questionId = null;
        String title = null;
        if (rootType == PostType.SOLUTION) {
            Solution solution = solutionMapper.selectById(comment.getPostId());
            if (solution != null) {
                questionId = solution.getQuestionId();
                title = solution.getSolutionTitle();
            }
        } else {
            Post post = postMapper.selectById(comment.getPostId());
            if (post != null) {
                title = post.getPostTitle();
            }
        }
        sendMessageToUser(comment.getUserId(), action, PostType.COMMENT,
                comment.getCommentId(), title, rootType, comment.getPostId(), comment.getRootCommentId(), questionId);
    }


    /**
     * 审核结论通知，异步投递到收件箱队列，投递失败不影响审核结果落库。
     *
     * @param userId 接收通知的用户id，即内容作者
     * @param action 审核动作，决定通知文案与幂等键
     * @param type 所属类型，帖子、题解或评论
     * @param id 被审核内容自身的id
     * @param title 内容标题，评论使用所属帖子或题解的标题
     * @param rootType 跳转承载内容类型：POST 或 SOLUTION
     * @param rootId 跳转承载内容id：帖子id或题解id
     * @param rootCommentId 评论所在根评论id，非评论传null
     * @param questionId 题解所属题目id，帖子传null
     */
    private void sendMessageToUser(Long userId, CheckAction action, PostType type, Long id,
                                   String title, PostType rootType, Long rootId, Long rootCommentId,
                                   Long questionId) {
        if (userId == null) {
            log.warn("审核通知缺少接收用户，type={}，id={}", type, id);
            return;
        }
        boolean pass = action != CheckAction.REJECT && action != CheckAction.TAKE_DOWN;
        boolean takeDown = action == CheckAction.TAKE_DOWN;
        CompletableFuture.runAsync(() -> {
            //发送到收件箱
            String messageId = CONTENT_CHECK + ":" + type.name() + ":" + id + ":" + action.code;
            NotificationDto notificationDto = new NotificationDto();
            notificationDto.setMessageId(messageId);
            notificationDto.setUserId(userId);
            notificationDto.setType(NotificationCenterType.CHECKED);
            notificationDto.setBusinessType(BusinessType.valueOf(type.name()));
            notificationDto.setBusinessId(id);

            NotificationCheckedDto checkedDto = NotificationCheckedDto.builder()
                    .passed(pass)
                    .takeDown(takeDown)
                    .title(title)
                    .message(switch (action) {
                        case PASS -> SuccessGetMessage(type);
                        case REJECT -> FailGetMessage(type);
                        case TAKE_DOWN -> TackDownGetMessage(type);
                        case RESTORE -> RestoreGetMessage(type);
                    })
                    .rootId(rootId == null ? null : rootId.toString())
                    .rootType((rootType == null ? PostType.POST : rootType).getType())
                    .rootCommentId(rootCommentId == null ? null : rootCommentId.toString())
                    .questionId(questionId == null ? null : questionId.toString())
                    .build();
            try {
                notificationDto.setExtraData(objectMapper.writeValueAsString(checkedDto));
            } catch (Exception e) {
                log.error("审核通知扩展数据序列化失败，type={}，id={}", type, id, e);
                return;
            }
            log.info("发送审核通知，exchange={}，routingKey={}，messageId={}，userId={}",
                    MqContexts.NOTIFICATION_EXCHANGE,
                    MqContexts.NOTIFICATION_CHECKED_ROUTING_KEY,
                    messageId,
                    userId);
            try {
                rabbitTemplate.convertAndSend(
                        MqContexts.NOTIFICATION_EXCHANGE,
                        MqContexts.NOTIFICATION_CHECKED_ROUTING_KEY,
                        notificationDto
                );
                log.info("审核通知已提交 RabbitMQ，messageId={}，userId={}", messageId, userId);
            } catch (Exception e) {
                log.error("审核通知提交 RabbitMQ 失败，messageId={}，userId={}", messageId, userId, e);
                throw e;
            }
        }).exceptionally(exception -> {
            log.error("审核通知发送失败，type={}，id={}", type, id, exception);
            return null;
        });
    }

    /**
     * 查询下架记录列表（游标分页）
     */
    public CursorPageResult<TakeDownPostVo> getTakeDownList(Long lastId, Integer pageSize) {
        LambdaQueryWrapper<TakeDownPost> wrapper = new LambdaQueryWrapper<>();
        if (lastId != null) {
            wrapper.lt(TakeDownPost::getId, lastId);
        }
        wrapper.orderByDesc(TakeDownPost::getId).last("LIMIT " + pageSize);
        
        List<TakeDownPost> records = takeDownPostMapper.selectList(wrapper);
        if (records.isEmpty()) {
            return new CursorPageResult<>(Collections.emptyList(), null, false, 0L);
        }
        
        // 收集所有管理员 ID
        Set<Long> adminIds = new HashSet<>();
        for (TakeDownPost record : records) {
            if (record.getAdminId() != null) {
                adminIds.add(record.getAdminId());
            }
        }
        
        // 批量查询管理员信息
        Map<Long, String> adminNameMap = new HashMap<>();
        if (!adminIds.isEmpty()) {
            Result<Map<Long, UserDto>> userResult = userFeignClient.getUserList(new ArrayList<>(adminIds));
            if (userResult != null && userResult.getData() != null) {
                for (Map.Entry<Long, UserDto> entry : userResult.getData().entrySet()) {
                    adminNameMap.put(entry.getKey(), entry.getValue().getUserName());
                }
            }
        }
        
        // 组装 VO
        List<TakeDownPostVo> voList = new ArrayList<>();
        for (TakeDownPost record : records) {
            TakeDownPostVo vo = new TakeDownPostVo();
            vo.setTakeDownId(record.getId());
            vo.setRootType(record.getRootType());
            vo.setRootId(record.getRootId());
            vo.setRootCommentId(record.getRootCommentId());
            vo.setQuestionId(record.getQuestionId());
            vo.setReason(record.getReason());
            vo.setAdminId(record.getAdminId());
            vo.setAdminName(adminNameMap.get(record.getAdminId()));
            vo.setCreateTime(record.getCreateTime());
            
            // 获取关联内容的标题和内容摘要
            fillTakeDownContent(vo, record);
            
            voList.add(vo);
        }
        
        Long nextCursor = records.get(records.size() - 1).getId();
        boolean hasMore = records.size() == pageSize;
        
        return new CursorPageResult<>(voList, nextCursor, hasMore, (long) voList.size());
    }
    
    /**
     * 填充下架记录的内容信息
     */
    private void fillTakeDownContent(TakeDownPostVo vo, TakeDownPost record) {
        try {
            switch (record.getRootType()) {
                case POST:
                    Post post = postMapper.selectById(record.getRootId());
                    if (post != null) {
                        vo.setTitle(post.getPostTitle());
                        vo.setContent(post.getPostContent() != null && post.getPostContent().length() > 100 
                            ? post.getPostContent().substring(0, 100) + "..." 
                            : post.getPostContent());
                    }
                    break;
                case SOLUTION:
                    Solution solution = solutionMapper.selectById(record.getRootId());
                    if (solution != null) {
                        vo.setTitle(solution.getSolutionTitle());
                        vo.setContent(solution.getSolutionContent() != null && solution.getSolutionContent().length() > 100 
                            ? solution.getSolutionContent().substring(0, 100) + "..." 
                            : solution.getSolutionContent());
                    }
                    break;
                case COMMENT:
                    Comment comment = commentMapper.selectById(record.getRootId());
                    if (comment != null) {
                        vo.setTitle("评论");
                        vo.setContent(comment.getComment() != null && comment.getComment().length() > 100 
                            ? comment.getComment().substring(0, 100) + "..." 
                            : comment.getComment());
                    }
                    break;
            }
        } catch (Exception e) {
            log.error("填充下架记录内容失败: id={}", record.getId(), e);
        }
    }
}
