package org.example.servicecommunity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class CommentService {
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private PostMapper postMapper;
    @Autowired
    private LikeRecordMapper likeRecordMapper;

    @Transactional
    public Comment createComment(CommentDto commentDto, String uuid) {
        if (commentDto == null || commentDto.getComment() == null || commentDto.getPostId() == null) {
            throw new IllegalArgumentException("comment content and postId can not be null");
        }
        if (Boolean.FALSE.equals(redisTemplate.opsForValue().setIfAbsent(RedisContext.REQUEST_ID_KEY + uuid, "pending", 3, TimeUnit.MINUTES))) {
            log.info("duplicate comment request");
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
            throw new IllegalArgumentException("create comment failed");
        }

        Post post = postMapper.selectById(comment.getPostId());
        if (post == null) {
            throw new IllegalArgumentException("post not found");
        }
        //为排行榜加分
        redisTemplate.opsForZSet().incrementScore(RedisContext.HOST_POST_KEY, post.getPostId().toString(), 2.0);
        int r = postMapper.updateCommentTotal(1, comment.getPostId());
        if (r == 0) {
            throw new IllegalArgumentException("create comment failed");
        }
        redisTemplate.opsForValue().set(RedisContext.REQUEST_ID_KEY + uuid, "success", 3, TimeUnit.MINUTES);
        return comment;
    }

    public CursorPageResult<CommentVo> cursorQuestions(Long lastId, Integer pageSize, Long postId, Long rootCommentId) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        List<CommentVo> res = new ArrayList<>();

        if (lastId != null) {
            wrapper.gt(Comment::getCommentId, lastId);
        }

        wrapper.eq(Comment::getPostId, postId);
        wrapper.orderByAsc(Comment::getCommentId);
        wrapper.last("LIMIT " + (pageSize + 1));
        if (!rootCommentId.equals(-1L)) {
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

        List<Long> commentIds = new ArrayList<>(records.stream().map(Comment::getCommentId).toList());
        Map<Long, LikeRecord> map = commentIds.isEmpty()
                ? new HashMap<>()
                : likeRecordMapper.selectBatchIdsForUserId(commentIds, UserContext.getUserId());
        for (Comment comment : records) {
            CommentVo commentVo = new CommentVo(comment);
            commentVo.setIsLike(map.containsKey(comment.getCommentId()));
            res.add(commentVo);
        }

        return CursorPageResult.<CommentVo>builder()
                .records(res)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }

    public void deleteComment(Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new IllegalArgumentException("comment not found");
        }
        if (!comment.getUserId().equals(UserContext.getUserId())) {
            throw new IllegalArgumentException("no permission to delete this comment");
        }
        Post post = postMapper.selectById(comment.getPostId());

        if (post == null) {
            throw new IllegalArgumentException("post not found");
        }
        List<Comment> comments = commentMapper.selectList(new QueryWrapper<Comment>()
                .eq("comment_id", commentId)
                .or()
                .eq("root_comment_id", commentId));
        List<Long> commentIds = comments.stream().map(Comment::getCommentId).toList();
        redisTemplate.opsForZSet().incrementScore(
                RedisContext.HOST_POST_KEY,
                post.getPostId().toString(),
                -2.0 * commentIds.size()
        );

        CompletableFuture.runAsync(() -> {
            String deleteCommentKey = RedisContext.DELETE_COMMENT_KEY + commentId;
            String deleteLikeRecordKey = RedisContext.DELETE_LIKE_RECORD_KEY + "comment:" + commentId;
            redisTemplate.opsForValue().set(deleteCommentKey, "pending", 30, TimeUnit.MINUTES);
            redisTemplate.opsForValue().set(deleteLikeRecordKey, "pending", 30, TimeUnit.MINUTES);
            try {
                if (!commentIds.isEmpty()) {
                    commentMapper.delete(new QueryWrapper<Comment>().in("comment_id", commentIds));
                    likeRecordMapper.delete(new QueryWrapper<LikeRecord>()
                            .eq("type", "COMMENT")
                            .in("post_id", commentIds));
                    postMapper.updateCommentTotal(-commentIds.size(), comment.getPostId());
                }
                redisTemplate.opsForValue().set(deleteCommentKey, "success", 30, TimeUnit.MINUTES);
                redisTemplate.opsForValue().set(deleteLikeRecordKey, "success", 30, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.error("delete comment cascade failed, commentId={}", commentId, e);
                redisTemplate.opsForValue().set(deleteCommentKey, "failed", 30, TimeUnit.MINUTES);
                redisTemplate.opsForValue().set(deleteLikeRecordKey, "failed", 30, TimeUnit.MINUTES);
            }
        });
    }
}
