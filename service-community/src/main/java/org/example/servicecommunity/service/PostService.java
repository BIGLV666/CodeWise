package org.example.servicecommunity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicecommunity.Dto.PostDto;
import org.example.servicecommunity.entry.Comment;
import org.example.servicecommunity.entry.LikeRecord;
import org.example.servicecommunity.entry.Post;
import org.example.servicecommunity.entry.Tags;
import org.example.servicecommunity.mapper.CommentMapper;
import org.example.servicecommunity.mapper.LikeRecordMapper;
import org.example.servicecommunity.mapper.PostMapper;
import org.example.servicecommunity.mapper.TagsMapper;
import org.example.servicecommunity.vo.CursorPageResult;
import org.example.servicecommunity.vo.HomePostVo;
import org.example.servicecommunity.vo.PostVo;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class PostService {
    @Autowired
    private PostMapper postMapper;
    @Autowired
    private TagsMapper tagsMapper;
    @Autowired
    private LikeRecordMapper likeRecordMapper;
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private UserFeignClient userFeignClient;

    public String getRequestId() {
        return UUID.randomUUID().toString();
    }

    @Transactional
    public Post createPost(PostDto postDto, String uuid) {
        if (postDto.getPostTitle() == null || postDto.getPostContent() == null) {
            throw new IllegalArgumentException("post title and content can not be null");
        }
        if (postDto.getPostTitle().length() > 200) {
            throw new IllegalArgumentException("post title is too long");
        }
        if (Boolean.FALSE.equals(redisTemplate.opsForValue().setIfAbsent(RedisContext.REQUEST_ID_KEY + uuid, "pending", 3, TimeUnit.MINUTES))) {
            log.info("duplicate post request");
            return null;
        }

        Post post = Post.builder()
                .postTitle(postDto.getPostTitle())
                .postContent(postDto.getPostContent())
                .userId(UserContext.getUserId())
                .userName(UserContext.getUserName())
                .likeCount(0L)
                .commentCount(0L)
                .status(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        int r1 = postMapper.insert(post);
        if (r1 == 0) {
            throw new IllegalArgumentException("create post failed");
        }

        List<Tags> tags = new ArrayList<>();
        if (postDto.getTags() != null) {
            for (String tag : postDto.getTags()) {
                if (tag != null && tag.length() < 20) {
                    tags.add(Tags.builder().tagName(tag).postId(post.getPostId()).build());
                }
            }
        }
        if (!tags.isEmpty()) {
            int r2 = tagsMapper.batchInsert(tags);
            if (r2 == 0) {
                log.info("add post tags failed");
                throw new IllegalArgumentException("create post failed");
            }
        }
        redisTemplate.opsForZSet().add(RedisContext.HOST_POST_KEY, post.getPostId().toString(), 0.0);
        redisTemplate.opsForValue().set(RedisContext.REQUEST_ID_KEY + uuid, "success", 3, TimeUnit.MINUTES);
        return post;
    }

    public CursorPageResult<HomePostVo> cursorQuestions(Long lastId, Integer pageSize) {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        if (lastId != null) {
            wrapper.gt(Post::getPostId, lastId);
        }
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

    public PostVo getPostById(Long postId) {
        PostVo postvo = (PostVo) redisTemplate.opsForHash().get(RedisContext.POST_VO_KEY,postId.toString());
        if(postvo != null) {
            return postvo;
        }
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Post::getPostId, postId);
         Post post = postMapper.selectOne(wrapper);

        List<Tags> tags = tagsMapper.selectList(new LambdaQueryWrapper<Tags>().eq(Tags::getPostId, postId));
        List<Tags> relatedTags = tags.isEmpty() ? Collections.emptyList() : tagsMapper.BatchSelectPostFromTagName(tags);
        Map<String, List<Long>> map = new HashMap<>();
        for (Tags tag : relatedTags) {
            map.computeIfAbsent(tag.getTagName(), key -> new ArrayList<>()).add(tag.getPostId());
        }

        Map<String, Map<Long, String>> relatedPost = new HashMap<>();
        for (Map.Entry<String, List<Long>> entry : map.entrySet()) {
            List<Post> relatedPosts = entry.getValue().isEmpty() ? Collections.emptyList() : postMapper.BatchSelectTitlesForId(entry.getValue());
            Map<Long, String> idAndTitle = new HashMap<>();
            for (Post related : relatedPosts) {
                idAndTitle.put(related.getPostId(), related.getPostTitle());
            }
            relatedPost.put(entry.getKey(), idAndTitle);
        }

        PostVo postVo = new PostVo(post);
        postVo.setRelatedPost(relatedPost);
        postVo.setIsLike(likeRecordMapper.selectOne(new QueryWrapper<LikeRecord>()
                .eq("post_id", postVo.getPostId())
                .eq("user_id", UserContext.getUserId())
                .eq("type", "POST")) != null);

        Result<UserDto> userDto = userFeignClient.getUserInfo(Long.parseLong(postVo.getUserId()));
        postVo.setUserDto(userDto.getData() == null ? null : userDto.getData());
        redisTemplate.opsForHash().put(RedisContext.POST_VO_KEY, postVo.getPostId().toString(), postVo);
        return postVo;
    }

    public List<HomePostVo> getPostByUserId(String tag) {
        List<Tags> tags = tagsMapper.selectList(new QueryWrapper<Tags>().eq("tag_name", tag));
        List<Long> postIds = new ArrayList<>();
        for (Tags tag1 : tags) {
            postIds.add(tag1.getPostId());
        }

        List<Post> posts = postIds.isEmpty() ? Collections.emptyList() : postMapper.selectBatchIds(postIds);
        List<Long> userIds = new ArrayList<>();
        List<HomePostVo> returnPostVoList = new ArrayList<>();
        for (Post post : posts) {
            returnPostVoList.add(new HomePostVo(post));
            userIds.add(post.getUserId());
        }

        Result<Map<Long, UserDto>> users = userFeignClient.getUserList(userIds);
        for (HomePostVo postVo : returnPostVoList) {
            UserDto user = users.getData() == null ? null : users.getData().get(postVo.getUserId());
            postVo.setUserName(user == null ? null : user.getNickName());
            postVo.setAvatar(user == null ? null : user.getAvatarUrl());
        }
        return returnPostVoList;
    }

    public List<HomePostVo> searchPostsByTitle(String keyword, Integer limit) {
        String normalizedKeyword = validateSearchValue(keyword, "keyword");
        List<Post> posts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, 1)
                .like(Post::getPostTitle, normalizedKeyword)
                .orderByDesc(Post::getCreateTime)
                .last("LIMIT " + limit));
        return toHomePostVos(posts);
    }

    public List<HomePostVo> searchPostsByTag(String tag, Integer limit) {
        String normalizedTag = validateSearchValue(tag, "tag");
        List<Tags> tags = tagsMapper.selectList(new LambdaQueryWrapper<Tags>()
                .eq(Tags::getTagName, normalizedTag)
                .last("LIMIT " + limit));
        if (tags.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> postIds = tags.stream().map(Tags::getPostId).distinct().toList();
        List<Post> posts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .in(Post::getPostId, postIds)
                .eq(Post::getStatus, 1)
                .orderByDesc(Post::getCreateTime));
        return toHomePostVos(posts);
    }

    private String validateSearchValue(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " can not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > 50) {
            throw new IllegalArgumentException(fieldName + " is too long");
        }
        return normalized;
    }

    private List<HomePostVo> toHomePostVos(List<Post> posts) {
        List<HomePostVo> result = posts.stream().map(HomePostVo::new).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        fillPostDetails(result);
        return result;
    }

    public void fillPostDetails(List<HomePostVo> postVos) {
        if (postVos == null || postVos.isEmpty()) {
            return;
        }
        List<Long> postIds = postVos.stream().map(HomePostVo::getPostId).toList();
        Map<Long, List<String>> tagsMap = new HashMap<>();
        for (Tags tag : tagsMapper.batchSelectForPostId(postIds)) {
            tagsMap.computeIfAbsent(tag.getPostId(), key -> new ArrayList<>()).add(tag.getTagName());
        }

        List<Long> userIds = postVos.stream().map(s-> Long.parseLong(s.getUserId())).distinct().toList();
        Result<Map<Long, UserDto>> users = userFeignClient.getUserList(userIds);
        Map<Long, UserDto> userMap = users == null || users.getData() == null ? Collections.emptyMap() : users.getData();
        for (HomePostVo postVo : postVos) {
            postVo.setTags(tagsMap.getOrDefault(postVo.getPostId(), Collections.emptyList()));
            UserDto user = userMap.get(postVo.getUserId());
            if (user != null) {
                postVo.setUserName(user.getNickName());
                postVo.setAvatar(user.getAvatarUrl());
            }
        }
    }

    @Transactional
    public PostVo updatePost(Long postId, PostVo postDto) {
        if (postDto == null || postDto.getPostTitle() == null || postDto.getPostTitle().trim().isEmpty()
                || postDto.getPostContent() == null || postDto.getPostContent().trim().isEmpty()) {
            throw new IllegalArgumentException("post title and content can not be blank");
        }
        String postTitle = postDto.getPostTitle().trim();
        if (postTitle.length() > 200) {
            throw new IllegalArgumentException("post title is too long");
        }

        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new IllegalArgumentException("post not found");
        }
        if (!post.getUserId().equals(UserContext.getUserId())) {
            throw new IllegalArgumentException("no permission to update this post");
        }

        List<Tags> tags = new ArrayList<>();
        if (postDto.getTags() != null) {
            for (String tagName : postDto.getTags()) {
                if (tagName == null || tagName.trim().isEmpty()) {
                    continue;
                }
                String normalizedTag = tagName.trim();
                if (normalizedTag.length() >= 20) {
                    throw new IllegalArgumentException("tag is too long");
                }
                boolean duplicated = tags.stream().anyMatch(tag -> tag.getTagName().equals(normalizedTag));
                if (!duplicated) {
                    tags.add(Tags.builder().postId(post.getPostId()).tagName(normalizedTag).build());
                }
            }
        }
        tagsMapper.delete(new QueryWrapper<Tags>().eq("post_id", post.getPostId()));
        if (!tags.isEmpty()) {
            int r = tagsMapper.batchInsert(tags);
            if (r != tags.size()) {
                throw new IllegalArgumentException("add post tags failed");
            }
        }

        post.setPostTitle(postTitle);
        post.setPostContent(postDto.getPostContent().trim());
        post.setUpdateTime(LocalDateTime.now());
        int r1 = postMapper.updateById(post);
        if (r1 == 0) {
            throw new IllegalArgumentException("update post failed");
        }
        redisTemplate.opsForHash().delete(RedisContext.POST_ID_KEY, post.getPostId().toString());
        redisTemplate.opsForHash().delete(RedisContext.POST_VO_KEY, post.getPostId().toString(), post.getPostId().toString());
        PostVo result = new PostVo(post);
        result.setTags(tags.stream().map(Tags::getTagName).toList());
        return result;
    }
    @Transactional
    public void deletePostById(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new IllegalArgumentException("post not found");
        }
        if (!post.getUserId().equals(UserContext.getUserId())) {
            throw new IllegalArgumentException("no permission to delete this post");
        }

        List<Comment> comments = commentMapper.selectList(new QueryWrapper<Comment>().eq("post_id", postId));
        List<Long> commentIds = comments.stream().map(Comment::getCommentId).toList();
        int result = postMapper.deleteById(postId);
        if (result == 0) {
            throw new IllegalArgumentException("delete post failed");
        }
        redisTemplate.opsForZSet().remove(RedisContext.HOST_POST_KEY, postId.toString());
        redisTemplate.opsForHash().delete(RedisContext.POST_ID_KEY, postId.toString());
        redisTemplate.opsForHash().delete(RedisContext.POST_VO_KEY, postId.toString(), postId.toString());

        CompletableFuture.runAsync(() -> {
            String deleteCommentKey = RedisContext.DELETE_COMMENT_KEY + "post:" + postId;
            String deleteLikeRecordKey = RedisContext.DELETE_LIKE_RECORD_KEY + "post:" + postId;
            redisTemplate.opsForValue().set(deleteCommentKey, "pending", 30, TimeUnit.MINUTES);
            redisTemplate.opsForValue().set(deleteLikeRecordKey, "pending", 30, TimeUnit.MINUTES);
            try {
                tagsMapper.delete(new QueryWrapper<Tags>().eq("post_id", postId));
                commentMapper.delete(new QueryWrapper<Comment>().eq("post_id", postId));
                likeRecordMapper.delete(new QueryWrapper<LikeRecord>().eq("post_id", postId).eq("type", "POST"));
                if (!commentIds.isEmpty()) {
                    likeRecordMapper.delete(new QueryWrapper<LikeRecord>()
                            .eq("type", "COMMENT")
                            .in("post_id", commentIds));
                }
                redisTemplate.opsForValue().set(deleteCommentKey, "success", 30, TimeUnit.MINUTES);
                redisTemplate.opsForValue().set(deleteLikeRecordKey, "success", 30, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.error("delete post cascade failed, postId={}", postId, e);
                redisTemplate.opsForValue().set(deleteCommentKey, "failed", 30, TimeUnit.MINUTES);
                redisTemplate.opsForValue().set(deleteLikeRecordKey, "failed", 30, TimeUnit.MINUTES);
            }
        });
    }
}
