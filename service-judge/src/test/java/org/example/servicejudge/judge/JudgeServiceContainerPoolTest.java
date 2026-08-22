package org.example.servicejudge.judge;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.HostConfig;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicejudge.vo.DockersStatusVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JudgeServiceContainerPoolTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void shouldBorrowFromTheMatchingLanguagePool() {
        JudgeService judgeService = createJudgeService(
                queueOf("java-1", "java-2"),
                queueOf("python-1")
        );

        assertEquals("java-1", borrow(judgeService, "JAVA"));
        assertEquals("python-1", borrow(judgeService, "python"));
        assertEquals("java-2", borrow(judgeService, "java"));
    }

    @Test
    void shouldAllowTwoJavaContainersToBeBorrowedConcurrently() throws Exception {
        JudgeService judgeService = createJudgeService(
                queueOf("java-1", "java-2"),
                queueOf("python-1")
        );
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> {
                start.await();
                return borrow(judgeService, "java");
            });
            var second = executor.submit(() -> {
                start.await();
                return borrow(judgeService, "java");
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
        JudgeService judgeService = createManagedJudgeService();
        BlockingQueue<String> javaPool = queueOf("java-idle");
        setLanguageState(
                judgeService,
                "java",
                javaPool,
                Set.of("java-idle", "java-busy"),
                2,
                3
        );

        DockersStatusVo javaStatus = judgeService.getDockers().stream()
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
        JudgeService judgeService = createManagedJudgeService();
        setLanguageState(
                judgeService,
                "java",
                queueOf("java-idle"),
                Set.of("java-idle", "java-busy"),
                2,
                0
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> judgeService.removeDocker("JAVA", "java-busy")
        );

        assertEquals("容器正在使用，不能删除", exception.getMessage());
    }

    @Test
    void shouldRemoveAnIdleContainerAndItsMetadata() {
        JudgeService judgeService = createManagedJudgeService();
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        ReflectionTestUtils.setField(judgeService, "dockerClient", dockerClient);
        BlockingQueue<String> javaPool = queueOf("java-1", "java-2");
        Set<String> allJavaIds = ConcurrentHashMap.newKeySet();
        allJavaIds.addAll(Set.of("java-1", "java-2"));
        setLanguageState(judgeService, "java", javaPool, allJavaIds, 2, 0);

        assertTrue(judgeService.removeDocker("java", "java-1"));

        assertFalse(javaPool.contains("java-1"));
        assertFalse(allJavaIds.contains("java-1"));
        assertEquals(1, configuredCounts(judgeService).get("java").get());
        verify(dockerClient).removeContainerCmd("java-1");
    }

    @Test
    void shouldAddAContainerToTheIdlePoolAndMetadata() {
        JudgeService judgeService = createManagedJudgeService();
        DockerClient dockerClient = mock(DockerClient.class);
        CreateContainerCmd createContainerCmd = mock(CreateContainerCmd.class);
        StartContainerCmd startContainerCmd = mock(StartContainerCmd.class);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createContainerCmd);
        when(createContainerCmd.withHostConfig(any(HostConfig.class))).thenReturn(createContainerCmd);
        when(createContainerCmd.withUser(anyString())).thenReturn(createContainerCmd);
        when(createContainerCmd.withWorkingDir(anyString())).thenReturn(createContainerCmd);
        when(createContainerCmd.withCmd(any(String[].class))).thenReturn(createContainerCmd);
        when(createContainerCmd.exec()).thenReturn(response);
        when(response.getId()).thenReturn("java-2");
        when(dockerClient.startContainerCmd("java-2")).thenReturn(startContainerCmd);
        ReflectionTestUtils.setField(judgeService, "dockerClient", dockerClient);
        BlockingQueue<String> javaPool = queueOf("java-1");
        Set<String> allJavaIds = ConcurrentHashMap.newKeySet();
        allJavaIds.add("java-1");
        setLanguageState(judgeService, "java", javaPool, allJavaIds, 1, 0);

        judgeService.addDocker("JAVA");

        assertTrue(javaPool.contains("java-2"));
        assertTrue(allJavaIds.contains("java-2"));
        assertEquals(2, configuredCounts(judgeService).get("java").get());
        verify(startContainerCmd).exec();
    }

    private JudgeService createJudgeService(
            BlockingQueue<String> javaPool,
            BlockingQueue<String> pythonPool
    ) {
        JudgeService judgeService = new JudgeService();
        @SuppressWarnings("unchecked")
        Map<String, BlockingQueue<String>> pools =
                (Map<String, BlockingQueue<String>>) ReflectionTestUtils.getField(judgeService, "containerPools");
        pools.put("java", javaPool);
        pools.put("python", pythonPool);
        return judgeService;
    }

    private JudgeService createManagedJudgeService() {
        JudgeService judgeService = new JudgeService();
        UserFeignClient userFeignClient = mock(UserFeignClient.class);
        UserDto admin = new UserDto();
        admin.setRoleId(2);
        UserContext.setUserId(1L);
        when(userFeignClient.getUserInfo(1L)).thenReturn(Result.success(admin));
        ReflectionTestUtils.setField(judgeService, "userFeignClient", userFeignClient);
        return judgeService;
    }

    private void setLanguageState(
            JudgeService judgeService,
            String language,
            BlockingQueue<String> idlePool,
            Set<String> allIds,
            int configuredCount,
            int waitingCount
    ) {
        pools(judgeService).put(language, idlePool);
        allIds(judgeService).put(language, allIds);
        configuredCounts(judgeService).put(language, new AtomicInteger(configuredCount));
        waitingCounts(judgeService).put(language, new AtomicInteger(waitingCount));
    }

    @SuppressWarnings("unchecked")
    private Map<String, BlockingQueue<String>> pools(JudgeService judgeService) {
        return (Map<String, BlockingQueue<String>>) ReflectionTestUtils.getField(judgeService, "containerPools");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Set<String>> allIds(JudgeService judgeService) {
        return (Map<String, Set<String>>) ReflectionTestUtils.getField(judgeService, "allContainerIds");
    }

    @SuppressWarnings("unchecked")
    private Map<String, AtomicInteger> configuredCounts(JudgeService judgeService) {
        return (Map<String, AtomicInteger>) ReflectionTestUtils.getField(judgeService, "configuredContainerCounts");
    }

    @SuppressWarnings("unchecked")
    private Map<String, AtomicInteger> waitingCounts(JudgeService judgeService) {
        return (Map<String, AtomicInteger>) ReflectionTestUtils.getField(judgeService, "waitingCounts");
    }

    private BlockingQueue<String> queueOf(String... containerIds) {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        queue.addAll(List.of(containerIds));
        return queue;
    }

    private String borrow(JudgeService judgeService, String language) {
        return ReflectionTestUtils.invokeMethod(judgeService, "borrowContainer", language);
    }
}
