package org.example.servicejudge.judge.container;

import com.github.dockerjava.api.DockerClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicejudge.judge.LanguageSpec;
import org.example.servicejudge.vo.DockersStatusVo;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 判题容器池管理器：按语言维护「空闲队列 + 全量集合 + 等待计数 + 容量计数」，
 * 负责容器借出/归还/污染重建与生命周期管理（原 {@code JudgeService} 迁出）。
 *
 * <p>借出超时上限 {@value #CONTAINER_BORROW_TIMEOUT_SECONDS} 秒；归还时若容器
 * 被标记污染（执行超时/异常，见 {@code DockerExecTemplate} 的 taint 回调）或
 * 工作区清理失败，则销毁原容器并重建一个同语言容器补齐容量。</p>
 */
@Component
@Slf4j
public class ContainerPoolManager {

    /** 借出容器时的最长等待秒数。 */
    private static final long CONTAINER_BORROW_TIMEOUT_SECONDS = 10L;

    /** 各语言初始预创建容器数量。 */
    private static final Map<LanguageSpec, Integer> INITIAL_CONTAINER_COUNTS = Map.of(
            LanguageSpec.JAVA, 2,
            LanguageSpec.PYTHON, 1,
            LanguageSpec.CPP, 1,
            LanguageSpec.C, 1
    );

    private final DockerClient dockerClient;
    private final UserFeignClient userFeignClient;
    private final DockerExecTemplate dockerExecTemplate;

    /** 各语言空闲容器队列。 */
    private final Map<String, BlockingQueue<String>> containerPools = new ConcurrentHashMap<>();
    /** 各语言正在等待借出的请求数（监控用）。 */
    private final Map<String, AtomicInteger> waitingCounts = new ConcurrentHashMap<>();
    /** 各语言全部容器 ID（含借出中的）。 */
    private final Map<String, Set<String>> allContainerIds = new ConcurrentHashMap<>();
    /** 各语言配置容量（含被污染待重建的）。 */
    private final Map<String, AtomicInteger> configuredContainerCounts = new ConcurrentHashMap<>();
    /** 各语言池的结构性锁（扩容/缩容时持有）。 */
    private final Map<String, Object> containerPoolLocks = new ConcurrentHashMap<>();
    /** 执行超时或进程树清理失败的容器不能再次进入空闲池。 */
    private final Set<String> taintedContainerIds = ConcurrentHashMap.newKeySet();

    public ContainerPoolManager(
            DockerClient dockerClient,
            UserFeignClient userFeignClient,
            DockerExecTemplate dockerExecTemplate
    ) {
        this.dockerClient = dockerClient;
        this.userFeignClient = userFeignClient;
        this.dockerExecTemplate = dockerExecTemplate;
    }

    // ========== 监控与管理接口（供 JudgeService 门面转发） ==========

    /**
     * 查询各语言容器池状态（不改变池内容）。
     *
     * @return 每种语言一条状态记录
     * @throws IllegalArgumentException 调用方未登录、用户不存在或非管理员
     */
    public List<DockersStatusVo> getDockers() {
        checkAdmin();
        List<DockersStatusVo> result = new ArrayList<>();
        for (LanguageSpec spec : LanguageSpec.values()) {
            Set<String> allIds = new HashSet<>(allContainerIds.getOrDefault(spec.language(), Set.of()));
            List<String> idleIds = containerPools.getOrDefault(spec.language(), new LinkedBlockingQueue<>())
                    .stream()
                    .filter(allIds::contains)
                    .sorted()
                    .toList();
            Set<String> busyIdSet = new HashSet<>(allIds);
            idleIds.forEach(busyIdSet::remove);
            List<String> busyIds = busyIdSet.stream().sorted().toList();

            DockersStatusVo status = new DockersStatusVo();
            status.setLanguage(spec.language());
            status.setTotal(allIds.size());
            status.setIdle(idleIds.size());
            status.setBusy(busyIds.size());
            status.setWaiting(waitingCounts.getOrDefault(spec.language(), new AtomicInteger()).get());
            status.setConfiguredCapacity(
                    configuredContainerCounts.getOrDefault(spec.language(), new AtomicInteger()).get());
            status.setIdleContainerIds(idleIds);
            status.setBusyContainerIds(busyIds);
            result.add(status);
        }
        return result;
    }

    /**
     * 为指定语言扩容一个容器。
     *
     * @param language 语言标识
     * @throws IllegalArgumentException 语言不支持或调用方非管理员
     * @throws IllegalStateException    容器创建/入池失败
     */
    public void addDocker(String language) {
        checkAdmin();
        LanguageSpec spec = LanguageSpec.of(language);
        if (spec == null) {
            throw new IllegalArgumentException("不支持的语言: " + language);
        }

        String containerId = null;
        try {
            containerId = dockerExecTemplate.createContainer(spec);
            synchronized (containerPoolLocks.computeIfAbsent(spec.language(), key -> new Object())) {
                BlockingQueue<String> idlePool = containerPools.computeIfAbsent(
                        spec.language(),
                        key -> new LinkedBlockingQueue<>()
                );
                if (!idlePool.offer(containerId)) {
                    throw new IllegalStateException("容器空闲队列已满");
                }
                allContainerIds.computeIfAbsent(spec.language(), key -> ConcurrentHashMap.newKeySet()).add(containerId);
                configuredContainerCounts.computeIfAbsent(spec.language(), key -> new AtomicInteger())
                        .incrementAndGet();
            }
            log.info("创建 {} 容器成功: {}", spec.language(), containerId);
        } catch (Exception exception) {
            log.error("创建 {} 容器失败", spec.language(), exception);
            if (containerId != null) {
                BlockingQueue<String> idlePool = containerPools.get(spec.language());
                if (idlePool != null) {
                    idlePool.remove(containerId);
                }
                Set<String> containerIds = allContainerIds.get(spec.language());
                if (containerIds != null) {
                    containerIds.remove(containerId);
                }
                dockerExecTemplate.removeContainerQuietly(containerId);
            }
            throw new IllegalStateException("创建 " + spec.language() + " 容器失败", exception);
        }
    }

    /**
     * 删除指定语言的空闲容器（每种语言至少保留一个）。
     *
     * @param language    语言标识
     * @param containerId 容器 ID
     * @return 删除成功返回 true
     * @throws IllegalArgumentException 容器不存在/参数非法或调用方非管理员
     * @throws IllegalStateException    容器使用中、最后一个容器或物理删除失败
     */
    public boolean removeDocker(String language, String containerId) {
        checkAdmin();
        LanguageSpec spec = LanguageSpec.of(language);
        if (spec == null) {
            throw new IllegalArgumentException("不支持的语言: " + language);
        }
        if (containerId == null || containerId.isBlank()) {
            throw new IllegalArgumentException("容器 ID 不能为空");
        }

        BlockingQueue<String> idlePool = containerPools.get(spec.language());
        Set<String> allIds = allContainerIds.get(spec.language());
        if (idlePool == null || allIds == null || !allIds.contains(containerId)) {
            throw new IllegalArgumentException("该容器不存在");
        }

        synchronized (containerPoolLocks.computeIfAbsent(spec.language(), key -> new Object())) {
            if (allIds.size() <= 1) {
                throw new IllegalStateException("每种语言至少保留一个容器");
            }
            if (!idlePool.remove(containerId)) {
                throw new IllegalStateException("容器正在使用，不能删除");
            }
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                allIds.remove(containerId);
                configuredContainerCounts.get(spec.language()).decrementAndGet();
                log.info("删除 {} 容器成功: {}", spec.language(), containerId);
                return true;
            } catch (Exception exception) {
                idlePool.offer(containerId);
                throw new IllegalStateException("删除容器失败: " + containerId, exception);
            }
        }
    }

    /**
     * 校验当前登录用户为管理员（roleId==2），否则抛出非法参数异常。
     * 用户身份取自 {@link UserContext}，不信任客户端传入的 userId。
     */
    private void checkAdmin() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("用户未登录");
        }
        Result<UserDto> response = userFeignClient.getUserInfo(userId);
        if (response == null || response.getData() == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (!Objects.equals(response.getData().getRoleId(), 2)) {
            throw new IllegalArgumentException("无权管理判题容器");
        }
    }

    // ========== 借还与污染 ==========

    /**
     * 从指定语言的空闲池借出一个容器。
     *
     * @param language 语言标识（大小写不敏感）
     * @return 容器 ID；语言不支持或 {@value #CONTAINER_BORROW_TIMEOUT_SECONDS} 秒内
     *         无空闲容器（含等待被中断）时返回 null
     */
    public String borrowContainer(String language) {
        LanguageSpec spec = LanguageSpec.of(language);
        if (spec == null) {
            return null;
        }
        BlockingQueue<String> pool = containerPools.get(spec.language());
        if (pool == null) {
            return null;
        }
        String containerId = pool.poll();
        if (containerId != null) {
            return containerId;
        }
        AtomicInteger waiting = waitingCounts.computeIfAbsent(
                spec.language(),
                key -> new AtomicInteger()
        );
        try {
            waiting.incrementAndGet();
            return pool.poll(CONTAINER_BORROW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("等待空闲 {} 容器时线程被中断", spec.language());
            return null;
        } finally {
            waiting.decrementAndGet();
        }
    }

    /**
     * 归还容器：污染容器或工作区清理失败时销毁重建，否则放回空闲池。
     *
     * @param language    语言标识（用于定位池，未知语言只记录告警）
     * @param containerId 容器 ID，null 时直接返回
     */
    public void returnContainer(String language, String containerId) {
        if (containerId == null) {
            return;
        }
        LanguageSpec spec = LanguageSpec.of(language);
        BlockingQueue<String> pool = spec == null ? null : containerPools.get(spec.language());
        if (pool == null) {
            log.warn("容器归还失败，语言池不存在: language={}, containerId={}", language, containerId);
            return;
        }
        String availableContainerId = containerId;
        if (taintedContainerIds.remove(containerId) || !dockerExecTemplate.cleanContainerWorkspace(containerId)) {
            availableContainerId = replaceContainer(spec, containerId);
        }
        if (availableContainerId != null && !pool.offer(availableContainerId)) {
            log.warn("容器归还失败，容器池已满: language={}, containerId={}", spec.language(), containerId);
        }
    }

    /**
     * 将容器标记为污染：归还时销毁重建，不再复用。
     *
     * @param containerId 容器 ID
     */
    public void markTainted(String containerId) {
        taintedContainerIds.add(containerId);
    }

    /**
     * 判断容器是否已被标记污染。
     *
     * @param containerId 容器 ID
     * @return 已污染返回 true
     */
    public boolean isTainted(String containerId) {
        return taintedContainerIds.contains(containerId);
    }

    /**
     * 销毁指定容器并重建同语言容器补齐容量。
     *
     * @param spec        语言规格
     * @param containerId 待替换容器 ID
     * @return 新容器 ID；重建失败返回 null（容量暂时减少）
     */
    private String replaceContainer(LanguageSpec spec, String containerId) {
        Set<String> containerIds = allContainerIds.computeIfAbsent(
                spec.language(),
                key -> ConcurrentHashMap.newKeySet()
        );
        containerIds.remove(containerId);
        dockerExecTemplate.removeContainerQuietly(containerId);
        try {
            String replacementId = dockerExecTemplate.createContainer(spec);
            containerIds.add(replacementId);
            log.info("已重建 {} 容器: old={}, new={}", spec.language(), containerId, replacementId);
            return replacementId;
        } catch (Exception exception) {
            log.error("重建 {} 容器失败，容器池容量暂时减少", spec.language(), exception);
            return null;
        }
    }

    // ========== 生命周期 ==========

    /**
     * 启动时按 {@link #INITIAL_CONTAINER_COUNTS} 预创建各语言容器池。
     * 单个容器创建失败只记录日志，不影响其他语言初始化。
     */
    @PostConstruct
    public void init() {
        log.info("开始初始化容器池...");
        for (LanguageSpec spec : LanguageSpec.values()) {
            int containerCount = INITIAL_CONTAINER_COUNTS.getOrDefault(spec, 1);
            BlockingQueue<String> pool = new LinkedBlockingQueue<>();
            Set<String> containerIds = ConcurrentHashMap.newKeySet();
            containerPools.put(spec.language(), pool);
            allContainerIds.put(spec.language(), containerIds);
            waitingCounts.put(spec.language(), new AtomicInteger());
            configuredContainerCounts.put(spec.language(), new AtomicInteger(containerCount));
            containerPoolLocks.put(spec.language(), new Object());
            for (int index = 0; index < containerCount; index++) {
                try {
                    String containerId = dockerExecTemplate.createContainer(spec);
                    containerIds.add(containerId);
                    pool.offer(containerId);
                    log.info("预创建 {} 容器成功: {}", spec.language(), containerId);
                } catch (Exception exception) {
                    log.error("创建 {} 容器失败", spec.language(), exception);
                }
            }
        }
        int total = allContainerIds.values().stream().mapToInt(Set::size).sum();
        log.info("容器池初始化完成，共 {} 个容器", total);
    }

    /**
     * 停止时强制删除全部容器并清空池状态。
     */
    @PreDestroy
    public void destroy() {
        log.info("开始清理容器池...");
        for (Set<String> containerIds : allContainerIds.values()) {
            for (String containerId : containerIds) {
                dockerExecTemplate.removeContainerQuietly(containerId);
            }
        }
        containerPools.clear();
        allContainerIds.clear();
        waitingCounts.clear();
        configuredContainerCounts.clear();
        containerPoolLocks.clear();
        taintedContainerIds.clear();
    }
}
