package org.example.serviceai.AIServiceManager;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceai.intifer.CallAi;
import org.example.serviceapi.exception.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class AIServiceManager {
    @Autowired(required = false)
    private List<CallAi> aiServices;

    /**
     * 是否启用 Provider 健康探测定时任务。
     *
     * <p>由 Nacos 下发 {@code ai.health-check.enabled}（默认 true）。置为 false 后不再起
     * 后台周期探测线程，改为在真实调用时靠失败计数与切换机制按需发现故障——这样未部署的
     * Provider（如 Ollama）不会持续刷连接失败日志。</p>
     */
    @Value("${ai.health-check.enabled:true}")
    private boolean healthCheckEnabled;

    // 所有服务（按优先级排序）
    private List<CallAi> availableServices = new ArrayList<>();

    // 当前使用的服务索引
    private final AtomicInteger currentIndex = new AtomicInteger(0);

    // 服务健康状态缓存
    private final Map<String, Boolean> healthCache = new ConcurrentHashMap<>();

    // 下一次健康探测时间
    private final Map<String, Long> nextHealthCheckAt = new ConcurrentHashMap<>();

    // 失败计数
    private final Map<String, AtomicInteger> failCount = new ConcurrentHashMap<>();

    // 最大失败次数
    private static final int MAX_FAIL_COUNT = 3;
    private static final long UNAVAILABLE_CHECK_INTERVAL_MILLIS = 30_000L;
    private static final long AVAILABLE_CHECK_INTERVAL_MILLIS = 180_000L;
    private final ScheduledExecutorService healthCheckExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "AI-Service-Health-Checker");
                thread.setDaemon(true);
                return thread;
            });

    @PostConstruct
    public void init() {
        if (aiServices == null || aiServices.isEmpty()) {
            log.error("没有可用的AI服务");
            return;
        }

        // 先剔除被 Nacos 关闭的 Provider（如 ollama.enabled=false），再按优先级排序。
        // 只影响「是否参与轮转与探测」；Provider Bean 一律保留——业务类对其是硬依赖注入
        // （AiConversationMemaryService 依赖 OllamaService），删 Bean 会让上下文启动失败。
        availableServices = new ArrayList<>();
        for (CallAi service : aiServices) {
            if (service.isEnabled()) {
                availableServices.add(service);
            } else {
                log.info("AI 服务已由配置关闭，不参与轮转与探测: {}", service.getModelName());
            }
        }
        availableServices.sort(Comparator.comparingInt(CallAi::getPriority));

        if (availableServices.isEmpty()) {
            log.error("所有 AI 服务都被配置关闭（检查 glm/qianwen/nikoapigpt/ollama 的 enabled），AI 功能将不可用");
        }

        // 初始化状态
        for (CallAi service : availableServices) {
            healthCache.put(service.getModelName(), false);
            failCount.put(service.getModelName(), new AtomicInteger(0));
            nextHealthCheckAt.put(service.getModelName(), 0L);
            log.info("注册AI服务: {} (优先级: {})",
                    service.getModelName(), service.getPriority());
        }

        if (healthCheckEnabled) {
            // 启动健康检查
            startHealthCheck();
        } else {
            // 探测定时任务已关闭：乐观地把全部 Provider 标记为可用，交给调用时的失败计数
            // （MAX_FAIL_COUNT）与 switchToNext 按需发现故障。若仍保持初始的 false，
            // getAvailableService() 会因为「没有任何健康服务」而直接返回 null（降级），AI 会不可用。
            for (CallAi service : availableServices) {
                healthCache.put(service.getModelName(), true);
            }
            log.info("AI 健康探测定时任务已关闭（ai.health-check.enabled=false），改为调用时按需发现故障");
        }

        log.info("AI服务管理器初始化完成，共 {} 个服务", availableServices.size());
    }

    /**
     * 获取当前可用的AI服务
     */
    public CallAi getAvailableService() {
        if (availableServices.isEmpty()) {
            throw new BizException(AiErrorCode.AI_SERVICE_NOT_READY);
        }

        int startIndex = currentIndex.get();
        int size = availableServices.size();
        int attempts = 0;

        while (attempts < size) {
            int index = (startIndex + attempts) % size;
            CallAi service = availableServices.get(index);

            if (isServiceHealthy(service)) {
                // 切换到该服务
                currentIndex.set(index);
                log.info("使用AI服务: {}", service.getModelName());
                return service;
            }
            attempts++;
        }

        // 所有服务都不可用，使用降级服务
        return null;
    }

    /**
     * 检查服务是否健康
     */
    private boolean isServiceHealthy(CallAi service) {
        String modelName = service.getModelName();

        // 检查缓存
        Boolean cached = healthCache.get(modelName);
        return Boolean.TRUE.equals(cached);
    }

    /**
     * 记录调用失败
     */
    public void recordFailure(CallAi service) {
        String modelName = service.getModelName();
        AtomicInteger count = failCount.get(modelName);
        if (count != null) {
            int fails = count.incrementAndGet();
            log.warn("服务 {} 失败次数: {}/{}", modelName, fails, MAX_FAIL_COUNT);

            if (fails >= MAX_FAIL_COUNT) {
                healthCache.put(modelName, false);
                nextHealthCheckAt.put(modelName,
                        System.currentTimeMillis() + UNAVAILABLE_CHECK_INTERVAL_MILLIS);
                log.warn("服务 {} 连续失败{}次，标记为不可用", modelName, MAX_FAIL_COUNT);
                // 切换到下一个服务
                switchToNext();
            }
        }
    }

    /**
     * 记录调用成功
     */
    public void recordSuccess(CallAi service) {
        String modelName = service.getModelName();
        AtomicInteger count = failCount.get(modelName);
        if (count != null) {
            count.set(0);
            healthCache.put(modelName, true);
        }
    }

    /**
     * 切换到下一个服务
     */
    public void switchToNext() {
        int nextIndex = (currentIndex.get() + 1) % availableServices.size();
        currentIndex.set(nextIndex);
        log.info("切换到下一个服务: {}", availableServices.get(nextIndex).getModelName());
    }

    /**
     * 获取降级服务
     */
    private CallAi getFallbackService() {
        for (CallAi service : availableServices) {
            if ("Fallback".equals(service.getModelName())) {
                log.warn("使用降级服务");
                return service;
            }
        }
        // 如果没有降级服务，返回第一个
        return availableServices.get(0);
    }

    /**
     * 启动健康检查定时任务
     */
    private void startHealthCheck() {
        healthCheckExecutor.scheduleAtFixedRate(this::checkDueServices, 0, 30, TimeUnit.SECONDS);
    }

    private void checkDueServices() {
        long now = System.currentTimeMillis();
        for (CallAi service : availableServices) {
            String modelName = service.getModelName();
            long nextCheck = nextHealthCheckAt.getOrDefault(modelName, 0L);
            if (nextCheck > now) {
                continue;
            }

            boolean wasHealthy = Boolean.TRUE.equals(healthCache.get(modelName));
            boolean available;
            try {
                available = service.isAvailable();
            } catch (Exception e) {
                available = false;
                log.warn("AI服务探测异常，model={}, error={}", modelName, e.getMessage(), e);
            }

            healthCache.put(modelName, available);
            if (available) {
                failCount.get(modelName).set(0);
            }
            nextHealthCheckAt.put(modelName, now + (available
                    ? AVAILABLE_CHECK_INTERVAL_MILLIS
                    : UNAVAILABLE_CHECK_INTERVAL_MILLIS));

            if (wasHealthy != available) {
                log.info("AI服务健康状态变化，model={}, available={}, nextCheckInSeconds={}",
                        modelName, available, available ? 180 : 30);
            } else {
                log.debug("AI服务探测完成，model={}, available={}, nextCheckInSeconds={}",
                        modelName, available, available ? 180 : 30);
            }
        }
    }

    @PreDestroy
    public void stopHealthCheck() {
        healthCheckExecutor.shutdownNow();
    }

    /**
     * 获取所有服务状态
     */
    public Map<String, Boolean> getAllServiceStatus() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        for (CallAi service : availableServices) {
            status.put(service.getModelName(), healthCache.getOrDefault(service.getModelName(), false));
        }
        return status;
    }

    /**
     * 手动切换服务
     */
    public boolean switchToService(String modelName) {
        for (int i = 0; i < availableServices.size(); i++) {
            if (availableServices.get(i).getModelName().equals(modelName)) {
                currentIndex.set(i);
                log.info("手动切换到服务: {}", modelName);
                return true;
            }
        }
        return false;
    }
}
