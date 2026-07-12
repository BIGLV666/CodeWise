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
import org.example.servicecommunity.entry.Solution;
import org.example.servicecommunity.enums.PostType;
import org.example.servicecommunity.mapper.CommentMapper;
import org.example.servicecommunity.mapper.LikeRecordMapper;
import org.example.servicecommunity.mapper.PostMapper;
import org.example.servicecommunity.mapper.SolutionMapper;
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
    @Autowired
    private SolutionMapper solutionMapper;

    @Transactional
    public Comment createComment(CommentDto commentDto, String uuid) {
        if (commentDto == null || commentDto.getComment() == null || commentDto.getPostId() == null) {
            throw new IllegalArgumentException("comment content and postId can not be null");
        }
        if (Boolean.FALSE.equals(redisTemplate.opsForValue().setIfAbsent(RedisContext.REQUEST_ID_KEY + uuid, "pending", 3, TimeUnit.MINUTES))) {
            log.info("duplicate comment request");
            return null;
        }

        PostType type = commentDto.getType() == null ? PostType.POST : commentDto.getType();
        Post post = null;
        Solution solution = null;
        if (type == PostType.POST) {
            post = postMapper.selectById(commentDto.getPostId());
            if (post == null || !Integer.valueOf(1).equals(post.getStatus())) {
                throw new IllegalArgumentException("post not found");
            }
        } else {
            solution = solutionMapper.selectById(commentDto.getPostId());
            if (solution == null || !Integer.valueOf(1).equals(solution.getStatus())) {
                throw new IllegalArgumentException("solution not found");
            }
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
                .type(type)
                .status(1)
                .build();
        int result = commentMapper.insert(comment);
        if (result == 0) {
            throw new IllegalArgumentException("create comment failed");
        }

        int r;
        if (type == PostType.POST) {
            // 社区帖子评论会影响热点排行。
            redisTemplate.opsForZSet().incrementScore(RedisContext.HOST_POST_KEY, post.getPostId().toString(), 2.0);
            r = postMapper.updateCommentTotal(1, comment.getPostId());
        } else {
            r = solutionMapper.updateCommentTotal(1, solution.getSolutionId());
        }
        if (r == 0) {
            throw new IllegalArgumentException("create comment failed");
        }
        redisTemplate.opsForValue().set(RedisContext.REQUEST_ID_KEY + uuid, "success", 3, TimeUnit.MINUTES);
        return comment;
    }

    public CursorPageResult<CommentVo> cursorQuestions(Long lastId, Integer pageSize, Long postId,
                                                       Long rootCommentId, PostType type) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        List<CommentVo> res = new ArrayList<>();

        if (lastId != null) {
            wrapper.gt(Comment::getCommentId, lastId);
        }

        wrapper.eq(Comment::getPostId, postId);
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

        List<Long> commentIds = new ArrayList<>(records.stream().map(Comment::getCommentId).toList());
        Map<Long, LikeRecord> map = commentIds.isEmpty()
                ? new HashMap<>()
                : likeRecordMapper.selectBatchIdsForUserId(commentIds,PostType.COMMENT.getType(),UserContext.getUserId());
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
        PostType type = comment.getType() == null ? PostType.POST : comment.getType();
        List<Comment> comments = commentMapper.selectList(new QueryWrapper<Comment>()
                .eq("type", type.getType())
                .and(wrapper -> wrapper.eq("comment_id", commentId)
                        .or()
                        .eq("root_comment_id", commentId)));
        List<Long> commentIds = comments.stream().map(Comment::getCommentId).toList();
        if (type == PostType.POST) {
            Post post = postMapper.selectById(comment.getPostId());
            if (post == null) {
                throw new IllegalArgumentException("post not found");
            }
            redisTemplate.opsForZSet().incrementScore(
                    RedisContext.HOST_POST_KEY,
                    post.getPostId().toString(),
                    -2.0 * commentIds.size()
            );
        } else if (solutionMapper.selectById(comment.getPostId()) == null) {
            throw new IllegalArgumentException("solution not found");
        }

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
                    if (type == PostType.POST) {
                        postMapper.updateCommentTotal(-commentIds.size(), comment.getPostId());
                    } else {
                        solutionMapper.updateCommentTotal(-commentIds.size(), comment.getPostId());
                    }
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
