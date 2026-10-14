package org.example.serviceai.AIServiceManager;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.config.AiConfig;
import org.example.serviceai.entry.Model;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceai.intifer.CallAi;
import org.example.serviceai.service.AiAggregateService;
import org.example.serviceai.util.GetAPiKey;
import org.example.serviceapi.exception.BizException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 端点的组合根（工厂 + 注册表）：启动时组装 Provider 列表、维护健康状态与失败计数，
 * 对外提供 {@link #getAvailableService()} 轮转取用。
 *
 * <p>Provider 的唯一来源是配置：{@code ai.models} 里每条配置生成一个
 * {@link AiAggregateService}。这里 new 出 Provider 不违反 IoC——本类及
 * {@link GetAPiKey}、{@link AiConfig} 都由容器注入，被 new 的只是「由配置数据决定的实例」，
 * 与 {@code @Bean} 方法里 new 一个对象同理。</p>
 */
@Service
@Slf4j
public class AIServiceManager {

    private final AiConfig aiConfig;
    private final GetAPiKey getAPiKey;

    AIServiceManager(AiConfig aiConfig, GetAPiKey getAPiKey) {
        this.aiConfig = aiConfig;
        this.getAPiKey = getAPiKey;
    }

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
        // 组装 + 注册必须原子完成，故合并在单个 @PostConstruct 里（两个 @PostConstruct 的执行
        // 顺序依赖反射实现，不可靠）。
        List<CallAi> candidates = buildProviders();
        if (candidates.isEmpty()) {
            log.error("没有可用的AI服务");
            return;
        }

        // 先剔除被配置关闭的端点（ai.models[].enabled=false），再按优先级排序。
        availableServices = new ArrayList<>();
        for (CallAi service : candidates) {
            if (service.isEnabled()) {
                availableServices.add(service);
            } else {
                log.info("AI 服务已由配置关闭，不参与轮转与探测: {}", service.getModelName());
            }
        }
        availableServices.sort(Comparator.comparingInt(CallAi::getPriority));

        if (availableServices.isEmpty()) {
            log.error("所有 AI 服务都被配置关闭（检查 ai.models[].enabled），AI 功能将不可用");
        }

        // 初始化状态
        for (CallAi service : availableServices) {
            healthCache.put(service.getModelName(), false);
            failCount.put(service.getModelName(), new AtomicInteger(0));
            nextHealthCheckAt.put(service.getModelName(), 0L);
            log.info("注册AI服务: {} (优先级: {})",
                    service.getModelName(), service.getPriority());
        }

        if (aiConfig.isHealthCheckEnabled()) {
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
     * 按 {@code ai.models} 逐条生成 Provider。单条配置有问题只跳过它，不让整个上下文启动失败。
     */
    private List<CallAi> buildProviders() {
        List<Model> models = aiConfig.getModels();
        if (models == null || models.isEmpty()) {
            log.error("未配置 ai.models（或为空），无法装配任何 AI 端点");
            return List.of();
        }

        List<CallAi> providers = new ArrayList<>(models.size());
        for (Model model : models) {
            try {
                providers.add(new AiAggregateService(model, getAPiKey));
            } catch (Exception exception) {
                log.error("AI 端点配置无效，已跳过: name={}", model.getName(), exception);
            }
        }
        log.info("已按 ai.models 生成 {} 个 AI 端点", providers.size());
        return providers;
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
