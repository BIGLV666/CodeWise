package org.example.servicecommunity.config;

import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.RedisConfig;
import org.example.servicecommunity.mapper.CommentMapper;
import org.example.servicecommunity.mapper.PostMapper;
import org.example.servicecommunity.mapper.SolutionMapper;
import org.example.servicecommunity.task.LikeTask;
import org.example.servicecommunity.task.SolutionLookTask;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisBucketSwitcherTest {
    private RedisTemplate<String, Object> template;
    private RedisBucketSwitcher switcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        template = mock(RedisTemplate.class);
        switcher = new RedisBucketSwitcher();
        ReflectionTestUtils.setField(switcher, "redisTemplate", template);
    }

    @Test
    void missingSourceReturnsFalseAndUsesAtomicScriptWithBothKeys() {
        when(template.execute(any(RedisScript.class), eq(List.of("source", "target")))).thenAnswer(call -> {
            RedisScript<Long> script = call.getArgument(0);
            assertEquals(Long.class, script.getResultType());
            assertTrue(script.getScriptAsString().contains("redis.call('EXISTS', KEYS[1])"));
            assertTrue(script.getScriptAsString().contains("redis.call('RENAMENX', KEYS[1], KEYS[2])"));
            return 0L;
        });

        assertFalse(switcher.renameIfSourceExists("source", "target"));
        verify(template).execute(any(RedisScript.class), eq(List.of("source", "target")));
        verifyNoMoreInteractions(template);
    }

    @Test
    void successfulRenameReturnsTrue() {
        when(template.execute(any(RedisScript.class), anyList())).thenReturn(1L);
        assertTrue(switcher.renameIfSourceExists("source", "target"));
    }

    @Test
    void nullScriptResultDoesNotReportSuccess() {
        assertFalse(switcher.renameIfSourceExists("source", "target"));
    }

    @Test
    void redisFailureIsNotSilentlyTreatedAsEmptyBucket() {
        IllegalStateException failure = new IllegalStateException("test Redis failure");
        when(template.execute(any(RedisScript.class), anyList())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> switcher.renameIfSourceExists("source", "target")));
    }

    @Test
    void emptyBucketsInAllScheduledTasksDoNotStartConsumersOrReadHashes() {
        RedisBucketSwitcher buckets = mock(RedisBucketSwitcher.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        PostMapper posts = mock(PostMapper.class);
        CommentMapper comments = mock(CommentMapper.class);
        SolutionMapper solutions = mock(SolutionMapper.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(buckets.switchBucket(anyString())).thenReturn(0L);
        LikeTask likes = new LikeTask(template, posts, comments, solutions, buckets, redisson, executor);
        SolutionLookTask views = new SolutionLookTask(template, buckets, solutions, redisson, executor);

        assertDoesNotThrow(likes::updatePostLike);
        assertDoesNotThrow(likes::updateCommentLike);
        assertDoesNotThrow(likes::updateSolutionLike);
        assertDoesNotThrow(views::updateSolutionLookCount);

        for (String prefix : List.of(RedisContext.LIKE_POST_KEY, RedisContext.LIKE_COMMENT_KEY,
                RedisContext.LIKE_SOLUTION_KEY, RedisContext.SOLUTION_LOOK_KEY)) {
            verify(buckets).renameIfSourceExists(eq(prefix + "-0"), startsWith(prefix + "-0:processing:"));
        }
        verify(lock, times(4)).unlock();
        verifyNoInteractions(template, posts, comments, solutions, executor);
    }

    // 仅连接显式提供的临时 Redis，普通单测不依赖本机 Redis 或线上环境。
    @Nested
    @EnabledIfEnvironmentVariable(named = "CODEWISE_BUCKET_TEST_REDIS_URL", matches = ".+")
    class RedisIntegration {
        private static RedissonClient client;
        private static RedisTemplate<String, Object> redis;
        private String prefix;
        private RedisBucketSwitcher realSwitcher;

        @BeforeAll
        static void connect() {
            Config config = new Config();
            config.useSingleServer().setAddress(System.getenv("CODEWISE_BUCKET_TEST_REDIS_URL"));
            client = Redisson.create(config);
            redis = new RedisConfig().redisTemplate(new RedissonConnectionFactory(client));
        }

        @AfterAll
        static void disconnect() {
            if (client != null) {
                client.shutdown();
            }
        }

        @BeforeEach
        void configure() {
            prefix = "bucket-test:" + UUID.randomUUID();
            realSwitcher = new RedisBucketSwitcher();
            ReflectionTestUtils.setField(realSwitcher, "redisTemplate", redis);
        }

        @Test
        void missingSourceReturnsFalseWithoutCreatingKeys() {
            assertFalse(realSwitcher.renameIfSourceExists(prefix, prefix + ":processing"));
            assertFalse(redis.hasKey(prefix));
            assertFalse(redis.hasKey(prefix + ":processing"));
        }

        @Test
        void existingHashIsMovedWithoutChangingContents() {
            redis.opsForHash().putAll(prefix, Map.of("10", 3L, "20", -1L));
            Map<Object, Object> before = redis.opsForHash().entries(prefix);

            assertTrue(realSwitcher.renameIfSourceExists(prefix, prefix + ":processing"));
            assertFalse(redis.hasKey(prefix));
            assertEquals(before, redis.opsForHash().entries(prefix + ":processing"));
        }

        @Test
        void existingTargetIsNotOverwrittenAndSourceIsPreserved() {
            redis.opsForHash().put(prefix, "10", 2L);
            redis.opsForHash().put(prefix + ":processing", "20", 4L);
            Map<Object, Object> source = redis.opsForHash().entries(prefix);
            Map<Object, Object> target = redis.opsForHash().entries(prefix + ":processing");

            assertFalse(realSwitcher.renameIfSourceExists(prefix, prefix + ":processing"));
            assertEquals(source, redis.opsForHash().entries(prefix));
            assertEquals(target, redis.opsForHash().entries(prefix + ":processing"));
        }

        @Test
        void repeatedRenameReportsSuccessOnlyOnce() {
            redis.opsForHash().put(prefix, "10", 2L);
            assertTrue(realSwitcher.renameIfSourceExists(prefix, prefix + ":processing"));
            assertFalse(realSwitcher.renameIfSourceExists(prefix, prefix + ":other"));
            assertFalse(redis.hasKey(prefix + ":other"));
        }

        @Test
        void emptyBucketCyclesRemainNormalAndNewWritesCanStillBeMoved() {
            String selector = prefix + ":selector";
            redis.opsForValue().set(selector, 0);
            for (int i = 0; i < 4; i++) {
                long oldBucket = realSwitcher.switchBucket(selector);
                assertEquals(i % 2, oldBucket);
                assertFalse(realSwitcher.renameIfSourceExists(prefix + "-" + oldBucket, prefix + ":empty:" + i));
            }
            redis.opsForHash().put(prefix + "-0", "10", 1L);
            assertEquals(0, realSwitcher.switchBucket(selector));
            assertTrue(realSwitcher.renameIfSourceExists(prefix + "-0", prefix + ":processing"));
            assertEquals(Map.of("10", 1), redis.opsForHash().entries(prefix + ":processing"));
        }

        @Test
        void concurrentRenamesMoveSourceExactlyOnceWithoutErrors() throws Exception {
            redis.opsForHash().put(prefix, "10", 5L);
            Map<Object, Object> expected = redis.opsForHash().entries(prefix);
            CountDownLatch ready = new CountDownLatch(32);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(32)) {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    String target = prefix + ":processing:" + i;
                    results.add(executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        return realSwitcher.renameIfSourceExists(prefix, target);
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                int successes = 0;
                for (int i = 0; i < results.size(); i++) {
                    if (results.get(i).get(10, TimeUnit.SECONDS)) {
                        successes++;
                        assertEquals(expected, redis.opsForHash().entries(prefix + ":processing:" + i));
                    } else {
                        assertFalse(redis.hasKey(prefix + ":processing:" + i));
                    }
                }
                assertEquals(1, successes);
                assertFalse(redis.hasKey(prefix));
            } finally {
                start.countDown();
            }
        }
    }
}
