package org.example.servicecommunity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicecommunity.Dto.SolutionDto;
import org.example.servicecommunity.entry.Comment;
import org.example.servicecommunity.entry.LikeRecord;
import org.example.servicecommunity.entry.Solution;
import org.example.servicecommunity.entry.Tags;
import org.example.servicecommunity.enums.PostType;
import org.example.servicecommunity.mapper.CommentMapper;
import org.example.servicecommunity.mapper.LikeRecordMapper;
import org.example.servicecommunity.mapper.SolutionMapper;
import org.example.servicecommunity.mapper.TagsMapper;
import org.example.servicecommunity.vo.CursorPageResult;
import org.example.servicecommunity.vo.HomeSolutionVo;
import org.example.servicecommunity.vo.SolutionVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class SolutionService {
    @Autowired
    private SolutionMapper solutionMapper;
    @Autowired
    private TagsMapper tagsMapper;
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private LikeRecordMapper likeRecordMapper;
    @Autowired
    private QuestionFeignClient questionFeignClient;
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;
    @Autowired
    private UserFeignClient userFeignClient;

    /** 发布题解，并把题解标签标记为 SOLUTION，避免与同 ID 的社区帖子串联。 */
    @Transactional
    public HomeSolutionVo createSolution(SolutionDto dto,String requestId) {
        if(requestId==null){
            throw new IllegalArgumentException("未知请求");
        }
        if(Boolean.FALSE.equals(redisTemplate.opsForValue().setIfAbsent(RedisContext.REQUEST_ID_KEY+":"+requestId,"pending"))){
            log.info("重复处理");
            return null;
        }
        validateSolution(dto);
        requireQuestion(dto.getQuestionId());

        Solution solution = Solution.builder()
                .questionId(dto.getQuestionId())
                .solutionTitle(dto.getSolutionTitle().trim())
                .solutionContent(dto.getSolutionContent().trim())
                .solutionUserId(UserContext.getUserId())
                .status(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        if (solutionMapper.insert(solution) == 0) {
            throw new IllegalArgumentException("发布题解失败");
        }
        List<Tags> tags = buildTags(solution.getSolutionId(), dto.getTags());
        if (!tags.isEmpty() && tagsMapper.batchInsert(tags) != tags.size()) {
            throw new IllegalArgumentException("保存题解标签失败");
        }
        redisTemplate.opsForValue().set(RedisContext.REQUEST_ID_KEY+":"+requestId,"success");
        return toHomeSolutionVo(solution, tags);
    }

    /** 按题目查询题解，使用 solutionId 升序游标分页。 */
    public CursorPageResult<HomeSolutionVo> listSolutions(Long questionId, Long lastId, Integer pageSize) {
        LambdaQueryWrapper<Solution> wrapper = new LambdaQueryWrapper<Solution>()
                .eq(Solution::getQuestionId, questionId)
                .eq(Solution::getStatus, 1)
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

    /** 查询题解详情，并把本次浏览写入 Redis 当前桶。 */
    public SolutionVo getSolution(Long solutionId) {
        Solution solution = solutionMapper.selectOne(new LambdaQueryWrapper<Solution>()
                .eq(Solution::getSolutionId, solutionId)
                .eq(Solution::getStatus, 1));
        if (solution == null) {
            throw new IllegalArgumentException("题解不存在或已下架");
        }
        Number bucketId = (Number) redisTemplate.opsForValue().get(RedisContext.SOLUTION_LOOK_BUCKET_KEY);
        if (bucketId == null) {
            redisTemplate.opsForValue().setIfAbsent(RedisContext.SOLUTION_LOOK_BUCKET_KEY, 0);
            bucketId = (Number) redisTemplate.opsForValue().get(RedisContext.SOLUTION_LOOK_BUCKET_KEY);
        }
        if (bucketId == null) {
            throw new IllegalStateException("获取题解浏览量桶失败");
        }
        redisTemplate.opsForHash().increment(
                RedisContext.SOLUTION_LOOK_KEY + "-" + bucketId.intValue(),
                solutionId.toString(),
                1
        );
        SolutionVo solutionVo = new SolutionVo(solution);
        Result<UserDto> userDto = userFeignClient.getUserInfo(Long.parseLong(solutionVo.getSolutionUserId()));
        solutionVo.setUserDto(userDto.getData()==null ? new UserDto() : userDto.getData());
        solutionVo.setIsLike(likeRecordMapper.selectOne(new QueryWrapper<LikeRecord>().eq("type",PostType.SOLUTION.getType()).eq("post_id",solutionId))!=null);
        return solutionVo;
    }

    /** 仅允许题解作者修改标题、正文和标签。 */
    @Transactional
    public HomeSolutionVo updateSolution(Long solutionId, SolutionDto dto) {
        validateSolution(dto);
        requireQuestion(dto.getQuestionId());
        Solution solution = requireOwnedSolution(solutionId);
        solution.setQuestionId(dto.getQuestionId());
        solution.setSolutionTitle(dto.getSolutionTitle().trim());
        solution.setSolutionContent(dto.getSolutionContent().trim());
        solution.setUpdateTime(LocalDateTime.now());
        if (solutionMapper.updateById(solution) == 0) {
            throw new IllegalArgumentException("修改题解失败");
        }
        tagsMapper.delete(new LambdaQueryWrapper<Tags>()
                .eq(Tags::getPostId, solutionId)
                .eq(Tags::getType, PostType.SOLUTION));
        List<Tags> tags = buildTags(solutionId, dto.getTags());
        if (!tags.isEmpty() && tagsMapper.batchInsert(tags) != tags.size()) {
            throw new IllegalArgumentException("修改题解标签失败");
        }
        return toHomeSolutionVo(solution, tags);
    }

    /** 删除题解及其标签、评论和评论点赞记录。 */
    @Transactional
    public void deleteSolution(Long solutionId) {
        requireOwnedSolution(solutionId);
        List<Comment> comments = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getPostId, solutionId)
                .eq(Comment::getType, PostType.SOLUTION));
        List<Long> commentIds = comments.stream().map(Comment::getCommentId).toList();
        //异步删除对应的评论以及点赞记录
        CompletableFuture.runAsync(()->{
            String deleteCommentKey = RedisContext.DELETE_COMMENT_KEY + "solution:" +solutionId;
            String deleteLikeRecordKey = RedisContext.DELETE_LIKE_RECORD_KEY + "solution:" + solutionId;
            redisTemplate.opsForValue().set(deleteCommentKey, "pending", 30, TimeUnit.MINUTES);
            redisTemplate.opsForValue().set(deleteLikeRecordKey, "pending", 30, TimeUnit.MINUTES);
            try{
            if (!commentIds.isEmpty()) {
                likeRecordMapper.delete(new QueryWrapper<LikeRecord>()
                        .eq("type", "COMMENT")
                        .in("post_id", commentIds));
            }
            commentMapper.delete(new LambdaQueryWrapper<Comment>()
                    .eq(Comment::getPostId, solutionId)
                    .eq(Comment::getType, PostType.SOLUTION));
            tagsMapper.delete(new LambdaQueryWrapper<Tags>()
                    .eq(Tags::getPostId, solutionId)
                    .eq(Tags::getType, PostType.SOLUTION));
            solutionMapper.deleteById(solutionId);
            redisTemplate.opsForValue().set(deleteCommentKey, "success", 30, TimeUnit.MINUTES);
            redisTemplate.opsForValue().set(deleteLikeRecordKey, "success", 30, TimeUnit.MINUTES);
            }catch(Exception e){
                log.error("异步删除题解关联数据失败，题解ID={}", solutionId, e);
                redisTemplate.opsForValue().set(deleteCommentKey, "failed", 30, TimeUnit.MINUTES);
                redisTemplate.opsForValue().set(deleteLikeRecordKey, "failed", 30, TimeUnit.MINUTES);
            }

    });
    }


    private Solution requireOwnedSolution(Long solutionId) {
        Solution solution = solutionMapper.selectById(solutionId);
        if (solution == null) {
            throw new IllegalArgumentException("题解不存在");
        }
        if (!solution.getSolutionUserId().equals(UserContext.getUserId())) {
            throw new IllegalArgumentException("无权修改或删除该题解");
        }
        return solution;
    }

    private void validateSolution(SolutionDto dto) {
        if (dto == null || dto.getQuestionId() == null || dto.getSolutionTitle() == null
                || dto.getSolutionTitle().trim().isEmpty() || dto.getSolutionContent() == null
                || dto.getSolutionContent().trim().isEmpty()) {
            throw new IllegalArgumentException("题目ID、题解标题和题解正文不能为空");
        }
        if (dto.getSolutionTitle().trim().length() > 200) {
            throw new IllegalArgumentException("题解标题不能超过200个字符");
        }
    }

    private void requireQuestion(Long questionId) {
        Result<QuestionDto> question = questionFeignClient.getQuestionInfo(questionId);
        if (question == null || question.getData() == null) {
            throw new IllegalArgumentException("题目不存在");
        }
    }

    private List<Tags> buildTags(Long solutionId, List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return new ArrayList<>();
        }
        List<Tags> tags = new ArrayList<>();
        for (String tagName : tagNames) {
            if (tagName == null || tagName.trim().isEmpty()) {
                continue;
            }
            String normalized = tagName.trim();
            if (normalized.length() >= 20) {
                throw new IllegalArgumentException("单个标签不能达到或超过20个字符");
            }
            if (tags.stream().noneMatch(tag -> tag.getTagName().equals(normalized))) {
                tags.add(Tags.builder()
                        .postId(solutionId)
                        .tagName(normalized)
                        .type(PostType.SOLUTION)
                        .build());
            }
        }
        return tags;
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

    private HomeSolutionVo toHomeSolutionVo(Solution solution, List<Tags> tags) {
        HomeSolutionVo vo = new HomeSolutionVo(solution);
        vo.setTags(tags.stream().map(Tags::getTagName).toList());
        return vo;
    }

    private List<Long>getAllUserIds(List<Solution>solutions) {
        List<Long> userIds = new ArrayList<>();
        for (Solution solution : solutions) {
            userIds.add(solution.getSolutionUserId());
        }

        return userIds;
    }

}
