package org.example.servicejudge.judge.container;

import com.github.dockerjava.api.DockerClient;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicejudge.judge.LanguageSpec;
import org.example.servicejudge.vo.DockersStatusVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 容器池管理器单元测试（自 {@code JudgeServiceContainerPoolTest} 迁移适配）：
 * 覆盖容器借还、并发借出、污染重建与增删容器元数据一致性。
 */
class ContainerPoolManagerTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void shouldBorrowFromTheMatchingLanguagePool() {
        ContainerPoolManager poolManager = createPoolManager(
                queueOf("java-1", "java-2"),
                queueOf("python-1")
        );

        assertEquals("java-1", poolManager.borrowContainer("JAVA"));
        assertEquals("python-1", poolManager.borrowContainer("python"));
        assertEquals("java-2", poolManager.borrowContainer("java"));
    }

    @Test
    void shouldAllowTwoJavaContainersToBeBorrowedConcurrently() throws Exception {
        ContainerPoolManager poolManager = createPoolManager(
                queueOf("java-1", "java-2"),
                queueOf("python-1")
        );
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> {
                start.await();
                return poolManager.borrowContainer("java");
            });
            var second = executor.submit(() -> {
                start.await();
                return poolManager.borrowContainer("java");
            });

            start.countDown();
            Set<String> borrowed = new HashSet<>();
            borrowed.add(first.get(1, TimeUnit.SECONDS));
            borrowed.add(second.get(1, TimeUnit.SECONDS));

            assertEquals(Set.of("java-1", "java-2"), borrowed);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void statusShouldNotMutateTheIdlePool() {
        ContainerPoolManager poolManager = createManagedPoolManager();
        BlockingQueue<String> javaPool = queueOf("java-idle");
        setLanguageState(
                poolManager,
                "java",
                javaPool,
                Set.of("java-idle", "java-busy"),
                2,
                3
        );

        DockersStatusVo javaStatus = poolManager.getDockers().stream()
                .filter(status -> "java".equals(status.getLanguage()))
                .findFirst()
                .orElseThrow();

        assertEquals(1, javaPool.size());
        assertEquals(List.of("java-idle"), javaStatus.getIdleContainerIds());
        assertEquals(List.of("java-busy"), javaStatus.getBusyContainerIds());
        assertEquals(2, javaStatus.getTotal());
        assertEquals(1, javaStatus.getIdle());
        assertEquals(1, javaStatus.getBusy());
        assertEquals(3, javaStatus.getWaiting());
    }

    @Test
    void shouldRejectRemovingABusyContainer() {
        ContainerPoolManager poolManager = createManagedPoolManager();
        setLanguageState(
                poolManager,
                "java",
                queueOf("java-idle"),
                Set.of("java-idle", "java-busy"),
                2,
                0
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> poolManager.removeDocker("JAVA", "java-busy")
        );

        assertEquals("容器正在使用，不能删除", exception.getMessage());
    }

    @Test
    void shouldRemoveAnIdleContainerAndItsMetadata() {
        ContainerPoolManager poolManager = createManagedPoolManager();
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        ReflectionTestUtils.setField(poolManager, "dockerClient", dockerClient);
        BlockingQueue<String> javaPool = queueOf("java-1", "java-2");
        Set<String> allJavaIds = ConcurrentHashMap.newKeySet();
        allJavaIds.addAll(Set.of("java-1", "java-2"));
        setLanguageState(poolManager, "java", javaPool, allJavaIds, 2, 0);

        assertTrue(poolManager.removeDocker("java", "java-1"));

        assertFalse(javaPool.contains("java-1"));
        assertFalse(allJavaIds.contains("java-1"));
        assertEquals(1, configuredCounts(poolManager).get("java").get());
        verify(dockerClient).removeContainerCmd("java-1");
    }

    @Test
    void shouldAddAContainerToTheIdlePoolAndMetadata() {
        ContainerPoolManager poolManager = createManagedPoolManager();
        DockerExecTemplate dockerExecTemplate = mock(DockerExecTemplate.class);
        when(dockerExecTemplate.createContainer(any(LanguageSpec.class))).thenReturn("java-2");
        ReflectionTestUtils.setField(poolManager, "dockerExecTemplate", dockerExecTemplate);
        BlockingQueue<String> javaPool = queueOf("java-1");
        Set<String> allJavaIds = ConcurrentHashMap.newKeySet();
        allJavaIds.add("java-1");
        setLanguageState(poolManager, "java", javaPool, allJavaIds, 1, 0);

        poolManager.addDocker("JAVA");

        assertTrue(javaPool.contains("java-2"));
        assertTrue(allJavaIds.contains("java-2"));
        assertEquals(2, configuredCounts(poolManager).get("java").get());
        verify(dockerExecTemplate).createContainer(any(LanguageSpec.class));
    }

    @Test
    void shouldReturnCleanContainerToIdlePoolAsIs() {
        ContainerPoolManager poolManager = createPoolManager(queueOf(), queueOf());
        DockerExecTemplate dockerExecTemplate = mock(DockerExecTemplate.class);
        when(dockerExecTemplate.cleanContainerWorkspace("java-1")).thenReturn(true);
        ReflectionTestUtils.setField(poolManager, "dockerExecTemplate", dockerExecTemplate);
        Set<String> allJavaIds = ConcurrentHashMap.newKeySet();
        allJavaIds.add("java-1");
        BlockingQueue<String> javaPool = queueOf();
        setLanguageState(poolManager, "java", javaPool, allJavaIds, 1, 0);

        poolManager.returnContainer("java", "java-1");

        assertFalse(poolManager.isTainted("java-1"));
        assertTrue(javaPool.contains("java-1"));
        verify(dockerExecTemplate).cleanContainerWorkspace("java-1");
    }

    @Test
    void shouldReplaceTaintedContainerOnReturn() {
        ContainerPoolManager poolManager = createPoolManager(queueOf(), queueOf());
        DockerExecTemplate dockerExecTemplate = mock(DockerExecTemplate.class);
        when(dockerExecTemplate.createContainer(any(LanguageSpec.class))).thenReturn("java-new");
        ReflectionTestUtils.setField(poolManager, "dockerExecTemplate", dockerExecTemplate);
        Set<String> allJavaIds = ConcurrentHashMap.newKeySet();
        allJavaIds.add("java-1");
        BlockingQueue<String> javaPool = queueOf();
        setLanguageState(poolManager, "java", javaPool, allJavaIds, 1, 0);

        poolManager.markTainted("java-1");
        poolManager.returnContainer("java", "java-1");

        // 污染容器被销毁并以新容器归还，旧 ID 从全量集合移除
        assertFalse(javaPool.contains("java-1"));
        assertTrue(javaPool.contains("java-new"));
        assertFalse(allJavaIds.contains("java-1"));
        assertTrue(allJavaIds.contains("java-new"));
        verify(dockerExecTemplate).removeContainerQuietly("java-1");
        verify(dockerExecTemplate).createContainer(any(LanguageSpec.class));
    }

    private ContainerPoolManager createPoolManager(
            BlockingQueue<String> javaPool,
            BlockingQueue<String> pythonPool
    ) {
        ContainerPoolManager poolManager = new ContainerPoolManager(
                mock(DockerClient.class),
                mock(UserFeignClient.class),
                mock(DockerExecTemplate.class));
        @SuppressWarnings("unchecked")
        Map<String, BlockingQueue<String>> pools =
                (Map<String, BlockingQueue<String>>) ReflectionTestUtils.getField(poolManager, "containerPools");
        pools.put("java", javaPool);
        pools.put("python", pythonPool);
        return poolManager;
    }

    private ContainerPoolManager createManagedPoolManager() {
        ContainerPoolManager poolManager = new ContainerPoolManager(
                mock(DockerClient.class),
                mock(UserFeignClient.class),
                mock(DockerExecTemplate.class));
        UserFeignClient userFeignClient = mock(UserFeignClient.class);
        UserDto admin = new UserDto();
        admin.setRoleId(2);
        UserContext.setUserId(1L);
        when(userFeignClient.getUserInfo(1L)).thenReturn(Result.success(admin));
        ReflectionTestUtils.setField(poolManager, "userFeignClient", userFeignClient);
        return poolManager;
    }

    private void setLanguageState(
            ContainerPoolManager poolManager,
            String language,
            BlockingQueue<String> idlePool,
            Set<String> allIds,
            int configuredCount,
            int waitingCount
    ) {
        pools(poolManager).put(language, idlePool);
        allIds(poolManager).put(language, allIds);
        configuredCounts(poolManager).put(language, new AtomicInteger(configuredCount));
        waitingCounts(poolManager).put(language, new AtomicInteger(waitingCount));
    }

    @SuppressWarnings("unchecked")
    private Map<String, BlockingQueue<String>> pools(ContainerPoolManager poolManager) {
        return (Map<String, BlockingQueue<String>>) ReflectionTestUtils.getField(poolManager, "containerPools");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Set<String>> allIds(ContainerPoolManager poolManager) {
        return (Map<String, Set<String>>) ReflectionTestUtils.getField(poolManager, "allContainerIds");
    }

    @SuppressWarnings("unchecked")
    private Map<String, AtomicInteger> configuredCounts(ContainerPoolManager poolManager) {
        return (Map<String, AtomicInteger>) ReflectionTestUtils.getField(poolManager, "configuredContainerCounts");
    }

    @SuppressWarnings("unchecked")
    private Map<String, AtomicInteger> waitingCounts(ContainerPoolManager poolManager) {
        return (Map<String, AtomicInteger>) ReflectionTestUtils.getField(poolManager, "waitingCounts");
    }

    private BlockingQueue<String> queueOf(String... containerIds) {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        queue.addAll(List.of(containerIds));
        return queue;
    }
}
