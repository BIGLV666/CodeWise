package org.example.servicecommunity.config;

import org.example.servicecommunity.service.LikeRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 点赞通知线程池装配测试：确认专用池可被 {@code @Qualifier} 解析、
 * 与桶消费池隔离，且 {@link LikeRecordService} 不会退回裸 {@code CompletableFuture.runAsync}。
 */
class AsyncThreadConfigTest {

    /** 用与 {@link LikeRecordService} 相同的方式（按名限定）注入，验证限定名可解析。 */
    @Component
    static class QualifierProbe {
        @Autowired
        @Qualifier("communityLikeNotifyExecutor")
        private ThreadPoolTaskExecutor likeNotifyExecutor;
    }

    @Test
    void likeNotifyExecutorIsResolvableByQualifierAndIsolatedFromBucketPool() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(AsyncThreadConfig.class, QualifierProbe.class)) {
            QualifierProbe probe = context.getBean(QualifierProbe.class);
            assertNotNull(probe.likeNotifyExecutor, "communityLikeNotifyExecutor 必须可按限定名注入");
            assertNotSame(context.getBean("communityBucketConsumerExecutor"), probe.likeNotifyExecutor,
                    "点赞通知不得复用桶消费线程池");
        }
    }

    @Test
    void likeNotifyExecutorRunsTasksOnDedicatedThreads() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(AsyncThreadConfig.class)) {
            ThreadPoolTaskExecutor executor = context.getBean("communityLikeNotifyExecutor",
                    ThreadPoolTaskExecutor.class);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> threadName = new AtomicReference<>();
            executor.execute(() -> {
                threadName.set(Thread.currentThread().getName());
                latch.countDown();
            });
            assertTrue(latch.await(5, TimeUnit.SECONDS), "任务应在专用池中执行完成");
            assertTrue(threadName.get().startsWith("community-like-notify-"),
                    "实际线程名=" + threadName.get());
        }
    }

    @Test
    void likeRecordServiceInjectsDedicatedExecutor() throws Exception {
        Field field = LikeRecordService.class.getDeclaredField("likeNotifyExecutor");
        Qualifier qualifier = field.getAnnotation(Qualifier.class);
        assertNotNull(qualifier, "点赞通知必须显式指定专用线程池，避免退回 ForkJoinPool.commonPool");
        assertEquals("communityLikeNotifyExecutor", qualifier.value());
        assertEquals(ThreadPoolTaskExecutor.class, field.getType());
    }
}
