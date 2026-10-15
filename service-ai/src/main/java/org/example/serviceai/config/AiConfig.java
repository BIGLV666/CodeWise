package org.example.serviceai.config;

import lombok.Data;
import org.example.serviceai.entry.Model;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * {@code ai.*} 配置：{@code ai.models} 声明全部可用 AI 端点，
 * {@code ai.health-check.enabled} 控制后台健康探测定时任务。
 */
@Configuration
@ConfigurationProperties(prefix = "ai")
@Data
public class AiConfig {

    /** 配置化的 AI 端点列表；非空时由 AiServiceFactory 按 {@code type} 逐条生成 Provider。 */
    private List<Model> models;

    /**
     * 绑定 {@code ai.health-check.enabled}。
     *
     * <p>这里刻意用嵌套对象而不是扁平字段 {@code healthCheckEnabled}：后者的规范化键是
     * {@code ai.health-check-enabled}，线上 Nacos 现存的 {@code ai.health-check.enabled}
     * 会静默失效（探测被重新打开）。</p>
     */
    private HealthCheck healthCheck = new HealthCheck();

    /** 是否启用 Provider 健康探测定时任务，缺省 true。 */
    public boolean isHealthCheckEnabled() {
        return healthCheck == null || healthCheck.isEnabled();
    }

    /** 便于代码内构造（测试/默认值）设置该开关。 */
    public void setHealthCheckEnabled(boolean enabled) {
        if (healthCheck == null) {
            healthCheck = new HealthCheck();
        }
        healthCheck.setEnabled(enabled);
    }

    @Data
    public static class HealthCheck {
        /**
         * 置为 false 后不再起后台周期探测线程，改为在真实调用时靠失败计数与切换机制
         * 按需发现故障——这样未部署的 Provider（如 Ollama）不会持续刷连接失败日志。
         */
        private boolean enabled = true;
    }
}
