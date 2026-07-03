package org.example.serviceai.AIServiceManager;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.intifer.CallAi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class AIServiceManager {
    @Autowired(required = false)
    private List<CallAi> aiServices;

    // 所有服务（按优先级排序）
    private List<CallAi> availableServices = new ArrayList<>();

    // 当前使用的服务索引
    private final AtomicInteger currentIndex = new AtomicInteger(0);

    // 服务健康状态缓存
    private final Map<String, Boolean> healthCache = new ConcurrentHashMap<>();

    // 失败计数
    private final Map<String, AtomicInteger> failCount = new ConcurrentHashMap<>();

    // 最大失败次数
    private static final int MAX_FAIL_COUNT = 3;

    @PostConstruct
    public void init() {
        if (aiServices == null || aiServices.isEmpty()) {
            log.error("没有可用的AI服务");
            return;
        }

        // 按优先级排序
        availableServices = new ArrayList<>(aiServices);
        availableServices.sort(Comparator.comparingInt(CallAi::getPriority));

        // 初始化状态
        for (CallAi service : availableServices) {
            healthCache.put(service.getModelName(), true);
            failCount.put(service.getModelName(), new AtomicInteger(0));
            log.info("注册AI服务: {} (优先级: {})",
                    service.getModelName(), service.getPriority());
        }

        // 启动健康检查
        startHealthCheck();

        log.info("AI服务管理器初始化完成，共 {} 个服务", availableServices.size());
    }

    /**
     * 获取当前可用的AI服务
     */
    public CallAi getAvailableService() {
        if (availableServices.isEmpty()) {
            throw new RuntimeException("没有可用的AI服务");
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
        if (cached != null && cached) {
            return true;
        }

        // 实时检查
        try {
            boolean available = service.isAvailable();
            healthCache.put(modelName, available);
            if (available) {
                failCount.get(modelName).set(0);
            }
            return available;
        } catch (Exception e) {
            log.warn("服务 {} 健康检查失败: {}", modelName, e.getMessage());
            healthCache.put(modelName, false);
            return false;
        }
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
        // 使用定时任务每30秒检查一次
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(30000);
                    for (CallAi service : availableServices) {
                        try {
                            boolean available = service.isAvailable();
                            healthCache.put(service.getModelName(), available);
                            if (available) {
                                failCount.get(service.getModelName()).set(0);
                            }
                        } catch (Exception e) {
                            log.debug("健康检查失败: {}", service.getModelName());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "AI-Service-Health-Checker").start();
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
