package org.example.serviceai.ai.pool;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.ai.provider.AiProvider;
import org.example.serviceai.config.AiConfig;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link AiPool} 的默认实现：维护轮转指针、健康状态与失败计数。
 *
 * <p>状态机逻辑逐字沿用改造前的 {@code AIServiceManager}——那套行为被 400 多行测试覆盖，
 * 本次只把它从「装配 + 轮转 + 健康 + 记账」的大类里剥出来，「装配」交给
 * {@link AiServiceFactory}。</p>
 *
 * <p>关键状态都在并发容器里、轮转指针是 {@link AtomicInteger}，热路径不加锁：
 * 高并发下允许短暂选到稍旧的端点，但不会串参数、不会死锁。</p>
 */
@Service
@Slf4j
public class DefaultAiPool implements AiPool {

    private static final int MAX_FAIL_COUNT = 3;
    private static final long UNAVAILABLE_CHECK_INTERVAL_MILLIS = 30_000L;
    private static final long AVAILABLE_CHECK_INTERVAL_MILLIS = 180_000L;
    private static final long HEALTH_CHECK_PERIOD_SECONDS = 30L;

    private final AiServiceFactory factory;
    private final AiConfig aiConfig;

    public DefaultAiPool(AiServiceFactory factory, AiConfig aiConfig) {
        this.factory = factory;
        this.aiConfig = aiConfig;
    }

    /** 自动轮转集合（按优先级排序）。 */
    private List<AiProvider> autoRotation = List.of();

    /** 按<b>身份</b>索引：全部已创建端点 + 容器内端点 Bean，含未参与轮转的（如 ollama）。 */
    private final Map<String, AiProvider> byIdentity = new ConcurrentHashMap<>();

    /**
     * 按<b>模型</b>索引：模型名 → 声明了它的端点（只含参与轮转的 enabled 端点，按 priority 升序）。
     *
     * <p>与 {@link #byIdentity} 是两套键空间：身份回答「这个端点是谁」，模型回答
     * 「这个能力谁能提供」。一个端点可声明多个模型，一个模型也可被多个端点提供。</p>
     */
    private Map<String, List<AiProvider>> byModel = Map.of();

    /** 当前使用的端点索引。 */
    private final AtomicInteger currentIndex = new AtomicInteger(0);

    private final Map<String, Boolean> healthCache = new ConcurrentHashMap<>();
    private final Map<String, Long> nextHealthCheckAt = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failCount = new ConcurrentHashMap<>();

