package org.example.servicecommunity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicecommunity.Dto.CommentDto;
import org.example.servicecommunity.entry.Comment;
import org.example.servicecommunity.entry.LikeRecord;
import org.example.servicecommunity.entry.Post;
import org.example.servicecommunity.mapper.CommentMapper;
import org.example.servicecommunity.mapper.LikeRecordMapper;
import org.example.servicecommunity.mapper.PostMapper;
import org.example.servicecommunity.vo.CommentVo;
import org.example.servicecommunity.vo.CursorPageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class CommentService {
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;
    @Autowired
    private PostMapper postMapper;
    @Autowired
    private LikeRecordMapper likeRecordMapper;
    @Transactional
    public Comment createComment(CommentDto commentDto,String uuid) {

        if (commentDto == null || commentDto.getComment() == null || commentDto.getPostId() == null) {
            throw new IllegalArgumentException("评论内容和帖子ID不能为空");
        }
        if(Boolean.FALSE.equals(redisTemplate.opsForValue().setIfAbsent(RedisContext.REQUEST_ID_KEY+uuid, "pending",3, TimeUnit.MINUTES))){
            log.info("重复尝试");
            return null;
        }


        Comment comment = Comment.builder()
                .comment(commentDto.getComment())
                .userId(UserContext.getUserId())
                .userName(UserContext.getUserName())
                .postId(commentDto.getPostId())
                .rootCommentId(commentDto.getRootCommentId())
                .replyUserId(commentDto.getReplyUserId())
                .replyUserName(commentDto.getReplyUserName())
                .likeCount(0L)
                .status(1)
                .build();
        int result = commentMapper.insert(comment);
        if (result == 0) {
            throw new IllegalArgumentException("评论发布失败");
        }

        Post post= postMapper.selectById(comment.getPostId());
        if(post==null){
            throw new IllegalArgumentException("未找到该讨论");
        }
        int r=postMapper.updateCommentTotal(1,comment.getPostId());
        if(r==0){
            throw new IllegalArgumentException("评论发布失败");
        }
        redisTemplate.opsForValue().set(RedisContext.REQUEST_ID_KEY+uuid,"success",3, TimeUnit.MINUTES);
        return comment;
    }

    public CursorPageResult<CommentVo> cursorQuestions(Long lastId, Integer pageSize,Long postId,Long rootCommentId) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        List<CommentVo>res=new ArrayList<>();

        // 游标分页：如果传入了 lastId，则查询大于 lastId 的记录（升序）
        if (lastId != null) {
            wrapper.gt(Comment::getCommentId, lastId);  // 改为 gt（大于），从前往后
        }


        // 按 commentId 升序排列（从小到大）
        wrapper.eq(Comment::getPostId,postId);
        wrapper.orderByAsc(Comment::getCommentId);  // 改为升序
        wrapper.last("LIMIT " + (pageSize + 1));
        if(!rootCommentId.equals(-1L)){
            wrapper.eq(Comment::getRootCommentId,rootCommentId);
        }

        List<Comment> returnPostVoList = commentMapper.selectList(wrapper);

        List<Comment> records;

        Long nextCursor = null;
        Boolean hasNext = false;

        if (returnPostVoList != null && !returnPostVoList.isEmpty()) {
            if (returnPostVoList.size() > pageSize) {
                // 取前 pageSize 条作为当前页
                records = returnPostVoList.subList(0, pageSize);
                // 获取最后一条记录的 ID 作为下一页的游标
                Comment lastRecord = records.getLast();
                nextCursor = lastRecord.getCommentId();
                hasNext = true;
            } else {
                records =  returnPostVoList;
                hasNext = false;
            }
        } else {
            records = new ArrayList<>();
        }
        Map<Long,LikeRecord>map=new HashMap<>();

            List<Long>commentIds = new ArrayList<>(records.stream().map(Comment::getCommentId).toList());
             map= likeRecordMapper.selectBatchIdsForUserId(commentIds,UserContext.getUserId());
             for(Comment comment:records){
                 CommentVo commentVo=new CommentVo(comment);
                 commentVo.setIsLike(map.containsKey(comment.getCommentId()));
                 res.add(commentVo);
             }

        return CursorPageResult.<CommentVo>builder()
                .records(res)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }
}
