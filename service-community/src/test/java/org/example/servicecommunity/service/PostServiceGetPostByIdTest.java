package org.example.servicecommunity.service;

import org.example.serviceapi.exception.BizException;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommunity.entry.Post;
import org.example.servicecommunity.mapper.LikeRecordMapper;
import org.example.servicecommunity.mapper.PostMapper;
import org.example.servicecommunity.vo.PostVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 帖子详情「命中缓存」分支的回归测试。
 *
 * <p>该分支曾把 {@code postId}（Long）直接当作 Redis hash 的 field 传入，而模板的 key
 * 序列化器是 {@code StringRedisSerializer}，命中缓存即抛
 * {@code ClassCastException: Long cannot be cast to String}，接口返回
 * 「服务繁忙，请稍后重试」。因 {@code getPostById} 会在 DB 分支末尾把帖子写入
 * {@code POST_KEY} 缓存、{@code HotPostTask} 每 5 分钟清一次缓存，故障表现为
 * 「帖子详情时好时坏」，且只在容器内（真实 Redis）复现。</p>
 *
 * <p>本测试用的桩只命中字符串 field：若实现回退成传 Long，桩不命中、增量按 0 计算，
 * 断言随即失败。</p>
 */
class PostServiceGetPostByIdTest {

    private RedisTemplate<String, Object> redisTemplate;
    private HashOperations<String, Object, Object> hashOps;
    private ValueOperations<String, Object> valueOps;
    private LikeRecordMapper likeRecordMapper;
    private PostMapper postMapper;
    private PostService postService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        hashOps = mock(HashOperations.class);
        valueOps = mock(ValueOperations.class);
        likeRecordMapper = mock(LikeRecordMapper.class);
        postMapper = mock(PostMapper.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        postService = new PostService();
        ReflectionTestUtils.setField(postService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(postService, "likeRecordMapper", likeRecordMapper);
        ReflectionTestUtils.setField(postService, "postMapper", postMapper);
    }

    private Post cachedPost(long postId, long likeCount) {
        Post post = new Post();
        post.setPostId(postId);
        post.setUserId(2L);
        post.setLikeCount(likeCount);
        post.setCommentCount(0L);
        return post;
    }

    @Test
    void 命中缓存时按字符串field读取桶内点赞增量() {
        when(hashOps.get(RedisContext.POST_KEY, "7")).thenReturn(cachedPost(7L, 10L));
        when(valueOps.get(RedisContext.LIKE_POST_BUCKET_KEY)).thenReturn(0);
        // 写入方 LikeRecordService 用的就是 postId.toString()
        when(hashOps.get(RedisContext.LIKE_POST_KEY + "-0", "7")).thenReturn(3);
        when(likeRecordMapper.selectCount(any())).thenReturn(0L);

        PostVo vo = postService.getPostById(7L);

        assertEquals(13L, vo.getLikeCount(), "DB like_count 应叠加上桶内未落库增量");
    }

    @Test
    void 桶未初始化时增量按零处理() {
        when(hashOps.get(RedisContext.POST_KEY, "7")).thenReturn(cachedPost(7L, 10L));
        when(valueOps.get(RedisContext.LIKE_POST_BUCKET_KEY)).thenReturn(null);
        when(likeRecordMapper.selectCount(any())).thenReturn(0L);

        PostVo vo = postService.getPostById(7L);

        assertEquals(10L, vo.getLikeCount());
    }

    @Test
    void 缓存未命中时走数据库分支且帖子不存在应报业务异常() {
        when(hashOps.get(RedisContext.POST_KEY, "7")).thenReturn(null);
        when(postMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> postService.getPostById(7L));
    }
}