    private final ScheduledExecutorService healthCheckExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "AI-Service-Health-Checker");
                thread.setDaemon(true);
                return thread;
            });

    @PostConstruct
    public void init() {
        // 先登记「按身份可借用」的全部端点（含不参与轮转的 Bean 端点），
        // 这样即便 ai.models 一条都没配，指定端点的路径（如 ollama 摘要）仍然可用。
        List<AiProvider> configured = factory.createConfiguredProviders();
        registerByIdentity(configured);
        registerByIdentity(factory.createBeanProviders());

        if (configured.isEmpty()) {
            log.error("没有可用的AI服务");
            return;
        }

        // 两个集合，用途不同：
        //   routable —— 进模型索引（"这个能力谁能提供"）：enabled 即可
        //   rotation —— 进 AUTO 轮转与健康探测：还要 auto-rotate
        // 于是"只用于指定模型、不参与 AUTO 轮转"可以表达——例如 OpenCode Go 这类
        // 每次请求都要求会话头的端点，AUTO 的调用方没有会话概念，打过去必 400。
        List<AiProvider> routable = new ArrayList<>();
        List<AiProvider> rotation = new ArrayList<>();
        for (AiProvider provider : configured) {
            if (!provider.enabled()) {
                log.info("AI 服务已由配置关闭（enabled=false），既不轮转也不进模型列表: {}", provider.name());
                continue;
            }
            routable.add(provider);
            if (provider.autoRotate()) {
                rotation.add(provider);
            } else {
                log.info("AI 服务只用于指定模型，不参与 AUTO 轮转与探测: {}", provider.name());
            }
        }
        routable.sort(Comparator.comparingInt(AiProvider::priority));
        rotation.sort(Comparator.comparingInt(AiProvider::priority));
        autoRotation = List.copyOf(rotation);

        if (autoRotation.isEmpty()) {
            log.error("没有任何端点参与 AUTO 轮转（检查 ai.models[].enabled / auto-rotate），"
                    + "AUTO 那条路将不可用");
        }

        byModel = buildModelIndex(routable);

        for (AiProvider provider : autoRotation) {
            healthCache.put(provider.name(), false);
            failCount.put(provider.name(), new AtomicInteger(0));
            nextHealthCheckAt.put(provider.name(), 0L);
            log.info("注册AI服务: {} (优先级: {})", provider.name(), provider.priority());
        }

        if (aiConfig.isHealthCheckEnabled()) {
            startHealthCheck();
        } else {
            // 探测定时任务已关闭：乐观地把全部端点标记为可用，交给调用时的失败计数
            // （MAX_FAIL_COUNT）与 switchToNext 按需发现故障。若仍保持初始的 false，
            // borrowAuto() 会因为「没有任何健康端点」而直接返回 null（降级）。
            for (AiProvider provider : autoRotation) {
                healthCache.put(provider.name(), true);
            }
            log.info("AI 健康探测定时任务已关闭（ai.health-check.enabled=false），改为调用时按需发现故障");
        }

        log.info("AI 端点池初始化完成：自动轮转 {} 个，可路由端点 {} 个，按身份可用 {} 个，可用模型 {} 个",
                autoRotation.size(), routable.size(), byIdentity.size(), byModel.size());
    }

    /** 每个模型对应一个按 priority 升序的端点列表——同模型多端点时用随机取做负载均衡。 */
    private Map<String, List<AiProvider>> buildModelIndex(List<AiProvider> rotation) {
        Map<String, List<AiProvider>> index = new LinkedHashMap<>();
        for (AiProvider provider : rotation) {
            for (String model : provider.servedModels()) {
                index.computeIfAbsent(model, key -> new ArrayList<>()).add(provider);
            }
        }
        Map<String, List<AiProvider>> immutable = new LinkedHashMap<>();
        index.forEach((model, providers) -> immutable.put(model, List.copyOf(providers)));
        return Collections.unmodifiableMap(immutable);
    }

    private void registerByIdentity(List<AiProvider> providers) {
        if (providers == null) {
            return;
        }
        for (AiProvider provider : providers) {
            if (provider != null && provider.name() != null) {
                byIdentity.put(provider.name(), provider);
            }
        }
    }

    @Override
    public AiProvider borrowAuto() {
        if (autoRotation.isEmpty()) {
            throw new BizException(AiErrorCode.AI_SERVICE_NOT_READY);
        }

        int startIndex = currentIndex.get();
        int size = autoRotation.size();

        for (int attempts = 0; attempts < size; attempts++) {
            int index = (startIndex + attempts) % size;
            AiProvider provider = autoRotation.get(index);
            if (Boolean.TRUE.equals(healthCache.get(provider.name()))) {
                currentIndex.set(index);
                log.info("使用AI服务: {}", provider.name());
                return provider;
            }
        }

        // 所有端点都不可用：降级返回 null，由调用方决定是否失败
        return null;
    }

    @Override
    public AiProvider borrowByIdentity(String identity) {
        return identity == null ? null : byIdentity.get(identity);
    }

    @Override
    public AiProvider borrowForModel(String modelName) {
        if (modelName == null) {
            return null;
        }
        List<AiProvider> candidates = byModel.get(modelName);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        // 随机取一个：同模型多端点时在端点间做负载均衡；单个候选时就是它自己。
        // 刻意不看健康状态——指定模型的失败不兜底，也不该把模型级故障连坐到 AUTO 的健康记账上。
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    @Override
    public List<String> platformModels() {
        return List.copyOf(new TreeSet<>(byModel.keySet()));
    }

    @Override
    public void reportFailure(AiProvider provider) {
        if (provider == null) {
            return;
        }
        String name = provider.name();
        AtomicInteger count = failCount.get(name);
        if (count == null) {
            // 不在自动轮转集合里的端点（如指定借用的 ollama）不参与健康记账
            return;
        }
        int fails = count.incrementAndGet();
        log.warn("服务 {} 失败次数: {}/{}", name, fails, MAX_FAIL_COUNT);

        if (fails >= MAX_FAIL_COUNT) {
            healthCache.put(name, false);
            nextHealthCheckAt.put(name, System.currentTimeMillis() + UNAVAILABLE_CHECK_INTERVAL_MILLIS);
            log.warn("服务 {} 连续失败{}次，标记为不可用", name, MAX_FAIL_COUNT);
            switchToNext();
        }
    }

    @Override
    public void reportSuccess(AiProvider provider) {
        if (provider == null) {
            return;
        }
        String name = provider.name();
        AtomicInteger count = failCount.get(name);
        if (count != null) {
            count.set(0);
            healthCache.put(name, true);
        }
    }

    @Override
    public void switchToNext() {
        int size = autoRotation.size();
        if (size == 0) {
            return;
        }
        int nextIndex = (currentIndex.get() + 1) % size;
        currentIndex.set(nextIndex);
        log.info("切换到下一个服务: {}", autoRotation.get(nextIndex).name());
    }

    @Override
    public boolean switchTo(String identity) {
        for (int i = 0; i < autoRotation.size(); i++) {
            if (autoRotation.get(i).name().equals(identity)) {
                currentIndex.set(i);
                log.info("手动切换到服务: {}", identity);
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<String, Boolean> status() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        for (AiProvider provider : autoRotation) {
            status.put(provider.name(), healthCache.getOrDefault(provider.name(), false));
        }
        return status;
    }

    private void startHealthCheck() {
        healthCheckExecutor.scheduleAtFixedRate(
                this::checkDueProviders, 0, HEALTH_CHECK_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    private void checkDueProviders() {
        long now = System.currentTimeMillis();
        for (AiProvider provider : autoRotation) {
            String name = provider.name();
            long nextCheck = nextHealthCheckAt.getOrDefault(name, 0L);
            if (nextCheck > now) {
                continue;
            }

            boolean wasHealthy = Boolean.TRUE.equals(healthCache.get(name));
            boolean available;
            try {
                available = provider.probe();
            } catch (Exception exception) {
                available = false;
                log.warn("AI服务探测异常，model={}, error={}", name, exception.getMessage(), exception);
            }

            healthCache.put(name, available);
            if (available) {
                failCount.get(name).set(0);
            }
            nextHealthCheckAt.put(name, now + (available
                    ? AVAILABLE_CHECK_INTERVAL_MILLIS
                    : UNAVAILABLE_CHECK_INTERVAL_MILLIS));

            if (wasHealthy != available) {
                log.info("AI服务健康状态变化，model={}, available={}, nextCheckInSeconds={}",
                        name, available, available ? 180 : 30);
            } else {
                log.debug("AI服务探测完成，model={}, available={}, nextCheckInSeconds={}",
                        name, available, available ? 180 : 30);
            }
        }
    }

    @PreDestroy
    public void stopHealthCheck() {
        healthCheckExecutor.shutdownNow();
    }
}
