package com.example.servicequiznest.service;

import com.example.servicequiznest.support.AbstractGroupIntegrationTest;
import com.example.servicequiznest.support.TestContainers;
import org.example.serviceapi.exception.BizException;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退出/晋升的并发压测：验证「每组恰好一个 ROOT」等不变量在竞争下不被破坏。
 *
 * <p>真 MySQL 8.4 + 真实事务，用线程池并发调用 {@link GroupService#leaveGroup}。
 * 关注的失败模式：双 ROOT、root 指针与 ROOT 成员不一致、member_count 漂移、孤儿组、死锁。</p>
 */
class GroupLeaveGroupConcurrencyTest extends AbstractGroupIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = TestContainers.newMySql();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        TestContainers.registerMySql(registry, MYSQL);
    }

    /** 一次并发压测的结果汇总 */
    private record LoadResult(int total, int success, List<Throwable> failures) {
    }

    private LoadResult runConcurrently(List<Runnable> tasks, int threads) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks.size());
        AtomicInteger success = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        for (Runnable task : tasks) {
            pool.submit(() -> {
                try {
                    start.await();
                    task.run();
                    success.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    UserContext.clear();
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).as("并发任务应在 120s 内跑完").isTrue();
        pool.shutdownNow();
        return new LoadResult(tasks.size(), success.get(), new ArrayList<>(failures));
    }

    private Runnable leaveAs(long userId, long groupId, Long designatedHeir) {
        return () -> {
            UserContext.setUserId(userId);
            groupService.leaveGroup(groupId, designatedHeir);
        };
    }

    @Test
    @DisplayName("压测：同一 root 50 并发退出，只有 1 次成功且最终只有一个 ROOT")
    void concurrentRootLeave_sameRoot_exactlyOneWinner() throws InterruptedException {
        insertGroup(100L, ROOT, 3);
        insertMember(ROOT, 100L, "ROOT", "APPROVED");
        insertMember(ADMIN, 100L, "ADMIN", "APPROVED");
        insertMember(MEMBER, 100L, "MEMBER", "APPROVED");

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(leaveAs(ROOT, 100L, ADMIN));
        }
        LoadResult result = runConcurrently(tasks, 32);

        assertThat(result.success()).as("并发退出只能有 1 次成功").isEqualTo(1);
        assertThat(result.failures()).allSatisfy(t -> assertThat(t).isInstanceOf(BizException.class));
        assertThat(ownerOf(100L)).isEqualTo(ADMIN);
        assertInvariants(100L);
    }

    @Test
    @DisplayName("压测：30 组同时换 root（每组 1 个线程），互不干扰且不变量全部成立")
    void concurrentRootLeave_differentGroups_allSucceed() throws InterruptedException {
        List<Runnable> tasks = new ArrayList<>();
        for (int g = 0; g < 30; g++) {
            long groupId = 200L + g;
            // 5 名 APPROVED 成员（含 root）+ 1 条 PENDING 占位：member_count 只算 APPROVED
            insertGroup(groupId, ROOT, 5);
            insertMember(ROOT, groupId, "ROOT", "APPROVED");
            insertMember(ADMIN, groupId, "ADMIN", "APPROVED");
            insertMember(MEMBER, groupId, "MEMBER", "APPROVED");
            insertMember(MEMBER2, groupId, "MEMBER", "APPROVED");
            insertMember(PENDING, groupId, "MEMBER", "PENDING");
            insertMember(1006L + g, groupId, "MEMBER", "APPROVED");
            tasks.add(leaveAs(ROOT, groupId, null));
        }
        LoadResult result = runConcurrently(tasks, 30);

        assertThat(result.failures()).as("不同小组之间不应互相干扰").isEmpty();
        assertThat(result.success()).isEqualTo(30);
        for (int g = 0; g < 30; g++) {
            long groupId = 200L + g;
            assertThat(ownerOf(groupId)).as("小组 %s 应换由管理员继承", groupId).isEqualTo(ADMIN);
            assertInvariants(groupId);
        }
    }

    @Test
    @DisplayName("压测：混合并发（root 退出 + 管理员/成员退出，30 组 × 6 线程）不变量恒成立")
    void mixedConcurrentOperations_keepInvariants() throws InterruptedException {
        List<Runnable> tasks = new ArrayList<>();
        for (int g = 0; g < 30; g++) {
            long groupId = 300L + g;
            // 5 名 APPROVED 成员（含 root）+ 1 条 PENDING 占位：member_count 只算 APPROVED
            insertGroup(groupId, ROOT, 5);
            insertMember(ROOT, groupId, "ROOT", "APPROVED");
            insertMember(ADMIN, groupId, "ADMIN", "APPROVED");
            insertMember(MEMBER, groupId, "MEMBER", "APPROVED");
            insertMember(MEMBER2, groupId, "MEMBER", "APPROVED");
            insertMember(PENDING, groupId, "MEMBER", "PENDING");
            insertMember(1006L + g, groupId, "MEMBER", "APPROVED");
            // root 退出（自动晋升）与管理员/成员退出同时发生
            tasks.add(leaveAs(ROOT, groupId, null));
            tasks.add(leaveAs(ADMIN, groupId, null));
            tasks.add(leaveAs(MEMBER, groupId, null));
            tasks.add(leaveAs(MEMBER2, groupId, null));
            tasks.add(leaveAs(1006L + g, groupId, null));
            tasks.add(leaveAs(PENDING, groupId, null));
        }
        long begin = System.currentTimeMillis();
        LoadResult result = runConcurrently(tasks, 48);
        long cost = System.currentTimeMillis() - begin;

        assertThat(result.failures())
                .as("只允许业务异常（并发下 CAS 失败/记录已删），不允许死锁或 SQL 错误：%s", result.failures())
                .allSatisfy(t -> assertThat(t).isInstanceOf(BizException.class));
        assertThat(result.success()).as("大多数请求应成功（%s/%s）", result.success(), result.total())
                .isGreaterThan(result.total() / 2);

        for (int g = 0; g < 30; g++) {
            long groupId = 300L + g;
            if (groupCount(groupId) == 0) {
                assertGroupPurged(groupId);
            } else {
                assertInvariants(groupId);
            }
        }
        System.out.printf("[load] 混合并发 %d 次调用，成功 %d 次，失败 %d 次，耗时 %d ms%n",
                result.total(), result.success(), result.failures().size(), cost);
    }

    @Test
    @DisplayName("压测：root 与“指定继承人”同时退出，不产生双 ROOT 或孤儿组")
    void concurrentRootLeave_andHeirLeave_noPartialState() throws InterruptedException {
        for (int round = 0; round < 20; round++) {
            long groupId = 400L + round;
            insertGroup(groupId, ROOT, 2);
            insertMember(ROOT, groupId, "ROOT", "APPROVED");
            insertMember(MEMBER, groupId, "MEMBER", "APPROVED");

            List<Runnable> tasks = List.of(leaveAs(ROOT, groupId, MEMBER), leaveAs(MEMBER, groupId, null));
            LoadResult result = runConcurrently(tasks, 2);

            assertThat(result.failures()).allSatisfy(t -> assertThat(t).isInstanceOf(BizException.class));
            if (groupCount(groupId) == 0) {
                assertGroupPurged(groupId);
            } else {
                assertInvariants(groupId);
            }
        }
    }
}