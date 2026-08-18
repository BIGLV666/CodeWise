package org.example.servicecommunity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.servicecommon.until.UserContext;
import org.example.servicecommunity.entry.Comment;
import org.example.servicecommunity.entry.Post;
import org.example.servicecommunity.entry.Solution;
import org.example.servicecommunity.entry.TakeDownPost;
import org.example.servicecommunity.entry.Tags;
import org.example.servicecommunity.enums.PostType;
import org.example.servicecommunity.mapper.CommentMapper;
import org.example.servicecommunity.mapper.PostMapper;
import org.example.servicecommunity.mapper.SolutionMapper;
import org.example.servicecommunity.mapper.TagsMapper;
import org.example.servicecommunity.mapper.TakeDownPostMapper;
import org.example.servicecommunity.vo.CursorPageResult;
import org.example.servicecommunity.vo.MyContentVo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 当前用户管理自己发布的帖子、题解与评论。 */
@Service
public class MyContentService {
    private final PostMapper postMapper;
    private final SolutionMapper solutionMapper;
    private final CommentMapper commentMapper;
    private final TagsMapper tagsMapper;
    private final TakeDownPostMapper takeDownPostMapper;

    public MyContentService(PostMapper postMapper, SolutionMapper solutionMapper,
                            CommentMapper commentMapper, TagsMapper tagsMapper,
                            TakeDownPostMapper takeDownPostMapper) {
        this.postMapper = postMapper;
        this.solutionMapper = solutionMapper;
        this.commentMapper = commentMapper;
        this.tagsMapper = tagsMapper;
        this.takeDownPostMapper = takeDownPostMapper;
    }

    public CursorPageResult<MyContentVo> list(PostType type, Long lastId, Integer pageSize) {
        if (type == null) {
            throw new IllegalArgumentException("内容类型不能为空");
        }
        if (pageSize == null || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageSize 必须在 1 到 100 之间");
        }
        return switch (type) {
            case POST -> listPosts(lastId, pageSize);
            case SOLUTION -> listSolutions(lastId, pageSize);
            case COMMENT -> listComments(lastId, pageSize);
        };
    }

    private CursorPageResult<MyContentVo> listPosts(Long lastId, int pageSize) {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .eq(Post::getUserId, currentUserId())
                .lt(lastId != null, Post::getPostId, lastId)
                .orderByDesc(Post::getPostId)
                .last("LIMIT " + (pageSize + 1));
        List<Post> queried = postMapper.selectList(wrapper);
        PageSlice<Post> page = slice(queried, pageSize, Post::getPostId);
        List<MyContentVo> records = page.records().stream().map(MyContentVo::of)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        fillTags(records, PostType.POST);
        fillRejectReasons(records);
        return result(records, page);
    }

    private CursorPageResult<MyContentVo> listSolutions(Long lastId, int pageSize) {
        LambdaQueryWrapper<Solution> wrapper = new LambdaQueryWrapper<Solution>()
                .eq(Solution::getSolutionUserId, currentUserId())
                .lt(lastId != null, Solution::getSolutionId, lastId)
                .orderByDesc(Solution::getSolutionId)
                .last("LIMIT " + (pageSize + 1));
        List<Solution> queried = solutionMapper.selectList(wrapper);
        PageSlice<Solution> page = slice(queried, pageSize, Solution::getSolutionId);
        List<MyContentVo> records = page.records().stream().map(MyContentVo::of)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        fillTags(records, PostType.SOLUTION);
        fillRejectReasons(records);
        return result(records, page);
    }

    private CursorPageResult<MyContentVo> listComments(Long lastId, int pageSize) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getUserId, currentUserId())
                .lt(lastId != null, Comment::getCommentId, lastId)
                .orderByDesc(Comment::getCommentId)
                .last("LIMIT " + (pageSize + 1));
        List<Comment> queried = commentMapper.selectList(wrapper);
        PageSlice<Comment> page = slice(queried, pageSize, Comment::getCommentId);
        List<MyContentVo> records = page.records().stream().map(MyContentVo::of)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<Long> solutionIds = page.records().stream()
                .filter(comment -> comment.getType() == PostType.SOLUTION && comment.getPostId() != null)
                .map(Comment::getPostId)
                .distinct()
                .toList();
        if (!solutionIds.isEmpty()) {
            Map<Long, String> questionIdMap = new HashMap<>();
            for (Solution solution : solutionMapper.selectBatchIds(solutionIds)) {
                if (solution.getQuestionId() != null) {
                    questionIdMap.put(solution.getSolutionId(), solution.getQuestionId().toString());
                }
            }
            for (MyContentVo record : records) {
                if (record.getRootType() == PostType.SOLUTION && record.getRootId() != null) {
                    record.setQuestionId(questionIdMap.get(Long.valueOf(record.getRootId())));
                }
            }
        }
        fillRejectReasons(records);
        return result(records, page);
    }

    private void fillRejectReasons(List<MyContentVo> records) {
        for (MyContentVo record : records) {
            if (record.getStatus() == null || record.getStatus() != 2 || record.getTargetId() == null || record.getType() == null) {
                continue;
            }
            TakeDownPost takeDown = takeDownPostMapper.selectOne(new LambdaQueryWrapper<TakeDownPost>()
                    .eq(TakeDownPost::getRootType, record.getType())
                    .eq(TakeDownPost::getRootId, Long.valueOf(record.getTargetId()))
                    .orderByDesc(TakeDownPost::getCreateTime)
                    .last("LIMIT 1"));
            if (takeDown != null) {
                record.setRejectReason(takeDown.getReason());
            }
        }
    }

    private void fillTags(List<MyContentVo> records, PostType type) {
        if (records.isEmpty()) {
            return;
        }
        List<Long> ids = records.stream().map(item -> Long.valueOf(item.getTargetId())).toList();
        Map<Long, List<String>> tagMap = new HashMap<>();
        for (Tags tag : tagsMapper.selectList(new LambdaQueryWrapper<Tags>()
                .in(Tags::getPostId, ids)
                .eq(Tags::getType, type))) {
            tagMap.computeIfAbsent(tag.getPostId(), key -> new ArrayList<>()).add(tag.getTagName());
        }
        for (MyContentVo record : records) {
            record.setTags(tagMap.getOrDefault(Long.valueOf(record.getTargetId()), Collections.emptyList()));
        }
    }

    private Long currentUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("请登录后查看自己的内容");
        }
        return userId;
    }

    private <T> PageSlice<T> slice(List<T> queried, int pageSize,
                                   java.util.function.Function<T, Long> idGetter) {
        boolean hasNext = queried.size() > pageSize;
        List<T> records = hasNext ? queried.subList(0, pageSize) : queried;
        Long nextCursor = hasNext && !records.isEmpty() ? idGetter.apply(records.getLast()) : null;
        return new PageSlice<>(records, hasNext, nextCursor);
    }

    private <T> CursorPageResult<MyContentVo> result(List<MyContentVo> records, PageSlice<T> page) {
        return CursorPageResult.<MyContentVo>builder()
                .records(records)
                .nextCursor(page.nextCursor())
                .hasNext(page.hasNext())
                .build();
    }

    private record PageSlice<T>(List<T> records, boolean hasNext, Long nextCursor) {
    }
}


