package org.example.serviceai.service;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.AIServiceManager.AIServiceManager;
import org.example.serviceai.intifer.CallAi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AIService  {
    @Autowired
    private AIServiceManager serviceManager;

    /**
     * 调用AI服务（带自动故障切换）
     */
    public String callAi(String prompt) {
        CallAi service = null;

        try {
            // 获取可用的服务
            service = serviceManager.getAvailableService();
            log.info("使用 {} 服务", service.getModelName());

            // 调用
            long startTime = System.currentTimeMillis();
            String result = service.callAi(prompt);
            long cost = System.currentTimeMillis() - startTime;

            // 记录成功
            serviceManager.recordSuccess(service);
            log.info("{} 服务调用成功，耗时: {}ms", service.getModelName(), cost);

            return result;

        } catch (Exception e) {
            log.error("AI服务调用失败: {}", e.getMessage());

            if (service != null) {
                // 记录失败
                serviceManager.recordFailure(service);
            }

            // 尝试切换到下一个服务重试
            return retryWithNextService(prompt, service);
        }
    }

    /**
     * 切换到下一个服务重试
     */
    private String retryWithNextService(String prompt, CallAi failedService) {
        // 切换服务
        serviceManager.switchToNext();

        try {
            CallAi nextService = serviceManager.getAvailableService();

            // 如果是同一个服务，说明没有其他可用服务
            if (nextService == failedService) {
                log.error("没有其他可用服务");
                throw new RuntimeException("所有AI服务都不可用");
            }

            log.info("切换到 {} 服务重试", nextService.getModelName());
            String result = nextService.callAi(prompt);
            serviceManager.recordSuccess(nextService);
            return result;

        } catch (Exception e) {
            log.error("重试也失败: {}", e.getMessage());
            throw new RuntimeException("AI服务调用失败: " + e.getMessage());
        }
    }

}
